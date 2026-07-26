package com.example.esp32spp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaRecorder;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.Button;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.Queue;

/**
 * ControlActivity.java
 *
 * - Binary packet parsing: 3 bytes: 0x7E, evt, payload
 * - SoundPool preload + pre-warm, MAX_STREAMS increased for simultaneous overlapping playback
 * - Immediate playNow(...) on PRESSED/ON events on UI thread
 * - Recording support (MediaProjection fallback to mic)
 *
 * Requirements:
 * - BluetoothSocketHolder.getSocket() returns connected BluetoothSocket
 * - InternalAudioRecorder class available for MediaProjection capture (optional)
 * - Short audio samples placed in res/raw with resource ids matching resOctaves arrays
 */
public class ControlActivity extends AppCompatActivity {
    private static final String TAG = "ControlActivity";
    private static final int REQ_RECORD_PERM = 301;
    private static final int REQ_MEDIA_PROJECTION = 4001;

    private TextView tvConn, tvEvents;
    private Button[] btns = new Button[7];
    private Button btnAllOff;
    private Button btnBlack, btnOctUp, btnOctDown;

    private TextView tvRecordTimer;
    private Button btnStartRecord, btnStopRecord;

    private BluetoothSocket socket;
    private OutputStream out;
    private InputStream in;
    private Thread listenThread;

    private SoundPool soundPool;
    private final int MAX_STREAMS = 24;

    private final int[][] resOctaves = new int[4][];
    private final int[][] soundIdsOctaves = new int[4][];
    private final boolean[][] loadedOctaves = new boolean[4][];
    private final Queue<PlayRequest> pendingPlays = new ArrayDeque<>();

    private ColorStateList[] originalTint = new ColorStateList[7];

    private int localOctaveFlag = 0;
    private boolean localBlackPressed = false;

    private int totalSamplesToLoad = 0;
    private int loadedSamplesCount = 0;
    private boolean allSamplesLoaded = false;

    private InternalAudioRecorder internalRecorder;
    private MediaRecorder micRecorder;

    private File currentRecordingFile;
    private boolean isRecording = false;
    private boolean usingInternalCapture = false;
    private long recordStartTimeMs = 0;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Handler recordHandler = new Handler(Looper.getMainLooper());
    private final Runnable recordTicker = new Runnable() {
        @Override
        public void run() {
            if (!isRecording) return;
            long elapsed = System.currentTimeMillis() - recordStartTimeMs;
            int seconds = (int) (elapsed / 1000);
            int mins = seconds / 60;
            int secs = seconds % 60;
            if (tvRecordTimer != null) tvRecordTimer.setText(String.format(Locale.getDefault(), "%02d:%02d", mins, secs));
            recordHandler.postDelayed(this, 500);
        }
    };

    // Binary event codes
    private static final int BIN_HDR = 0x7E;
    private static final int BIN_EVT_PRESSED = 0x01;
    private static final int BIN_EVT_ON = 0x02;
    private static final int BIN_EVT_OFF = 0x03;
    private static final int BIN_EVT_ONBLACK = 0x04;
    private static final int BIN_EVT_OFFBLACK = 0x05;
    private static final int BIN_EVT_OCT = 0x06;

    private static class PlayRequest {
        int octaveIndex;
        int noteIndex;
        PlayRequest(int o, int n) { octaveIndex = o; noteIndex = n; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);

        tvConn = findViewById(R.id.tvConn);
        tvEvents = findViewById(R.id.tvEvents);

        btns[0] = findViewById(R.id.btn1);
        btns[1] = findViewById(R.id.btn2);
        btns[2] = findViewById(R.id.btn3);
        btns[3] = findViewById(R.id.btn4);
        btns[4] = findViewById(R.id.btn5);
        btns[5] = findViewById(R.id.btn6);
        btns[6] = findViewById(R.id.btn7);
        btnAllOff = findViewById(R.id.btnAllOff);

        btnBlack = findViewById(R.id.btnBlack);
        btnOctUp = findViewById(R.id.btnOctUp);
        btnOctDown = findViewById(R.id.btnOctDown);

        tvRecordTimer = findViewById(R.id.tvRecordTimer);
        btnStartRecord = findViewById(R.id.btnStartRecord);
        btnStopRecord = findViewById(R.id.btnStopRecord);

        if (tvRecordTimer != null) tvRecordTimer.setText("00:00");
        if (btnStopRecord != null) btnStopRecord.setEnabled(false);

        for (int i = 0; i < btns.length; ++i) {
            if (btns[i] != null) originalTint[i] = btns[i].getBackgroundTintList();
        }

        setButtonsEnabled(false);
        initSoundPoolAndResources();

        socket = BluetoothSocketHolder.getSocket();
        if (socket == null) {
            if (tvConn != null) tvConn.setText("連線狀態：未連線");
        } else {
            try {
                out = socket.getOutputStream();
                in = socket.getInputStream();
                if (tvConn != null) tvConn.setText("連線狀態：已連線");
                if (allSamplesLoaded) setButtonsEnabled(true);
                startListening();
            } catch (IOException e) {
                if (tvConn != null) tvConn.setText("取得 IO 失敗: " + e.getMessage());
                setButtonsEnabled(false);
            }
        }

        for (int i = 0; i < 7; ++i) {
            final int idx = i;
            if (btns[i] != null) {
                btns[i].setOnClickListener(v -> {
                    handleLocalPlayAndUi(idx);
                    sendChar((char)('1' + idx));
                });
            }
        }

        if (btnAllOff != null) btnAllOff.setOnClickListener(v -> {
            clearAllButtonLit();
            sendChar('0');
        });

        if (btnBlack != null) {
            btnBlack.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    localBlackPressed = true;
                    sendChar('b');
                    btnBlack.setPressed(true);
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    localBlackPressed = false;
                    sendChar('B');
                    btnBlack.setPressed(false);
                }
                return true;
            });
        }

        if (btnOctUp != null) {
            btnOctUp.setOnClickListener(v -> {
                if (localOctaveFlag < 1) localOctaveFlag++;
                sendChar('u');
                if (tvConn != null) tvConn.setText("連線狀態：已連線  八度偏移: " + localOctaveFlag);
            });
        }
        if (btnOctDown != null) {
            btnOctDown.setOnClickListener(v -> {
                if (localOctaveFlag > -1) localOctaveFlag--;
                sendChar('d');
                if (tvConn != null) tvConn.setText("連線狀態：已連線  八度偏移: " + localOctaveFlag);
            });
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) internalRecorder = new InternalAudioRecorder(this);
        else internalRecorder = null;

        if (btnStartRecord != null) {
            btnStartRecord.setOnClickListener(v -> {
                if (isRecording) return;
                showRecordingRationaleAndStart();
            });
        }

        if (btnStopRecord != null) {
            btnStopRecord.setOnClickListener(v -> stopRecordingAll());
        }
    }

    private void showRecordingRationaleAndStart() {
        new AlertDialog.Builder(this)
                .setTitle("需要授權以錄製系統音訊")
                .setMessage("App 需要擷取手機播放的媒體聲音並儲存為錄音檔。請授予錄音權限，接著系統會顯示授權畫面以允許擷取系統音訊。若拒絕，將改用麥克風錄音。")
                .setPositiveButton("同意並繼續", (d, w) -> {
                    if (!hasRecordPermission()) {
                        requestRecordPermission();
                    } else {
                        startMediaProjectionOrFallback();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void startMediaProjectionOrFallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && internalRecorder != null) {
            Intent intent = internalRecorder.createScreenCaptureIntent();
            startActivityForResult(intent, REQ_MEDIA_PROJECTION);
        } else {
            startMicRecordingFallback();
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        for (int i = 0; i < 7; ++i) if (btns[i] != null) btns[i].setEnabled(enabled);
        if (btnAllOff != null) btnAllOff.setEnabled(enabled);
        if (btnBlack != null) btnBlack.setEnabled(enabled);
        if (btnOctUp != null) btnOctUp.setEnabled(enabled);
        if (btnOctDown != null) btnOctDown.setEnabled(enabled);
    }

    private void initSoundPoolAndResources() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(MAX_STREAMS)
                    .setAudioAttributes(attrs)
                    .build();
        } else {
            soundPool = new SoundPool(MAX_STREAMS, android.media.AudioManager.STREAM_MUSIC, 0);
        }

        // Put short WAV/OGG samples in res/raw and ensure resource ids match below
        resOctaves[0] = new int[] {
                R.raw.c3, R.raw.c3s, R.raw.d3, R.raw.d3s, R.raw.e3, R.raw.f3,
                R.raw.f3s, R.raw.g3, R.raw.g3s, R.raw.a3, R.raw.a3s, R.raw.b3
        };
        resOctaves[1] = new int[] {
                R.raw.c4, R.raw.c4s, R.raw.d4, R.raw.d4s, R.raw.e4, R.raw.f4,
                R.raw.f4s, R.raw.g4, R.raw.g4s, R.raw.a4, R.raw.a4s, R.raw.b4
        };
        resOctaves[2] = new int[] {
                R.raw.c5, R.raw.c5s, R.raw.d5, R.raw.d5s, R.raw.e5, R.raw.f5,
                R.raw.f5s, R.raw.g5, R.raw.g5s, R.raw.a5, R.raw.a5s, R.raw.b5
        };
        resOctaves[3] = new int[] { R.raw.c6 };

        totalSamplesToLoad = 0;
        for (int o = 0; o < resOctaves.length; ++o) {
            soundIdsOctaves[o] = new int[resOctaves[o].length];
            loadedOctaves[o] = new boolean[resOctaves[o].length];
            totalSamplesToLoad += resOctaves[o].length;
        }

        for (int o = 0; o < resOctaves.length; ++o) {
            for (int i = 0; i < resOctaves[o].length; ++i) {
                int resId = resOctaves[o][i];
                if (resId != 0) {
                    soundIdsOctaves[o][i] = soundPool.load(this, resId, 1);
                } else {
                    soundIdsOctaves[o][i] = 0;
                }
            }
        }

        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (status == 0) {
                loadedSamplesCount++;
                outer:
                for (int o = 0; o < soundIdsOctaves.length; ++o) {
                    for (int i = 0; i < soundIdsOctaves[o].length; ++i) {
                        if (soundIdsOctaves[o][i] == sampleId) {
                            loadedOctaves[o][i] = true;
                            break outer;
                        }
                    }
                }
                if (loadedSamplesCount >= totalSamplesToLoad) {
                    allSamplesLoaded = true;
                    uiHandler.post(() -> {
                        // pre-warm: silent quick play then stop to reduce first-play latency
                        for (int o = 0; o < soundIdsOctaves.length; ++o) {
                            for (int i = 0; i < soundIdsOctaves[o].length; ++i) {
                                int sid = soundIdsOctaves[o][i];
                                if (sid != 0 && loadedOctaves[o][i]) {
                                    int streamId = soundPool.play(sid, 0f, 0f, 1, 0, 1f);
                                    if (streamId != 0) soundPool.stop(streamId);
                                }
                            }
                        }
                        setButtonsEnabled(true);
                        flushPendingPlays();
                        if (tvEvents != null) tvEvents.setText("音檔已載入完成");
                    });
                }
            }
        });
    }

    private void flushPendingPlays() {
        while (!pendingPlays.isEmpty()) {
            PlayRequest pr = pendingPlays.poll();
            playNow(pr.octaveIndex, pr.noteIndex);
        }
    }

    private void playNow(int octaveIndex, int noteIndex) {
        if (soundPool == null) return;
        if (octaveIndex < 0 || octaveIndex > 3) return;
        if (octaveIndex == 3) {
            if (soundIdsOctaves[3].length > 0 && loadedOctaves[3][0]) {
                int sid = soundIdsOctaves[3][0];
                int stream = soundPool.play(sid, 1f, 1f, 1, 0, 1f);
                Log.d(TAG, "playNow c6 stream=" + stream);
            } else {
                pendingPlays.add(new PlayRequest(octaveIndex, noteIndex));
            }
            return;
        }
        if (noteIndex < 0 || noteIndex >= soundIdsOctaves[octaveIndex].length) return;
        int sid = soundIdsOctaves[octaveIndex][noteIndex];
        if (sid != 0 && loadedOctaves[octaveIndex][noteIndex]) {
            int streamId = soundPool.play(sid, 1f, 1f, 1, 0, 1f);
            Log.d(TAG, "playNow octave=" + octaveIndex + " note=" + noteIndex + " stream=" + streamId);
        } else {
            pendingPlays.add(new PlayRequest(octaveIndex, noteIndex));
        }
    }

    private void handleLocalPlayAndUi(int btnIndex) {
        int whiteIdx = btnIndex + 1;
        if (localBlackPressed) {
            PlayRequest pr = mapBlackPlayRequest(whiteIdx, localOctaveFlag);
            if (pr != null) playNow(pr.octaveIndex, pr.noteIndex);
        } else {
            int octaveIndex = localOctaveFlag + 1;
            if (octaveIndex < 0) octaveIndex = 0;
            if (octaveIndex > 2) octaveIndex = 2;
            final int ni = mapWhiteIndexToNoteIndex(whiteIdx);
            playNow(octaveIndex, ni);
        }
        uiHandler.post(() -> setButtonLit(btnIndex, true));
    }

    private void sendChar(char c) {
        new Thread(() -> {
            try {
                if (out == null) {
                    uiHandler.post(() -> { if (tvEvents != null) tvEvents.setText("尚未連線"); });
                    return;
                }
                out.write((byte) c);
                out.flush();
                uiHandler.post(() -> { if (tvEvents != null) tvEvents.setText("已傳送: " + c); });
            } catch (IOException e) {
                uiHandler.post(() -> { if (tvEvents != null) tvEvents.setText("傳送失敗: " + e.getMessage()); });
            }
        }).start();
    }

    private void startListening() {
        listenThread = new Thread(() -> {
            byte[] buffer = new byte[512];
            StringBuilder sb = new StringBuilder();
            try {
                while (!Thread.currentThread().isInterrupted() && in != null) {
                    int read;
                    try { read = in.read(buffer); } catch (IOException e) { break; }
                    if (read > 0) {
                        int pos = 0;
                        while (pos < read) {
                            int b = buffer[pos] & 0xFF;
                            if (b == BIN_HDR) {
                                int remain = read - pos - 1;
                                if (remain >= 2) {
                                    byte evt = buffer[pos + 1];
                                    byte payload = buffer[pos + 2];
                                    handleBinaryPacket(evt, payload);
                                    pos += 3;
                                } else {
                                    for (int i = pos; i < read; ++i) sb.append((char) buffer[i]);
                                    break;
                                }
                            } else {
                                sb.append((char) b);
                                pos++;
                            }
                        }
                        String data = sb.toString();
                        int idx;
                        while ((idx = findMessageEnd(data)) != -1) {
                            String msg = data.substring(0, idx).trim();
                            data = data.substring(idx);
                            handleIncomingMessageImmediate(msg);
                            uiHandler.post(() -> { if (tvEvents != null) tvEvents.setText("ESP32: " + msg); });
                        }
                        sb.setLength(0);
                        sb.append(data);
                    }
                }
            } finally { }
        });
        listenThread.start();
    }

    private void handleBinaryPacket(byte evt, byte payload) {
        int ievt = evt & 0xFF;
        if (ievt == BIN_EVT_PRESSED) {
            int keyIdx = (payload & 0x07) + 1;
            int bflag = ((payload >> 3) & 0x01);
            int octaveMapped = (payload >> 4) & 0x03;
            int oflag;
            if (octaveMapped == 0) oflag = -1;
            else if (octaveMapped == 1) oflag = 0;
            else oflag = 1;

            final PlayRequest pr;
            if (bflag == 1) {
                pr = mapBlackPlayRequest(keyIdx, oflag);
            } else {
                int octaveIndex = oflag + 1;
                if (octaveIndex < 0) octaveIndex = 0;
                if (octaveIndex > 2) octaveIndex = 2;
                final int ni = mapWhiteIndexToNoteIndex(keyIdx);
                pr = new PlayRequest(octaveIndex, ni);
            }

            if (pr != null) {
                uiHandler.post(() -> playNow(pr.octaveIndex, pr.noteIndex));
            }

            final int btnIndex = keyIdx - 1;
            uiHandler.post(() -> {
                if (btnIndex >= 0 && btnIndex < btns.length && btns[btnIndex] != null) {
                    setButtonLit(btnIndex, false);
                }
                if (tvEvents != null) tvEvents.setText("ESP32 PRESSED (bin): " + keyIdx);
            });

        } else if (ievt == BIN_EVT_ON) {
            int keyIdx = (payload & 0x07) + 1;
            final int btnIndex = keyIdx - 1;
            int whiteIdx = keyIdx;
            int octaveIndex = localOctaveFlag + 1;
            if (octaveIndex < 0) octaveIndex = 0;
            if (octaveIndex > 2) octaveIndex = 2;
            final int ni = mapWhiteIndexToNoteIndex(whiteIdx);
            final PlayRequest pr = new PlayRequest(octaveIndex, ni);
            uiHandler.post(() -> {
                setButtonLit(btnIndex, true);
                playNow(pr.octaveIndex, pr.noteIndex);
                if (tvEvents != null) tvEvents.setText("ESP32 ON (bin): " + keyIdx);
            });
        } else if (ievt == BIN_EVT_OFF) {
            uiHandler.post(() -> {
                clearAllButtonLit();
                if (tvEvents != null) tvEvents.setText("ESP32 OFF (bin)");
            });
        } else if (ievt == BIN_EVT_ONBLACK) {
            uiHandler.post(() -> { if (tvEvents != null) tvEvents.setText("ESP32 ONBLACK (bin)"); });
        } else if (ievt == BIN_EVT_OFFBLACK) {
            uiHandler.post(() -> { if (tvEvents != null) tvEvents.setText("ESP32 OFFBLACK (bin)"); });
        } else if (ievt == BIN_EVT_OCT) {
            int octaveMapped = payload & 0x03;
            int oct;
            if (octaveMapped == 0) oct = -1;
            else if (octaveMapped == 1) oct = 0;
            else oct = 1;
            uiHandler.post(() -> { if (tvConn != null) tvConn.setText("連線狀態：已連線  八度偏移: " + oct); });
        } else {
            uiHandler.post(() -> { if (tvEvents != null) tvEvents.setText("ESP32 Unknown bin evt: " + ievt); });
        }
    }

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

    private void handleIncomingMessageImmediate(String msg) {
        if (msg == null || msg.isEmpty()) return;
        if (msg.startsWith("PRESSED")) {
            try {
                String payload = msg.substring(7).trim();
                String[] parts = payload.split(";");
                int n = Integer.parseInt(parts[0].trim());
                int bflag = 0;
                int oflag = 0;
                for (int i = 1; i < parts.length; ++i) {
                    String p = parts[i];
                    if (p.startsWith("B:")) bflag = Integer.parseInt(p.substring(2).trim());
                    else if (p.startsWith("O:")) oflag = Integer.parseInt(p.substring(2).trim());
                }

                final PlayRequest pr;
                if (bflag == 1) {
                    pr = mapBlackPlayRequest(n, oflag);
                } else {
                    int octaveIndex = oflag + 1;
                    if (octaveIndex < 0) octaveIndex = 0;
                    if (octaveIndex > 2) octaveIndex = 2;
                    final int ni = mapWhiteIndexToNoteIndex(n);
                    pr = new PlayRequest(octaveIndex, ni);
                }

                if (pr != null) {
                    uiHandler.post(() -> playNow(pr.octaveIndex, pr.noteIndex));
                }

                final int btnIndex = n - 1;
                uiHandler.post(() -> {
                    if (btnIndex >= 0 && btnIndex < btns.length && btns[btnIndex] != null) {
                        setButtonLit(btnIndex, false);
                    }
                });
            } catch (Exception ignored) {}
        } else if (msg.startsWith("ON")) {
            try {
                String num = msg.substring(2).trim();
                int n = Integer.parseInt(num);
                final int btnIndex = n - 1;

                int whiteIdx = n;
                int octaveIndex = localOctaveFlag + 1;
                if (octaveIndex < 0) octaveIndex = 0;
                if (octaveIndex > 2) octaveIndex = 2;
                final int ni = mapWhiteIndexToNoteIndex(whiteIdx);
                final PlayRequest pr = new PlayRequest(octaveIndex, ni);
                uiHandler.post(() -> {
                    setButtonLit(btnIndex, true);
                    playNow(pr.octaveIndex, pr.noteIndex);
                });
            } catch (Exception ignored) {}
        } else if (msg.startsWith("OFF")) {
            uiHandler.post(this::clearAllButtonLit);
        } else if (msg.startsWith("Oct")) {
            try {
                String v = msg.substring(3).trim();
                int oct = Integer.parseInt(v);
                uiHandler.post(() -> { if (tvConn != null) tvConn.setText("連線狀態：已連線  八度偏移: " + oct); });
            } catch (Exception ignored) {}
        } else if (msg.startsWith("ONBLACK") || msg.startsWith("OFFBLACK")) {
            uiHandler.post(() -> { if (tvEvents != null) tvEvents.setText(msg); });
        } else {
            uiHandler.post(() -> { if (tvEvents != null) tvEvents.setText(msg); });
        }
    }

    private void setButtonLit(int idx, boolean lit) {
        if (idx < 0 || idx >= btns.length) return;
        Button b = btns[idx];
        if (b == null) return;
        if (lit) {
            b.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFD54F")));
            b.setTextColor(Color.BLACK);
        } else {
            if (originalTint[idx] != null) b.setBackgroundTintList(originalTint[idx]);
            else b.setBackgroundTintList(ColorStateList.valueOf(Color.WHITE));
            b.setTextColor(Color.BLACK);
        }
    }

    private void clearAllButtonLit() {
        for (int i = 0; i < btns.length; ++i) setButtonLit(i, false);
    }

    private int mapWhiteIndexToNoteIndex(int whiteIdx) {
        switch (whiteIdx) {
            case 1: return 0;
            case 2: return 2;
            case 3: return 4;
            case 4: return 5;
            case 5: return 7;
            case 6: return 9;
            case 7: return 11;
            default: return 0;
        }
    }

    private PlayRequest mapBlackPlayRequest(int whiteIdx, int oflag) {
        int octaveIndex = oflag + 1;
        switch (whiteIdx) {
            case 1: return new PlayRequest(octaveIndex, 1);
            case 2: return new PlayRequest(octaveIndex, 3);
            case 3: return new PlayRequest(octaveIndex, 5);
            case 4: return new PlayRequest(octaveIndex, 6);
            case 5: return new PlayRequest(octaveIndex, 8);
            case 6: return new PlayRequest(octaveIndex, 10);
            case 7:
                if (oflag == 1) {
                    return new PlayRequest(3, 0);
                } else {
                    int nextOct = octaveIndex + 1;
                    if (nextOct > 2) nextOct = 2;
                    return new PlayRequest(nextOct, 0);
                }
            default: return null;
        }
    }

    // Recording helpers

    private boolean hasRecordPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestRecordPermission() {
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.RECORD_AUDIO},
                REQ_RECORD_PERM);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_RECORD_PERM) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && internalRecorder != null) {
                    Intent intent = internalRecorder.createScreenCaptureIntent();
                    startActivityForResult(intent, REQ_MEDIA_PROJECTION);
                } else startMicRecordingFallback();
            } else if (tvEvents != null) tvEvents.setText("錄音權限未授予");
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null && internalRecorder != null) {
                currentRecordingFile = new File(getRecordingsDir(), createRecordingFileName());
                try {
                    internalRecorder.startProjection(resultCode, data, currentRecordingFile);
                    isRecording = true;
                    usingInternalCapture = true;
                    recordStartTimeMs = System.currentTimeMillis();
                    recordHandler.post(recordTicker);
                    if (btnStartRecord != null) btnStartRecord.setEnabled(false);
                    if (btnStopRecord != null) btnStopRecord.setEnabled(true);
                    if (tvEvents != null) tvEvents.setText("錄音中 (系統音訊): " + currentRecordingFile.getAbsolutePath());
                } catch (IOException e) {
                    if (tvEvents != null) tvEvents.setText("啟動系統音訊擷取失敗，改用麥克風: " + e.getMessage());
                    startMicRecordingFallback();
                }
            } else {
                if (tvEvents != null) tvEvents.setText("MediaProjection 授權被拒絕");
                startMicRecordingFallback();
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private File getRecordingsDir() {
        File musicDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (musicDir == null) musicDir = getFilesDir();
        File recDir = new File(musicDir, "Recordings");
        if (!recDir.exists()) recDir.mkdirs();
        return recDir;
    }

    private String createRecordingFileName() {
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return "REC_" + ts + ".m4a";
    }

    @SuppressLint("MissingPermission")
    private void startMicRecordingFallback() {
        try {
            currentRecordingFile = new File(getRecordingsDir(), createRecordingFileName());
            micRecorder = new MediaRecorder();
            micRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            micRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            micRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            micRecorder.setAudioEncodingBitRate(128000);
            micRecorder.setAudioSamplingRate(44100);
            micRecorder.setOutputFile(currentRecordingFile.getAbsolutePath());
            micRecorder.prepare();
            micRecorder.start();

            isRecording = true;
            usingInternalCapture = false;
            recordStartTimeMs = System.currentTimeMillis();
            recordHandler.post(recordTicker);

            if (btnStartRecord != null) btnStartRecord.setEnabled(false);
            if (btnStopRecord != null) btnStopRecord.setEnabled(true);
            if (tvEvents != null) tvEvents.setText("錄音中（麥克風）: " + currentRecordingFile.getAbsolutePath());
        } catch (IOException | IllegalStateException e) {
            if (tvEvents != null) tvEvents.setText("啟動麥克風錄音失敗: " + e.getMessage());
            if (micRecorder != null) {
                try { micRecorder.release(); } catch (Exception ignored) {}
                micRecorder = null;
            }
            isRecording = false;
        }
    }

    private void stopRecordingAll() {
        if (!isRecording) return;
        if (usingInternalCapture) {
            if (internalRecorder != null) internalRecorder.stop();
        } else {
            if (micRecorder != null) {
                try { micRecorder.stop(); } catch (RuntimeException e) { Log.w(TAG, "stop exception", e); }
                try { micRecorder.release(); } catch (Exception ignored) {}
                micRecorder = null;
            }
        }
        isRecording = false;
        usingInternalCapture = false;
        recordHandler.removeCallbacks(recordTicker);
        if (tvRecordTimer != null) tvRecordTimer.setText("00:00");
        if (btnStartRecord != null) btnStartRecord.setEnabled(true);
        if (btnStopRecord != null) btnStopRecord.setEnabled(false);
        if (tvEvents != null) tvEvents.setText("錄音已停止，檔案儲存於 Recordings 資料夾");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (listenThread != null) {
            listenThread.interrupt();
            listenThread = null;
        }
        if (isRecording) {
            try { stopRecordingAll(); } catch (Exception ignored) {}
        }
        try {
            if (in != null) { in.close(); in = null; }
            if (out != null) { out.close(); out = null; }
            if (socket != null) {
                socket.close();
                BluetoothSocketHolder.clear();
                socket = null;
            }
        } catch (IOException ignored) {}
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }
}
