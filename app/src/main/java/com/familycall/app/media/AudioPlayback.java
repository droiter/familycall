package com.familycall.app.media;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.familycall.app.net.CodecConfig;

import java.nio.ByteBuffer;
import java.util.Map;
import java.util.TreeMap;

/** AAC-LC receiver with a small adaptive jitter buffer feeding an AudioTrack. */
public final class AudioPlayback {
    private static final String TAG = "AudioPlayback";

    public interface Listener {
        void onAudioStats(int kbps, int lossPercent);

        void onAudioError(String message);
    }

    private final Listener listener;
    private final int sampleRate;
    private final AudioTrack track;
    private volatile MediaCodec decoder;
    private Thread playThread;
    private volatile boolean running;
    private byte[] config;
    private final TreeMap<Integer, byte[]> jitter = new TreeMap<>();
    private int expectedSeq = -1;
    private long firstFrameAt;
    private long lastPlayAt;
    private long bytesWindow;
    private long windowStart;
    private int received;
    private int lost;

    public AudioPlayback(int sampleRate, Listener listener) {
        this.sampleRate = sampleRate;
        this.listener = listener;
        int min = AudioTrack.getMinBufferSize(sampleRate,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) min = 4096;
        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        AudioFormat fmt = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build();
        track = new AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(Math.max(min * 2, 3200))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();
    }

    public void start() {
        if (running) return;
        running = true;
        try {
            track.play();
        } catch (Exception e) {
            listener.onAudioError("扬声器不可用");
        }
        windowStart = System.currentTimeMillis();
        playThread = new Thread(this::playLoop, "audio-playback");
        playThread.setDaemon(true);
        playThread.start();
    }

    public synchronized void onConfig(byte[] csd0) {
        if (csd0 == null || csd0.length == 0) return;
        if (config != null && java.util.Arrays.equals(config, csd0) && decoder != null) {
            return;
        }
        this.config = csd0;
        restartDecoder();
    }

    public synchronized void onFrame(int seq, byte[] aac) {
        if (aac == null || aac.length == 0) return;
        received++;
        bytesWindow += aac.length;
        if (expectedSeq < 0) {
            expectedSeq = seq;
            firstFrameAt = System.currentTimeMillis();
        }
        int diff = seq - expectedSeq;
        if (diff < -300 || diff > 300) {
            // Stream restarted or wrapped unexpectedly.
            expectedSeq = seq;
        }
        if (seq - expectedSeq < 0) return; // too late, already played past it
        if (!jitter.containsKey(seq)) jitter.put(seq, aac);
        while (jitter.size() > 50) {
            Integer k = jitter.firstKey();
            jitter.remove(k);
            lost++;
            expectedSeq = k + 1;
        }
    }

    private void playLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        long lastReport = 0;
        while (running) {
            byte[] frame = null;
            boolean hasData = false;
            synchronized (this) {
                if (expectedSeq >= 0) {
                    long now = System.currentTimeMillis();
                    boolean ready = jitter.size() >= 2 || now - firstFrameAt >= 50;
                    if (ready) {
                        frame = jitter.remove(expectedSeq);
                        if (frame == null) {
                            Integer next = jitter.ceilingKey(expectedSeq);
                            if (next != null && next - expectedSeq <= 30
                                    && now - lastPlayAt > 40) {
                                lost += Math.max(0, next - expectedSeq);
                                expectedSeq = next;
                                frame = jitter.remove(expectedSeq);
                            }
                        }
                        hasData = true;
                    }
                }
            }
            if (frame != null) {
                try {
                    decode(frame, info);
                    synchronized (this) {
                        expectedSeq++;
                        lastPlayAt = System.currentTimeMillis();
                    }
                } catch (Exception e) {
                    if (running) {
                        Log.e(TAG, "play loop", e);
                        listener.onAudioError("音频播放异常");
                    }
                    return;
                }
            } else if (!hasData) {
                sleep(5);
            }
            synchronized (this) {
                long now = System.currentTimeMillis();
                if (now - lastReport >= 1000) {
                    int total = received + lost;
                    int loss = total == 0 ? 0 : (int) (lost * 100L / total);
                    int kbps = (int) (bytesWindow * 8 / Math.max(1, now - windowStart));
                    listener.onAudioStats(kbps, loss);
                    bytesWindow = 0;
                    windowStart = now;
                    received = 0;
                    lost = 0;
                    lastReport = now;
                }
            }
            if (frame == null) sleep(3);
        }
    }

    private void decode(byte[] frame, MediaCodec.BufferInfo info) throws Exception {
        MediaCodec dec = decoder;
        if (dec == null) return;
        int in = dec.dequeueInputBuffer(10000);
        if (in < 0) return;
        ByteBuffer buf = dec.getInputBuffer(in);
        if (buf == null) return;
        buf.clear();
        buf.put(frame);
        dec.queueInputBuffer(in, 0, frame.length, 0, 0);
        while (true) {
            int out = dec.dequeueOutputBuffer(info, 0);
            if (out >= 0) {
                ByteBuffer pcm = dec.getOutputBuffer(out);
                if (pcm != null && info.size > 0) {
                    pcm.position(info.offset);
                    pcm.limit(info.offset + info.size);
                    byte[] data = new byte[info.size];
                    pcm.get(data);
                    try {
                        track.write(data, 0, data.length);
                    } catch (Exception ignored) {
                    }
                }
                dec.releaseOutputBuffer(out, false);
            } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                // The encoder is fixed at 16 kHz mono; no reconfiguration needed.
            } else {
                break;
            }
        }
    }

    private synchronized void restartDecoder() {
        try {
            if (decoder != null) {
                try {
                    decoder.stop();
                    decoder.release();
                } catch (Exception ignored) {
                }
                decoder = null;
            }
            if (config == null) return;
            MediaFormat fmt = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1);
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(config));
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC);
            decoder.configure(fmt, null, null, 0);
            decoder.start();
            jitter.clear();
            expectedSeq = -1;
        } catch (Exception e) {
            Log.e(TAG, "decoder init", e);
            listener.onAudioError("音频解码器初始化失败");
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    public void stop() {
        running = false;
        Thread t = playThread;
        playThread = null;
        if (t != null) {
            try {
                t.join(400);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            track.stop();
            track.release();
        } catch (Exception ignored) {
        }
        synchronized (this) {
            try {
                if (decoder != null) {
                    decoder.stop();
                    decoder.release();
                }
            } catch (Exception ignored) {
            }
            decoder = null;
            jitter.clear();
            config = null;
        }
    }
}
