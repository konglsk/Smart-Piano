package com.example.esp32spp;

import android.content.Context;
import android.content.Intent;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.util.Log;
import androidx.annotation.RequiresApi;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * InternalAudioRecorder
 *
 * - 使用 AudioPlaybackCapture (MediaProjection) 擷取系統/媒體聲音
 * - 將 PCM 經由 MediaCodec 編碼為 AAC，並用 MediaMuxer 輸出為 .m4a
 *
 * 注意:
 * - 需要 API 29+ (Android 10)
 * - 呼叫 startProjection 之前必須先由 Activity 取得 MediaProjection 的授權 Intent 結果 (resultCode, data)
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class InternalAudioRecorder {
    private static final String TAG = "InternalAudioRecorder";

    private final Context context;
    private final MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;

    private AudioRecord audioRecord;
    private MediaCodec encoder;
    private MediaMuxer muxer;
    private int audioTrackIndex = -1;
    private boolean muxerStarted = false;
    private Thread captureThread;
    private volatile boolean capturing = false;

    private final int sampleRate = 44100;
    private final int channelConfig = AudioFormat.CHANNEL_IN_STEREO;
    private final int channelCount = 2;
    private final int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
    private final int bitRate = 128000;

    private File outFile;

    public InternalAudioRecorder(Context ctx) {
        this.context = ctx.getApplicationContext();
        projectionManager = (MediaProjectionManager) ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE);
    }

    /**
     * Activity 用來啟動系統授權畫面的 Intent
     */
    public Intent createScreenCaptureIntent() {
        return projectionManager.createScreenCaptureIntent();
    }

    /**
     * 開始擷取：呼叫前請先在 Activity 的 onActivityResult 拿到 resultCode 與 data
     * 並傳入要輸出的檔案 File
     */
    public void startProjection(int resultCode, Intent data, File outFile) throws IOException {
        if (mediaProjection != null) return;
        this.outFile = outFile;
        mediaProjection = projectionManager.getMediaProjection(resultCode, data);
        if (mediaProjection == null) throw new IOException("MediaProjection is null");
        prepareAndStart();
    }

    /**
     * 停止擷取並釋放資源
     */
    public void stop() {
        capturing = false;
        if (captureThread != null) {
            try { captureThread.join(500); } catch (InterruptedException ignored) {}
            captureThread = null;
        }
        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        if (encoder != null) {
            try { encoder.stop(); encoder.release(); } catch (Exception ignored) {}
            encoder = null;
        }
        if (muxer != null) {
            try { if (muxerStarted) muxer.stop(); muxer.release(); } catch (Exception ignored) {}
            muxer = null;
        }
        if (mediaProjection != null) {
            try { mediaProjection.stop(); } catch (Exception ignored) {}
            mediaProjection = null;
        }
        muxerStarted = false;
        audioTrackIndex = -1;
    }

    private void prepareAndStart() throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            throw new IOException("AudioPlaybackCapture requires API 29+");
        }

        AudioPlaybackCaptureConfiguration config = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                .build();

        int minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat);
        int bufferSize = Math.max(minBuf, sampleRate * 2);

        audioRecord = new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(audioFormat)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelConfig)
                        .build())
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build();

        MediaFormat format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bufferSize);

        try {
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
        } catch (IOException e) {
            throw new IOException("Failed to create encoder: " + e.getMessage(), e);
        }

        muxer = new MediaMuxer(outFile.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

        capturing = true;
        captureThread = new Thread(this::captureLoop, "InternalAudioCapture");
        captureThread.start();
    }

    private void captureLoop() {
        try {
            audioRecord.startRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "audioRecord.startRecording failed", e);
            capturing = false;
            return;
        }

        byte[] readBuf = new byte[2048 * 4];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

        while (capturing) {
            int read = audioRecord.read(readBuf, 0, readBuf.length);
            if (read > 0) {
                int inputBufferIndex = encoder.dequeueInputBuffer(10000);
                if (inputBufferIndex >= 0) {
                    ByteBuffer inputBuf = encoder.getInputBuffer(inputBufferIndex);
                    if (inputBuf != null) {
                        inputBuf.clear();
                        inputBuf.put(readBuf, 0, read);
                        long pts = System.nanoTime() / 1000;
                        encoder.queueInputBuffer(inputBufferIndex, 0, read, pts, 0);
                    }
                }
            }

            int outputIndex = encoder.dequeueOutputBuffer(info, 0);
            while (outputIndex >= 0) {
                ByteBuffer encoded = encoder.getOutputBuffer(outputIndex);
                if (encoded != null && info.size > 0) {
                    encoded.position(info.offset);
                    encoded.limit(info.offset + info.size);
                    if (!muxerStarted) {
                        MediaFormat outFormat = encoder.getOutputFormat();
                        audioTrackIndex = muxer.addTrack(outFormat);
                        muxer.start();
                        muxerStarted = true;
                    }
                    muxer.writeSampleData(audioTrackIndex, encoded, info);
                }
                encoder.releaseOutputBuffer(outputIndex, false);
                outputIndex = encoder.dequeueOutputBuffer(info, 0);
            }
        }

        // flush encoder
        try {
            int inputBufferIndex = encoder.dequeueInputBuffer(10000);
            if (inputBufferIndex >= 0) {
                encoder.queueInputBuffer(inputBufferIndex, 0, 0, System.nanoTime() / 1000, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }
        } catch (Exception ignored) {}

        MediaCodec.BufferInfo finalInfo = new MediaCodec.BufferInfo();
        int outIndex = encoder.dequeueOutputBuffer(finalInfo, 10000);
        while (outIndex >= 0) {
            ByteBuffer encoded = encoder.getOutputBuffer(outIndex);
            if (encoded != null && finalInfo.size > 0) {
                encoded.position(finalInfo.offset);
                encoded.limit(finalInfo.offset + finalInfo.size);
                if (!muxerStarted) {
                    MediaFormat outFormat = encoder.getOutputFormat();
                    audioTrackIndex = muxer.addTrack(outFormat);
                    muxer.start();
                    muxerStarted = true;
                }
                muxer.writeSampleData(audioTrackIndex, encoded, finalInfo);
            }
            encoder.releaseOutputBuffer(outIndex, false);
            outIndex = encoder.dequeueOutputBuffer(finalInfo, 10000);
        }
    }
}
