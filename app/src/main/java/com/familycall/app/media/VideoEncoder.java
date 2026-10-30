package com.familycall.app.media;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;

import com.familycall.app.net.CodecConfig;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** H.264 hardware encoder fed by a Camera2 output surface. */
public final class VideoEncoder {
    private static final String TAG = "VideoEncoder";
    public static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;

    public interface Listener {
        void onEncoderSurface(Surface surface, int width, int height);

        void onEncoderConfig(List<byte[]> csd);

        void onEncodedFrame(byte[] annexB, boolean keyFrame, long ptsUs);

        void onEncoderInfo(int width, int height, int fps);

        void onEncoderError(String message);
    }

    private final int width;
    private final int height;
    private final int fps;
    private final int initialBitrate;
    private final Listener listener;
    private MediaCodec codec;
    private Surface inputSurface;
    private Thread outputThread;
    private volatile boolean running;
    private volatile int bitrate;
    private volatile long lastKeyframeRequestMs;
    private final List<byte[]> csd = new ArrayList<>();
    private long encodedBytesWindow;
    private long encodedWindowStart;
    private int framesWindow;
    private volatile float encodedFps;
    private volatile int encodedKbps;

    public VideoEncoder(int width, int height, int fps, int bitrate, Listener listener) {
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.initialBitrate = bitrate;
        this.bitrate = bitrate;
        this.listener = listener;
    }

    public void start() {
        try {
            codec = MediaCodec.createEncoderByType(MIME);
            MediaFormat format = MediaFormat.createVideoFormat(MIME, width, height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            format.setInteger(MediaFormat.KEY_PROFILE,
                    MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
            format.setInteger(MediaFormat.KEY_LEVEL,
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            try {
                format.setInteger(MediaFormat.KEY_BITRATE_MODE,
                        MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            } catch (Exception ignored) {
            }
            try {
                format.setInteger("operating-rate", fps);
                format.setInteger("priority", 0);
            } catch (Exception ignored) {
            }
            try {
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            } catch (Exception first) {
                // Some encoders reject an explicit profile/level; retry with a minimal format.
                if (android.os.Build.VERSION.SDK_INT >= 29) {
                    format.removeKey(MediaFormat.KEY_PROFILE);
                    format.removeKey(MediaFormat.KEY_LEVEL);
                } else {
                    format = MediaFormat.createVideoFormat(MIME, width, height);
                    format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                    format.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
                    format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
                    format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
                }
                codec.release();
                codec = MediaCodec.createEncoderByType(MIME);
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            }
            inputSurface = codec.createInputSurface();
            codec.start();
            running = true;
            encodedWindowStart = System.currentTimeMillis();
            listener.onEncoderSurface(inputSurface, width, height);
            listener.onEncoderInfo(width, height, fps);
            outputThread = new Thread(this::outputLoop, "video-encoder");
            outputThread.setDaemon(true);
            outputThread.start();
        } catch (Exception e) {
            Log.e(TAG, "Cannot start H.264 encoder", e);
            listener.onEncoderError("设备无法启动视频编码器（H.264）");
        }
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    private void outputLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        boolean configDone = false;
        while (running) {
            try {
                int index = codec.dequeueOutputBuffer(info, 10000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat fmt = codec.getOutputFormat();
                    readCsd(fmt);
                    if (!csd.isEmpty()) {
                        listener.onEncoderConfig(new ArrayList<>(csd));
                        configDone = true;
                    }
                } else if (index >= 0) {
                    if (info.size > 0) {
                        ByteBuffer buffer = codec.getOutputBuffer(index);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            byte[] data = new byte[info.size];
                            buffer.get(data);
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            if (!configDone && !csd.isEmpty()) {
                                listener.onEncoderConfig(new ArrayList<>(csd));
                                configDone = true;
                            }
                            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                                byte[] annexB = H264.toAnnexB(data);
                                listener.onEncodedFrame(annexB, key, info.presentationTimeUs);
                                countEncoded(annexB.length);
                            }
                        }
                    }
                    codec.releaseOutputBuffer(index, false);
                }
            } catch (IllegalStateException e) {
                if (running) Log.e(TAG, "encoder loop", e);
                return;
            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "encoder loop", e);
                    listener.onEncoderError("视频编码异常");
                }
                return;
            }
        }
    }

    private void readCsd(MediaFormat fmt) {
        if (fmt == null || !csd.isEmpty()) return;
        for (String key : new String[]{"csd-0", "csd-1"}) {
            try {
                ByteBuffer b = fmt.getByteBuffer(key);
                if (b != null) {
                    byte[] bytes = CodecConfig.fromBuffer(b);
                    if (bytes != null && bytes.length > 0) csd.add(bytes);
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void countEncoded(int bytes) {
        synchronized (this) {
            encodedBytesWindow += bytes;
            framesWindow++;
            long now = System.currentTimeMillis();
            long elapsed = now - encodedWindowStart;
            if (elapsed >= 1000) {
                encodedKbps = (int) (encodedBytesWindow * 8 / elapsed);
                encodedFps = framesWindow * 1000f / elapsed;
                encodedBytesWindow = 0;
                framesWindow = 0;
                encodedWindowStart = now;
            }
        }
    }

    public void setBitrate(int kbps) {
        int bps = Math.max(120, kbps) * 1000;
        if (Math.abs(bps - bitrate) < 20000) return;
        bitrate = bps;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bps);
            codec.setParameters(b);
        } catch (Exception e) {
            Log.w(TAG, "setBitrate failed", e);
        }
    }

    public void requestKeyFrame() {
        long now = System.currentTimeMillis();
        if (now - lastKeyframeRequestMs < 500) return;
        lastKeyframeRequestMs = now;
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (Exception e) {
            Log.w(TAG, "keyframe failed", e);
        }
    }

    public float getEncodedFps() {
        return encodedFps;
    }

    public int getEncodedKbps() {
        return encodedKbps;
    }

    public void stop() {
        running = false;
        Thread t = outputThread;
        if (t != null) {
            try {
                t.join(400);
            } catch (InterruptedException ignored) {
            }
        }
        try {
            if (codec != null) {
                codec.stop();
                codec.release();
            }
        } catch (Exception ignored) {
        }
        codec = null;
        if (inputSurface != null) {
            try {
                inputSurface.release();
            } catch (Exception ignored) {
            }
            inputSurface = null;
        }
    }
}
