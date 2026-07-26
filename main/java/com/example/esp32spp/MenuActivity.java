package com.example.esp32spp;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

/**
 * MenuActivity - 綁定四個按鈕並啟動對應 Activity
 */
public class MenuActivity extends AppCompatActivity {
    private static final String TAG = "MenuActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_menu);

        Button btnControl = findViewById(R.id.btnControl);
        Button btnNOSound = findViewById(R.id.btnNOSound);
        Button btnMetronome = findViewById(R.id.btnMetronome);
        Button btnRecordingList = findViewById(R.id.btnRecordingList);

        if (btnControl == null || btnNOSound == null || btnMetronome == null || btnRecordingList == null) {
            Log.e(TAG, "One or more menu buttons are null. Check activity_menu.xml ids.");
            Toast.makeText(this, "Menu UI 初始化失敗，請檢查 layout id", Toast.LENGTH_LONG).show();
            return;
        }

        btnControl.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MenuActivity.this, ControlActivity.class));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start ControlActivity", e);
                    Toast.makeText(MenuActivity.this, "無法開啟 Control", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnNOSound.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MenuActivity.this, NOSoundActivity.class));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start NOSoundActivity", e);
                    Toast.makeText(MenuActivity.this, "無法開啟 NOSound", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnMetronome.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MenuActivity.this, MetronomeActivity.class));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start MetronomeActivity", e);
                    Toast.makeText(MenuActivity.this, "無法開啟 Metronome", Toast.LENGTH_SHORT).show();
                }
            }
        });

        btnRecordingList.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(MenuActivity.this, RecordingListActivity.class));
                } catch (Exception e) {
                    Log.e(TAG, "Failed to start RecordingListActivity", e);
                    Toast.makeText(MenuActivity.this, "無法開啟 Recording List", Toast.LENGTH_SHORT).show();
                }
            }
        });
    }
}
