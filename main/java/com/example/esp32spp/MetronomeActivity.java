package com.example.esp32spp;

import android.bluetooth.BluetoothSocket;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Queue;

/**
 * MetronomeActivity - full implementation
 *
 * Notes:
 * - This implementation attempts to load res/raw/note1 .. note37 and res/raw/click automatically.
 *   If your project uses different resource names, either rename the raw files or replace the
 *   resource name generation logic below.
 * - BluetoothSocket is obtained from BluetoothSocketHolder if available; incoming messages are
 *   parsed similarly to ControlActivity/NOSoundActivity.
 */
public class MetronomeActivity extends AppCompatActivity {

    private TextView tvBpm, tvBeats;
    private SeekBar seekBpm;
    private Button btnDecBpm, btnIncBpm, btnDecBeats, btnIncBeats, btnStartStop, btnTap;
    private LinearLayout beatGrid;

    // SoundPool for metronome click + 37 notes
    private SoundPool soundPool;
    private int clickSoundId = -1;
    private final int NOTE_COUNT = 37;
    private final int[] resNotes = new int[NOTE_COUNT];      // R.raw.note1 ... note37 (auto lookup)
    private final int[] soundIdsNotes = new int[NOTE_COUNT];
    private final boolean[] loadedNotes = new boolean[NOTE_COUNT];
    private boolean clickLoaded = false;
    private final Queue<Integer> pendingPlays = new ArrayDeque<>();

    // metronome timing
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean running = false;
    private int bpm = 100;
    private int beatsPerBar = 4;
    private int currentBeat = 0;
    private long intervalMs() { return 60000L / Math.max(1, bpm); }

    // Bluetooth I/O
    private BluetoothSocket socket;
    private InputStream in;
    private OutputStream out;
    private Thread listenThread;

    // UI highlight color backup (if needed)
    private int highlightColor = 0xFFFFD54F;
    private int normalColor = 0xFFDDDDDD;

    // pending play request class (for queued plays)
    private static class PlayReq { int idx; PlayReq(int i){ idx = i; } }
    private final Queue<PlayReq> pendingNoteRequests = new ArrayDeque<>();

    // Runnable for metronome ticks
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            // play click
            if (clickLoaded && clickSoundId != 0) soundPool.play(clickSoundId, 1f, 1f, 1, 0, 1f);
            // highlight beat
            highlightBeat(currentBeat);
            // optional: send bluetooth marker at bar start
            if (currentBeat == 0) sendBluetoothMarker("M");
            currentBeat = (currentBeat + 1) % Math.max(1, beatsPerBar);
            handler.postDelayed(this, intervalMs());
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_metronome);

        tvBpm = findViewById(R.id.tvBpm);
        tvBeats = findViewById(R.id.tvBeats);
        seekBpm = findViewById(R.id.seekBpm);
        btnDecBpm = findViewById(R.id.btnDecBpm);
        btnIncBpm = findViewById(R.id.btnIncBpm);
        btnDecBeats = findViewById(R.id.btnDecBeats);
        btnIncBeats = findViewById(R.id.btnIncBeats);
        btnStartStop = findViewById(R.id.btnStartStop);
        btnTap = findViewById(R.id.btnTap);
        beatGrid = findViewById(R.id.beatGrid);

        // Attempt to auto-resolve raw resources: note1..note37 and click
        for (int i = 0; i < NOTE_COUNT; i++) {
            String resName = "note" + (i + 1); // expects raw/note1.wav ... note37.wav
            int resId = getResources().getIdentifier(resName, "raw", getPackageName());
            resNotes[i] = resId;
        }
        int clickRes = getResources().getIdentifier("click", "raw", getPackageName());
        if (clickRes != 0) clickSoundId = -1; // will be loaded in initSoundPoolAndNotes

        initSoundPoolAndNotes();

        // UI initial values
        bpm = 100;
        beatsPerBar = 4;
        tvBpm.setText(String.valueOf(bpm));
        tvBeats.setText(String.valueOf(beatsPerBar));
        seekBpm.setMax(244 - 30);
        seekBpm.setProgress(bpm - 30);

        seekBpm.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                bpm = 30 + progress;
                tvBpm.setText(String.valueOf(bpm));
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnDecBpm.setOnClickListener(v -> {
            bpm = Math.max(30, bpm - 1);
            seekBpm.setProgress(bpm - 30);
            tvBpm.setText(String.valueOf(bpm));
        });
        btnIncBpm.setOnClickListener(v -> {
            bpm = Math.min(244, bpm + 1);
            seekBpm.setProgress(bpm - 30);
            tvBpm.setText(String.valueOf(bpm));
        });

        btnDecBeats.setOnClickListener(v -> {
            beatsPerBar = Math.max(1, beatsPerBar - 1);
            tvBeats.setText(String.valueOf(beatsPerBar));
            buildBeatGrid();
        });
        btnIncBeats.setOnClickListener(v -> {
            beatsPerBar = Math.min(12, beatsPerBar + 1);
            tvBeats.setText(String.valueOf(beatsPerBar));
            buildBeatGrid();
        });

        btnStartStop.setOnClickListener(v -> {
            if (running) stopMetronome(); else startMetronome();
        });

        btnTap.setOnClickListener(v -> {
            // Simple tap-tempo implementation: record last two taps and compute BPM
            handleTapTempo();
        });

        buildBeatGrid();

        // Try to obtain BluetoothSocket and start listening
        socket = BluetoothSocketHolder.getSocket();
        if (socket != null) {
            try {
                in = socket.getInputStream();
                out = socket.getOutputStream();
                startListening();
            } catch (IOException ignored) {}
        }
    }

    // Initialize SoundPool and load click + notes (if resources exist)
    private void initSoundPoolAndNotes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder().setMaxStreams(8).setAudioAttributes(attrs).build();
        } else {
            soundPool = new SoundPool(8, android.media.AudioManager.STREAM_MUSIC, 0);
        }

        // Load notes if resource ids are present
        for (int i = 0; i < NOTE_COUNT; i++) {
            if (resNotes[i] != 0) {
                soundIdsNotes[i] = soundPool.load(this, resNotes[i], 1);
            } else {
                soundIdsNotes[i] = 0;
            }
            loadedNotes[i] = false;
        }

        // Try to load click (resource name "click")
        int clickRes = getResources().getIdentifier("click", "raw", getPackageName());
        if (clickRes != 0) {
            clickSoundId = soundPool.load(this, clickRes, 1);
            clickLoaded = false;
        } else {
            clickSoundId = 0;
            clickLoaded = false;
        }

        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (status == 0) {
                if (sampleId == clickSoundId) clickLoaded = true;
                for (int i = 0; i < soundIdsNotes.length; i++) {
                    if (soundIdsNotes[i] == sampleId) {
                        loadedNotes[i] = true;
                        break;
                    }
                }
                flushPendingNotes();
            }
        });
    }

    private void flushPendingNotes() {
        while (!pendingNoteRequests.isEmpty()) {
            PlayReq pr = pendingNoteRequests.poll();
            playNoteNow(pr.idx);
        }
    }

    // Immediately play a note index (0..36)
    private void playNoteNow(int idx) {
        if (idx < 0 || idx >= NOTE_COUNT) return;
        if (soundIdsNotes[idx] == 0) return;
        if (loadedNotes[idx]) {
            soundPool.play(soundIdsNotes[idx], 1f, 1f, 1, 0, 1f);
        } else {
            pendingNoteRequests.add(new PlayReq(idx));
        }
    }

    // Build beat grid UI blocks
    private void buildBeatGrid() {
        beatGrid.removeAllViews();
        for (int i = 0; i < beatsPerBar; i++) {
            View v = new View(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, 100, 1f);
            lp.setMargins(6, 6, 6, 6);
            v.setLayoutParams(lp);
            v.setBackgroundColor(normalColor);
            beatGrid.addView(v);
        }
        currentBeat = 0;
    }

    // Highlight the beat at index; if idx < 0, clear highlights
    private void highlightBeat(int idx) {
        int count = beatGrid.getChildCount();
        for (int i = 0; i < count; i++) {
            View v = beatGrid.getChildAt(i);
            if (i == idx) v.setBackgroundColor(highlightColor);
            else v.setBackgroundColor(normalColor);
        }
    }

    private void startMetronome() {
        running = true;
        currentBeat = 0;
        btnStartStop.setText("停止");
        handler.post(tickRunnable);
        sendBluetoothMarker("S"); // notify ESP32 start (optional)
    }

    private void stopMetronome() {
        running = false;
        btnStartStop.setText("開始");
        handler.removeCallbacks(tickRunnable);
        sendBluetoothMarker("X"); // notify ESP32 stop (optional)
        highlightBeat(-1);
    }

    // Simple tap tempo: measure intervals between taps and set BPM
    private long lastTapTime = 0;
    private int tapCount = 0;
    private void handleTapTempo() {
        long now = System.currentTimeMillis();
        if (lastTapTime == 0 || now - lastTapTime > 2000) {
            // reset if too long since last tap
            tapCount = 1;
            lastTapTime = now;
            tvBeats.post(() -> tvBeats.setText(String.valueOf(beatsPerBar)));
            return;
        }
        tapCount++;
        long interval = now - lastTapTime;
        lastTapTime = now;
        // compute BPM from moving average (approx)
        int newBpm = (int) Math.round(60000.0 / interval);
        newBpm = Math.max(30, Math.min(244, newBpm));
        bpm = newBpm;
        seekBpm.setProgress(bpm - 30);
        tvBpm.setText(String.valueOf(bpm));
    }

    // Parse and handle incoming messages from ESP32 (similar to other activities)
    private void handleIncomingMessageImmediate(String msg) {
        if (msg == null || msg.isEmpty()) return;
        // Examples: "PRESSEDn;B:x;O:y" or "ONn" / "OFF" / "OctN"
        if (msg.startsWith("PRESSED")) {
            try {
                String payload = msg.substring(7).trim();
                String[] parts = payload.split(";");
                int n = Integer.parseInt(parts[0].trim()); // assume n maps to note index
                int noteIndex = n - 1;
                if (noteIndex >= 0 && noteIndex < NOTE_COUNT) {
                    final int ni = noteIndex;
                    runOnUiThread(() -> playNoteNow(ni));
                }
            } catch (Exception ignored) {}
        } else if (msg.startsWith("ON")) {
            try {
                String num = msg.substring(2).trim();
                int n = Integer.parseInt(num);
                int noteIndex = n - 1;
                if (noteIndex >= 0 && noteIndex < NOTE_COUNT) {
                    runOnUiThread(() -> playNoteNow(noteIndex));
                }
            } catch (Exception ignored) {}
        } else if (msg.startsWith("OFF")) {
            // optional: stop or clear UI
        } else if (msg.startsWith("Oct")) {
            // optional: handle octave sync if needed
        } else {
            // other messages ignored or logged
        }
    }

    // Start listening thread for Bluetooth input
    private void startListening() {
        if (in == null) return;
        listenThread = new Thread(() -> {
            byte[] buffer = new byte[256];
            StringBuilder sb = new StringBuilder();
            try {
                while (!Thread.currentThread().isInterrupted() && in != null) {
                    int read;
                    try {
                        read = in.read(buffer);
                    } catch (IOException e) {
                        break;
                    }
                    if (read > 0) {
                        String chunk = new String(buffer, 0, read);
                        sb.append(chunk);
                        String data = sb.toString();
                        int idx;
                        while ((idx = findMessageEnd(data)) != -1) {
                            String msg = data.substring(0, idx).trim();
                            data = data.substring(idx);
                            final String finalMsg = msg;
                            handleIncomingMessageImmediate(finalMsg);
                        }
                        sb.setLength(0);
                        sb.append(data);
                    }
                }
            } finally {
                // cleanup if needed
            }
        });
        listenThread.start();
    }

    // Determine end index of a message in the buffer (mirrors other activities)
    private int findMessageEnd(String s) {
        int nl = s.indexOf('\n');
        if (nl != -1) return nl + 1;
        if (s.startsWith("PRESSED")) {
            int sem = s.indexOf(";O:");
            if (sem != -1) {
                int i = sem + 3;
                while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '-')) i++;
                if (i > sem + 3) return i;
            }
            return -1;
        } else if (s.startsWith("ON")) {
            int i = 2;
            while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
            if (i > 2) return i;
            return -1;
        } else if (s.startsWith("OFF")) {
            return 3;
        } else if (s.startsWith("Oct")) {
            int i = 3;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '-')) i++;
            if (i > 3) return i;
            return -1;
        }
        return -1;
    }

    // Send a short marker/tag to ESP32 (non-blocking)
    private void sendBluetoothMarker(String tag) {
        new Thread(() -> {
            try {
                BluetoothSocket sock = BluetoothSocketHolder.getSocket();
                if (sock != null && sock.isConnected()) {
                    OutputStream os = sock.getOutputStream();
                    if (os != null) {
                        os.write(tag.getBytes());
                        os.flush();
                    }
                }
            } catch (IOException ignored) {}
        }).start();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopMetronome();
        if (listenThread != null) {
            listenThread.interrupt();
            listenThread = null;
        }
        try {
            if (in != null) { in.close(); in = null; }
            if (out != null) { out.close(); out = null; }
            if (socket != null) {
                // Do not close socket here to avoid affecting other activities that may reuse it.
                // If you want to close it, also call BluetoothSocketHolder.clear();
            }
        } catch (IOException ignored) {}

        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }
}
