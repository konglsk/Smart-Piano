// src/main/java/com/example/esp32spp/LoadingActivity.java
package com.example.esp32spp;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

public class LoadingActivity extends AppCompatActivity {

    private static final long LOADING_MS = 1200;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_loading);

        ImageView imgLogo = findViewById(R.id.imgLogo);
        TextView tvLoading = findViewById(R.id.tvLoading);
        ProgressBar prog = findViewById(R.id.prog);

        tvLoading.setText("載入中，請稍候...");

        new Handler().postDelayed(() -> {
            Intent it = new Intent(LoadingActivity.this, MainActivity.class);
            startActivity(it);
            finish();
        }, LOADING_MS);
    }
}
