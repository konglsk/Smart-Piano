package com.example.esp32spp;

import android.content.Intent;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * RecordingListActivity
 *
 * - 顯示 getExternalFilesDir(Environment.DIRECTORY_MUSIC)/Recordings 下的錄音檔
 * - 點擊播放；長按分享（使用 FileProvider）
 */
public class RecordingListActivity extends AppCompatActivity {

    private ListView lvRecordings;
    private TextView tvTitle;
    private Button btnRefresh, btnStopPlay;

    private List<File> recordings = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private MediaPlayer mediaPlayer;
    private File currentPlaying;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recording_list);

        lvRecordings = findViewById(R.id.lvRecordings);
        tvTitle = findViewById(R.id.tvRecordListTitle);
        btnRefresh = findViewById(R.id.btnRefreshList);
        btnStopPlay = findViewById(R.id.btnStopPlay);

        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        lvRecordings.setAdapter(adapter);

        btnRefresh.setOnClickListener(v -> refreshList());
        btnStopPlay.setOnClickListener(v -> stopPlayback());

        lvRecordings.setOnItemClickListener((parent, view, position, id) -> {
            File f = recordings.get(position);
            playFile(f);
        });

        lvRecordings.setOnItemLongClickListener((parent, view, position, id) -> {
            File f = recordings.get(position);
            shareFile(f);
            return true;
        });

        refreshList();
    }

    private File getRecordingsDir() {
        File musicDir = getExternalFilesDir(Environment.DIRECTORY_MUSIC);
        if (musicDir == null) musicDir = getFilesDir();
        File recDir = new File(musicDir, "Recordings");
        if (!recDir.exists()) recDir.mkdirs();
        return recDir;
    }

    private void refreshList() {
        File dir = getRecordingsDir();
        File[] files = dir.listFiles((d, name) -> {
            String ln = name.toLowerCase();
            return ln.endsWith(".m4a") || ln.endsWith(".mp4") || ln.endsWith(".wav") || ln.endsWith(".aac") || ln.endsWith(".3gp");
        });
        recordings.clear();
        adapter.clear();
        if (files != null && files.length > 0) {
            Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
            recordings.addAll(Arrays.asList(files));
            for (File f : recordings) adapter.add(f.getName());
        }
        adapter.notifyDataSetChanged();
    }

    private void playFile(File f) {
        stopPlayback();
        mediaPlayer = new MediaPlayer();
        try {
            mediaPlayer.setDataSource(f.getAbsolutePath());
            mediaPlayer.prepare();
            mediaPlayer.start();
            currentPlaying = f;
            btnStopPlay.setEnabled(true);
            btnStopPlay.setOnClickListener(v -> stopPlayback());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void stopPlayback() {
        if (mediaPlayer != null) {
            try { mediaPlayer.stop(); } catch (Exception ignored) {}
            try { mediaPlayer.release(); } catch (Exception ignored) {}
            mediaPlayer = null;
            currentPlaying = null;
        }
        btnStopPlay.setEnabled(false);
    }

    private void shareFile(File f) {
        if (f == null || !f.exists()) return;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", f);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("audio/*");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(share, "分享錄音"));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopPlayback();
    }
}
