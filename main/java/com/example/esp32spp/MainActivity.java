package com.example.esp32spp;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * MainActivity - 完整檔案
 *
 * 功能重點：
 * - 啟動時會嘗試在已配對裝置中尋找名稱為 "ESP32_PIANO" 的裝置，若找到會自動填入 etDeviceName 並顯示狀態
 * - 按下 Scan 會列出已配對裝置供使用者選擇（按需請求 Android 12+ 的 BLUETOOTH_SCAN）
 * - 按下 Connect 會以 etDeviceName 的名稱或 address 嘗試連線（使用 SPP UUID）
 * - 不在啟動時強制請求危險權限；僅在需要時按需請求
 *
 * 請確認 layout (res/layout/activity_main.xml) 包含 id：
 * tvStatus, etDeviceName, btnScan, btnConnect, btnNext, btnRequestPermissions (或改名對應)
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final String DEFAULT_TARGET_NAME = "ESP32_PIANO";
    private static final int REQ_BLUETOOTH_SCAN = 5001;

    private TextView tvStatus;
    private EditText etDeviceName;
    private Button btnScan;
    private Button btnConnect;
    private Button btnNext;
    private Button btnStartRecord;

    private BluetoothAdapter btAdapter;
    private volatile Thread connectThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // 請確認 layout 有對應 id

        tvStatus = findViewById(R.id.tvStatus);
        etDeviceName = findViewById(R.id.etDeviceName);
        btnScan = findViewById(R.id.btnScan);
        btnConnect = findViewById(R.id.btnConnect);
        btnNext = findViewById(R.id.btnNext);
        btnStartRecord = findViewById(R.id.btnRequestPermissions); // 可視情況改名

        // 初始化藍牙 adapter（不會在啟動時請求權限）
        btAdapter = BluetoothAdapter.getDefaultAdapter();
        if (btAdapter == null) {
            tvStatus.setText("裝置不支援藍牙");
            btnScan.setEnabled(false);
            btnConnect.setEnabled(false);
            btnNext.setEnabled(false);
        }

        // 顯示預設 Device Name：先嘗試尋找已配對的 ESP32_PIANO，找不到則顯示預設名稱
        attemptAutoFillEsp32Name();

        // Scan 按鈕：列出已配對裝置供使用者選擇
        btnScan.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (btAdapter == null) return;
                if (!btAdapter.isEnabled()) {
                    Intent enableBt = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivity(enableBt);
                    tvStatus.setText("請開啟藍牙後再按 Scan");
                    return;
                }
                // Android 12+ 需要 BLUETOOTH_SCAN 才能掃描附近裝置；列出已配對裝置通常不需，但仍示範按需請求
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                        ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.BLUETOOTH_SCAN)
                                != PackageManager.PERMISSION_GRANTED) {
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("需要藍牙掃描權限")
                            .setMessage("列出附近或已配對的藍牙裝置可能需要授權。按「同意」會顯示系統授權畫面。")
                            .setPositiveButton("同意", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    ActivityCompat.requestPermissions(
                                            MainActivity.this,
                                            new String[]{Manifest.permission.BLUETOOTH_SCAN},
                                            REQ_BLUETOOTH_SCAN);
                                }
                            })
                            .setNegativeButton("取消", null)
                            .show();
                } else {
                    showPairedDevicesDialog();
                }
            }
        });

        // Connect 按鈕：使用 etDeviceName 的名稱或 address 嘗試連線
        btnConnect.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                if (btAdapter == null) return;
                if (!btAdapter.isEnabled()) {
                    Intent enableBt = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    startActivity(enableBt);
                    tvStatus.setText("請開啟藍牙後再按 Connect");
                    return;
                }
                String nameOrAddr = etDeviceName.getText().toString().trim();
                if (nameOrAddr.isEmpty()) nameOrAddr = DEFAULT_TARGET_NAME;
                startPersistentConnect(nameOrAddr);
            }
        });

        // Next 按鈕：進入 Menu（需先連線）
        btnNext.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                BluetoothSocket s = BluetoothSocketHolder.getSocket();
                if (s == null || !s.isConnected()) {
                    tvStatus.setText("尚未連線，無法進入控制頁面");
                    return;
                }
                startActivity(new Intent(MainActivity.this, MenuActivity.class));
            }
        });

        // StartRecord 按鈕：示意按需請求錄音權限（不在啟動時自動請求）
        btnStartRecord.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("錄音權限")
                        .setMessage("若要錄製系統或麥克風音訊，App 需要錄音權限。按「同意」會顯示系統授權畫面。")
                        .setPositiveButton("同意", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    ActivityCompat.requestPermissions(
                                            MainActivity.this,
                                            new String[]{Manifest.permission.RECORD_AUDIO},
                                            301);
                                }
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
    }

    /**
     * 嘗試在已配對裝置中尋找名稱為 DEFAULT_TARGET_NAME 的 ESP32 裝置（不分大小寫）
     * 若找到則自動填入 etDeviceName 並更新 tvStatus
     */
    private void attemptAutoFillEsp32Name() {
        if (btAdapter == null) {
            etDeviceName.setText(DEFAULT_TARGET_NAME);
            return;
        }
        // 先嘗試從已配對裝置中尋找
        BluetoothDevice found = findPairedDeviceByName(DEFAULT_TARGET_NAME);
        if (found != null) {
            String display = found.getName() != null ? found.getName() : found.getAddress();
            etDeviceName.setText(display);
            tvStatus.setText("找到已配對 ESP32 裝置: " + display);
        } else {
            // 若找不到，顯示預設名稱
            etDeviceName.setText(DEFAULT_TARGET_NAME);
            tvStatus.setText("未找到已配對的 ESP32 裝置，顯示預設名稱: " + DEFAULT_TARGET_NAME);
        }
    }

    /**
     * 在已配對裝置中尋找指定名稱（不分大小寫）
     * 回傳第一個匹配的 BluetoothDevice，找不到回傳 null
     */
    private BluetoothDevice findPairedDeviceByName(String targetName) {
        if (btAdapter == null || targetName == null) return null;
        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
        if (paired == null || paired.isEmpty()) return null;
        for (BluetoothDevice d : paired) {
            String name = d.getName();
            if (name != null && name.equalsIgnoreCase(targetName)) {
                return d;
            }
        }
        return null;
    }

    /**
     * 顯示已配對裝置的選單，使用者選擇後把名稱填入 etDeviceName
     */
    private void showPairedDevicesDialog() {
        if (btAdapter == null) return;
        Set<BluetoothDevice> paired = btAdapter.getBondedDevices();
        if (paired == null || paired.isEmpty()) {
            tvStatus.setText("沒有已配對裝置");
            new AlertDialog.Builder(this)
                    .setTitle("已配對裝置")
                    .setMessage("目前沒有已配對的藍牙裝置。請先在系統藍牙設定配對裝置。")
                    .setPositiveButton("好", null)
                    .show();
            return;
        }

        final List<String> items = new ArrayList<>();
        final List<BluetoothDevice> devices = new ArrayList<>();
        for (BluetoothDevice d : paired) {
            String name = d.getName();
            String addr = d.getAddress();
            items.add((name != null ? name : "(unknown)") + "  —  " + addr);
            devices.add(d);
        }

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, items);
        new AlertDialog.Builder(this)
                .setTitle("選擇已配對裝置")
                .setAdapter(adapter, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        BluetoothDevice chosen = devices.get(which);
                        String chosenName = chosen.getName() != null ? chosen.getName() : chosen.getAddress();
                        etDeviceName.setText(chosenName);
                        tvStatus.setText("選擇: " + chosenName + " (" + chosen.getAddress() + ")");
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    @SuppressLint("MissingPermission")
    private void startPersistentConnect(final String targetName) {
        if (connectThread != null && connectThread.isAlive()) {
            tvStatus.setText("正在嘗試連線中...");
            return;
        }

        connectThread = new Thread(new Runnable() {
            @Override
            public void run() {
                if (btAdapter == null) return;
                btAdapter.cancelDiscovery();
                BluetoothDevice target = null;
                // 優先比對名稱，再嘗試 address（若使用者輸入 address）
                for (BluetoothDevice d : btAdapter.getBondedDevices()) {
                    if (d.getName() != null && d.getName().equals(targetName)) {
                        target = d;
                        break;
                    }
                    if (d.getAddress() != null && d.getAddress().equalsIgnoreCase(targetName)) {
                        target = d;
                        break;
                    }
                }

                if (target == null) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvStatus.setText("找不到已配對裝置: " + targetName);
                        }
                    });
                    return;
                }

                try {
                    final BluetoothDevice finalTarget = target;
                    BluetoothSocket s = target.createRfcommSocketToServiceRecord(SPP_UUID);
                    s.connect();
                    BluetoothSocketHolder.setSocket(s);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            String connectedName = finalTarget.getName() != null ? finalTarget.getName() : finalTarget.getAddress();
                            tvStatus.setText("已連線到 " + connectedName);
                            etDeviceName.setText(connectedName);
                        }
                    });

                    // 簡單的讀取/寫入線程（示意）
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                InputStream in = s.getInputStream();
                                OutputStream out = s.getOutputStream();
                                byte[] buffer = new byte[256];
                                long lastPing = System.currentTimeMillis();

                                while (s.isConnected()) {
                                    int len = in.read(buffer);
                                    if (len > 0) {
                                        String msg = new String(buffer, 0, len);
                                        Log.d(TAG, "RX: " + msg);
                                    }
                                    if (System.currentTimeMillis() - lastPing > 5000) {
                                        out.write('p');
                                        lastPing = System.currentTimeMillis();
                                    }
                                }
                            } catch (IOException e) {
                                Log.e(TAG, "讀取線程斷線: " + e.getMessage());
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        tvStatus.setText("連線中斷，請重新連線");
                                    }
                                });
                            }
                        }
                    }).start();

                } catch (IOException e) {
                    final String msg = e.getMessage();
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            tvStatus.setText("連線失敗: " + (msg != null ? msg : "IOException"));
                        }
                    });
                }
            }
        });
        connectThread.start();
    }

    // 處理權限回呼（BLUETOOTH_SCAN 或其他）
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_BLUETOOTH_SCAN) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showPairedDevicesDialog();
            } else {
                Toast.makeText(this, "藍牙掃描權限未授予，無法列出裝置", Toast.LENGTH_SHORT).show();
                new AlertDialog.Builder(this)
                        .setTitle("權限未授予")
                        .setMessage("若要列出已配對裝置，請在系統設定中授予藍牙掃描權限。")
                        .setPositiveButton("前往設定", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                                intent.setData(Uri.parse("package:" + getPackageName()));
                                startActivity(intent);
                            }
                        })
                        .setNegativeButton("取消", null)
                        .show();
            }
        } else if (requestCode == 301) { // RECORD_AUDIO legacy callback
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "錄音權限已授予", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "錄音權限未授予", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
