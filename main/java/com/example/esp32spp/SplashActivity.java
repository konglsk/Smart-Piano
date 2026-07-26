package com.example.esp32spp;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import androidx.appcompat.app.AppCompatActivity;
import android.view.WindowManager;

/**
 * SplashActivity
 * - 顯示啟動畫面若干毫秒後轉到 MainActivity
 * - 放在 src/main/java/com/example/esp32spp/
 */
public class SplashActivity extends AppCompatActivity {
    private static final long SPLASH_MS = 1200L; // 顯示時間，毫秒

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 可選：全螢幕顯示（若使用 Toolbar 或狀態列控制請調整）
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        setContentView(R.layout.activity_splash);

        // 延遲後啟動 MainActivity（或你想要的啟動 Activity）
        new Handler().postDelayed(() -> {
            Intent it = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(it);
            finish();
        }, SPLASH_MS);
    }
}
