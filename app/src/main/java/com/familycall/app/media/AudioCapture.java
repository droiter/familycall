package com.familycall.app.media;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.util.Log;

import com.familycall.app.net.CodecConfig;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** PCM capture -> AAC-LC (16 kHz mono, 32 kbps) with echo cancellation when available. */
public final class AudioCapture {
    private static final String TAG = "AudioCapture";
    public static final int SAMPLE_RATE = 16000;
    public static final int CHANNELS = 1;
    public static final int BITRATE = 32000;
    private static final int FRAME_SAMPLES = 320; // 20 ms

    public interface Listener {
        void onAudioConfig(byte[] csd0);

        void onAudioFrame(byte[] aac, long ptsUs);

        void onAudioError(String message);
    }

    private final Context context;
    private final Listener listener;
    private volatile boolean running;
    private volatile boolean muted;
    private Thread thread;
    private AudioRecord record;
    private AcousticEchoCanceler aec;
    private MediaCodec encoder;
    private long sampleIndex;
    private long configSentAt;

    public AudioCapture(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        if (running) return;
        try {
            if (android.os.Build.VERSION.SDK_INT >= 23
                    && context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                listener.onAudioError("需要麦克风权限才能通话");
                return;
            }
            int min = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (min <= 0) min = 4096;
            int bufferSize = Math.max(min * 2, FRAME_SAMPLES * 2 * 8);
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufferSize);
            if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                listener.onAudioError("麦克风初始化失败");
                releaseRecord();
                return;
            }
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(record.getAudioSessionId());
                    if (aec != null) aec.setEnabled(true);
                }
            } catch (Exception ignored) {
            }

            MediaFormat format = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, SAMPLE_RATE, CHANNELS);
            format.setInteger(MediaFormat.KEY_AAC_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AACObjectLC);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            encoder.start();
            running = true;
            sampleIndex = 0;
            record.startRecording();
            thread = new Thread(this::captureLoop, "audio-capture");
            thread.setDaemon(true);
            thread.start();
        } catch (Exception e) {
            Log.e(TAG, "start audio", e);
            listener.onAudioError("无法启动麦克风/音频编码");
            stop();
        }
    }

    private void captureLoop() {
        short[] samples = new short[FRAME_SAMPLES];
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastConfig = 0;
        while (running) {
            try {
                int read = record.read(samples, 0, samples.length);
                if (read <= 0) continue;
                if (muted) {
                    for (int i = 0; i < read; i++) samples[i] = 0;
                }
                int in = encoder.dequeueInputBuffer(10000);
                if (in >= 0) {
                    ByteBuffer buf = encoder.getInputBuffer(in);
                    if (buf != null) {
                        buf.clear();
                        int bytes = Math.min(buf.remaining(), read * 2);
                        for (int i = 0; i < bytes / 2; i++) buf.putShort(samples[i]);
                        long ptsUs = sampleIndex * 1_000_000L / SAMPLE_RATE;
                        encoder.queueInputBuffer(in, 0, bytes, ptsUs, 0);
                        sampleIndex += bytes / 2L;
                    }
                }
                while (true) {
                    int out = encoder.dequeueOutputBuffer(info, 0);
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        byte[] csd = readCsd(encoder.getOutputFormat());
                        if (csd != null) {
                            listener.onAudioConfig(csd);
                            lastConfig = System.currentTimeMillis();
                        }
                    } else if (out >= 0) {
                        if (info.size > 0) {
                            ByteBuffer ob = encoder.getOutputBuffer(out);
                            if (ob != null) {
                                ob.position(info.offset);
                                ob.limit(info.offset + info.size);
                                byte[] data = new byte[info.size];
                                ob.get(data);
                                listener.onAudioFrame(data, info.presentationTimeUs);
                            }
                        }
                        encoder.releaseOutputBuffer(out, false);
                    } else {
                        break;
                    }
                }
                long now = System.currentTimeMillis();
                if (now - lastConfig > 3000) {
                    // Re-send the AudioSpecificConfig periodically so a lost config packet
                    // cannot leave the receiver silent for the rest of the call.
                    byte[] csd = readCsd(encoder.getOutputFormat());
                    if (csd != null) {
                        listener.onAudioConfig(csd);
                        lastConfig = now;
                    }
                }
            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "capture loop", e);
                    listener.onAudioError("音频采集中断");
                }
                return;
            }
        }
    }

    private byte[] readCsd(MediaFormat format) {
        if (format == null) return null;
        try {
            ByteBuffer b = format.getByteBuffer("csd-0");
            return CodecConfig.fromBuffer(b);
        } catch (Exception e) {
            return null;
        }
    }

    public void setMuted(boolean muted) {
        this.muted = muted;
    }

    public void stop() {
        running = false;
        AudioRecord r = record;
        record = null;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception ignored) {
            }
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(400);
            } catch (InterruptedException ignored) {
            }
        }
        if (r != null) {
            try {
                r.release();
            } catch (Exception ignored) {
            }
        }
        if (aec != null) {
            try {
                aec.release();
            } catch (Exception ignored) {
            }
            aec = null;
        }
        try {
            if (encoder != null) {
                encoder.stop();
                encoder.release();
            }
        } catch (Exception ignored) {
        }
        encoder = null;
    }

    private void releaseRecord() {
        if (record != null) {
            try {
                record.release();
            } catch (Exception ignored) {
            }
            record = null;
        }
    }
}
