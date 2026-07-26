// NOSoundActivity.java  (完整檔案，替換現有檔案)
package com.example.esp32spp;

import android.bluetooth.BluetoothSocket;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.HapticFeedbackConstants;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ToggleButton;
import androidx.appcompat.app.AppCompatActivity;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Queue;

public class NOSoundActivity extends AppCompatActivity {
    private TextView tvConn, tvEvents;
    private Button[] btns = new Button[7];
    private Button btnAllOff;
    private ToggleButton btnBlack;
    private Button btnOctUp, btnOctDown;
    private BluetoothSocket socket;
    private OutputStream out;
    private InputStream in;
    private Thread listenThread;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    // SoundPool + 資源陣列（octave3, octave4, octave5, c6）
    private SoundPool soundPool;
    private final int[][] resOctaves = new int[4][];
    private final int[][] soundIdsOctaves = new int[4][];
    private final boolean[][] loadedOctaves = new boolean[4][];
    private final Queue<PlayRequest> pendingPlays = new ArrayDeque<>();
    private static final int MAX_STREAMS = 16; // 提高允許重疊數量

    // 儲存按鈕原始 tint 以便還原
    private ColorStateList[] originalTint = new ColorStateList[7];
    // 本機狀態（手機端預期狀態）
    private int localOctaveFlag = 0; // -1,0,1
    private boolean localBlackPressed = false;

    private static class PlayRequest {
        int octaveIndex; // 0->oct3,1->oct4,2->oct5,3->c6
        int noteIndex; // 0..11 for octaves, 0 for c6
        PlayRequest(int o, int n) { octaveIndex = o; noteIndex = n; }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_nosound);

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

        for (int i = 0; i < btns.length; ++i) originalTint[i] = btns[i].getBackgroundTintList();

        initSoundPoolAndResources();

        socket = BluetoothSocketHolder.getSocket();
        if (socket == null) {
            tvConn.setText("連線狀態：未連線");
            setButtonsEnabled(false);
            return;
        }
        try {
            out = socket.getOutputStream();
            in = socket.getInputStream();
            tvConn.setText("連線狀態：已連線");
            setButtonsEnabled(true);
            startListening();
        } catch (IOException e) {
            tvConn.setText("取得 IO 失敗: " + e.getMessage());
            setButtonsEnabled(false);
        }

        // 手機按下白鍵：立即播放（或模擬播放）、更新 UI 為已點亮，並傳送指令給 ESP32
        for (int i = 0; i < 7; ++i) {
            final int idx = i;
            btns[i].setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                handleLocalPlayAndUi(idx);
                sendChar((char)('1' + idx));
            });
        }

        btnAllOff.setOnClickListener(v -> {
            clearAllButtonLit();
            sendChar('0');
        });

        if (btnBlack != null) {
            btnBlack.setOnTouchListener((v, event) -> {
                if (event.getAction() == MotionEvent.ACTION_DOWN) {
                    localBlackPressed = true;
                    sendChar('b'); // black down
                    btnBlack.setChecked(true);
                } else if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
                    localBlackPressed = false;
                    sendChar('B'); // black up
                    btnBlack.setChecked(false);
                }
                return true;
            });
        }

        if (btnOctUp != null) {
            btnOctUp.setOnClickListener(v -> {
                if (localOctaveFlag < 1) localOctaveFlag++;
                sendChar('u');
                tvConn.setText("連線狀態：已連線 八度偏移: " + localOctaveFlag);
            });
        }
        if (btnOctDown != null) {
            btnOctDown.setOnClickListener(v -> {
                if (localOctaveFlag > -1) localOctaveFlag--;
                sendChar('d');
                tvConn.setText("連線狀態：已連線 八度偏移: " + localOctaveFlag);
            });
        }
    }

    private void setButtonsEnabled(boolean enabled) {
        for (int i = 0; i < 7; ++i) btns[i].setEnabled(enabled);
        btnAllOff.setEnabled(enabled);
        if (btnBlack != null) btnBlack.setEnabled(enabled);
        if (btnOctUp != null) btnOctUp.setEnabled(enabled);
        if (btnOctDown != null) btnOctDown.setEnabled(enabled);
    }

    private void initSoundPoolAndResources() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build();
            soundPool = new SoundPool.Builder()
                    .setMaxStreams(MAX_STREAMS)
                    .setAudioAttributes(attrs)
                    .build();
        } else {
            soundPool = new SoundPool(MAX_STREAMS, android.media.AudioManager.STREAM_MUSIC, 0);
        }

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

        for (int o = 0; o < resOctaves.length; ++o) {
            soundIdsOctaves[o] = new int[resOctaves[o].length];
            loadedOctaves[o] = new boolean[resOctaves[o].length];
        }
        for (int o = 0; o < resOctaves.length; ++o) {
            for (int i = 0; i < resOctaves[o].length; ++i) {
                soundIdsOctaves[o][i] = soundPool.load(this, resOctaves[o][i], 1);
            }
        }
        soundPool.setOnLoadCompleteListener((sp, sampleId, status) -> {
            if (status == 0) {
                for (int o = 0; o < soundIdsOctaves.length; ++o) {
                    for (int i = 0; i < soundIdsOctaves[o].length; ++i) {
                        if (soundIdsOctaves[o][i] == sampleId) {
                            loadedOctaves[o][i] = true;
                            break;
                        }
                    }
                }
                // 立即播放等待中的請求（若有）
                flushPendingPlays();
            }
        });
    }

    private void flushPendingPlays() {
        while (!pendingPlays.isEmpty()) {
            PlayRequest pr = pendingPlays.poll();
            // 直接播放（不透過 uiHandler）
            playNowImmediate(pr.octaveIndex, pr.noteIndex);
        }
    }

    // 直接在任何執行緒呼叫 play（SoundPool 是 thread-safe）
    private void playNowImmediate(int octaveIndex, int noteIndex) {
        if (soundPool == null) return;
        if (octaveIndex == 3) {
            if (loadedOctaves[3][0]) soundPool.play(soundIdsOctaves[3][0], 1f, 1f, 1, 0, 1f);
            return;
        }
        if (octaveIndex < 0 || octaveIndex > 2) return;
        if (noteIndex < 0 || noteIndex >= soundIdsOctaves[octaveIndex].length) return;
        if (loadedOctaves[octaveIndex][noteIndex]) {
            soundPool.play(soundIdsOctaves[octaveIndex][noteIndex], 1f, 1f, 1, 0, 1f);
        } else {
            pendingPlays.add(new PlayRequest(octaveIndex, noteIndex));
        }
    }

    // 原本的 playNow（保留給 UI 呼叫）
    private void playNow(int octaveIndex, int noteIndex) {
        uiHandler.post(() -> playNowImmediate(octaveIndex, noteIndex));
    }

    // 手機按下時的本機播放與 UI 更新
    private void handleLocalPlayAndUi(int btnIndex) {
        int whiteIdx = btnIndex + 1;
        if (localBlackPressed) {
            PlayRequest pr = mapBlackPlayRequest(whiteIdx, localOctaveFlag);
            if (pr != null) {
                playNowImmediate(pr.octaveIndex, pr.noteIndex);
            }
        } else {
            int octaveIndex = localOctaveFlag + 1; // -1..1 -> 0..2
            if (octaveIndex < 0) octaveIndex = 0;
            if (octaveIndex > 2) octaveIndex = 2;
            final int oi = octaveIndex;
            final int ni = mapWhiteIndexToNoteIndex(whiteIdx);
            playNowImmediate(oi, ni);
        }
        uiHandler.post(() -> setButtonLit(btnIndex, true));
    }

    private void sendChar(char c) {
        new Thread(() -> {
            try {
                if (out == null) {
                    uiHandler.post(() -> tvEvents.setText("尚未連線"));
                    return;
                }
                out.write((byte) c);
                out.flush();
                uiHandler.post(() -> tvEvents.setText("已傳送: " + c));
            } catch (IOException e) {
                uiHandler.post(() -> tvEvents.setText("傳送失敗: " + e.getMessage()));
            }
        }).start();
    }

    private void startListening() {
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
                            // 直接處理訊息（在接收執行緒）
                            handleIncomingMessageImmediate(msg);
                            // UI event log 更新（非阻塞）
                            uiHandler.post(() -> tvEvents.setText("ESP32: " + msg));
                        }
                        sb.setLength(0);
                        sb.append(data);
                    }
                }
            } finally {
            }
        });
        listenThread.start();
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

    // 處理 ESP32 訊息：播放（PRESSED）、更新 UI（ON / PRESSED / OFF / Oct）
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
                // 立即播放（直接呼叫，不透過 uiHandler）
                if (bflag == 1) {
                    PlayRequest pr = mapBlackPlayRequest(n, oflag);
                    if (pr != null) {
                        playNowImmediate(pr.octaveIndex, pr.noteIndex);
                    }
                } else {
                    int octaveIndex = oflag + 1;
                    if (octaveIndex < 0) octaveIndex = 0;
                    if (octaveIndex > 2) octaveIndex = 2;
                    final int ni = mapWhiteIndexToNoteIndex(n);
                    playNowImmediate(octaveIndex, ni);
                }
                final int btnIndex = n - 1;
                // UI 更新（按鍵燈）放到主線程
                uiHandler.post(() -> setButtonLit(btnIndex, false));
            } catch (Exception ignored) {}
        } else if (msg.startsWith("ON")) {
            try {
                String num = msg.substring(2).trim();
                int n = Integer.parseInt(num);
                final int btnIndex = n - 1;
                uiHandler.post(() -> setButtonLit(btnIndex, true));
            } catch (Exception ignored) {}
        } else if (msg.startsWith("OFF")) {
            uiHandler.post(this::clearAllButtonLit);
        } else if (msg.startsWith("Oct")) {
            try {
                String v = msg.substring(3).trim();
                int oct = Integer.parseInt(v);
                localOctaveFlag = oct; // 同步本機 octaveFlag
                final int finalOct = oct;
                uiHandler.post(() -> tvConn.setText("連線狀態：已連線 八度偏移: " + finalOct));
            } catch (Exception ignored) {}
        } else if (msg.startsWith("ONBLACK") || msg.startsWith("OFFBLACK")) {
            uiHandler.post(() -> tvEvents.setText(msg));
        } else {
            uiHandler.post(() -> tvEvents.setText(msg));
        }
    }

    // 將白鍵按鈕標示為已點亮或還原
    private void setButtonLit(int idx, boolean lit) {
        if (idx < 0 || idx >= btns.length) return;
        Button b = btns[idx];
        if (lit) {
            b.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FFD54F"))); // amber
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

    // 白鍵編號 (1..7) -> octave 中 note index (0..11)
    private int mapWhiteIndexToNoteIndex(int whiteIdx) {
        switch (whiteIdx) {
            case 1: return 0; // C
            case 2: return 2; // D
            case 3: return 4; // E
            case 4: return 5; // F
            case 5: return 7; // G
            case 6: return 9; // A
            case 7: return 11; // B
            default: return 0;
        }
    }

    // 黑鍵對應（根據 whiteIdx 與 octaveFlag）
    private PlayRequest mapBlackPlayRequest(int whiteIdx, int oflag) {
        int octaveIndex = oflag + 1; // -1..1 -> 0..2
        switch (whiteIdx) {
            case 1: return new PlayRequest(octaveIndex, 1); // C#
            case 2: return new PlayRequest(octaveIndex, 3); // D#
            case 3: return new PlayRequest(octaveIndex, 5); // E -> F
            case 4: return new PlayRequest(octaveIndex, 6); // F#
            case 5: return new PlayRequest(octaveIndex, 8); // G#
            case 6: return new PlayRequest(octaveIndex, 10); // A#
            case 7:
                if (oflag == 1) {
                    return new PlayRequest(3, 0); // c6
                } else {
                    int nextOct = octaveIndex + 1;
                    if (nextOct > 2) nextOct = 2;
                    return new PlayRequest(nextOct, 0);
                }
            default: return null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (listenThread != null) {
            listenThread.interrupt();
            listenThread = null;
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
