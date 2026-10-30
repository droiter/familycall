package com.familycall.app.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** H.264 decoder that renders to the remote TextureView's Surface. */
public final class VideoDecoder {
    private static final String TAG = "VideoDecoder";

    public interface Listener {
        void onDecoderSize(int width, int height);

        void onDecoderNeedsKeyframe();

        void onDecoderError(String message);
    }

    private final Listener listener;
    private final LinkedBlockingQueue<Frame> queue = new LinkedBlockingQueue<>();
    private volatile Surface surface;
    private volatile boolean running;
    private MediaCodec codec;
    private List<byte[]> csd;
    private int width = 640;
    private int height = 480;
    private Thread thread;
    private volatile boolean waitingForKeyframe = true;

    private static final class Frame {
        final byte[] data;
        final boolean key;
        final long ptsUs;

        Frame(byte[] data, boolean key, long ptsUs) {
            this.data = data;
            this.key = key;
            this.ptsUs = ptsUs;
        }
    }

    public VideoDecoder(Listener listener) {
        this.listener = listener;
    }

    public synchronized void setSurface(Surface s) {
        if (surface == s) return;
        surface = s;
        restartIfReady();
    }

    public synchronized void setConfig(List<byte[]> config, int w, int h) {
        if (config == null || config.isEmpty()) return;
        if (csd != null && sameCsd(csd, config) && w == width && h == height) {
            return;
        }
        csd = config;
        if (w > 0) width = w;
        if (h > 0) height = h;
        restartIfReady();
    }

    private static boolean sameCsd(List<byte[]> a, List<byte[]> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!java.util.Arrays.equals(a.get(i), b.get(i))) return false;
        }
        return true;
    }

    public void submit(byte[] data, boolean keyFrame, long ptsUs) {
        if (data == null || data.length == 0) return;
        if (waitingForKeyframe && !keyFrame) {
            listener.onDecoderNeedsKeyframe();
            return;
        }
        if (waitingForKeyframe && keyFrame) waitingForKeyframe = false;
        if (queue.size() > 6) {
            queue.clear();
            waitingForKeyframe = true;
            listener.onDecoderNeedsKeyframe();
            return;
        }
        queue.offer(new Frame(data, keyFrame, ptsUs));
    }

    public void requestKeyframe() {
        waitingForKeyframe = true;
    }

    private synchronized void restartIfReady() {
        if (surface == null || csd == null || csd.isEmpty()) return;
        stopInternal();
        try {
            codec = MediaCodec.createDecoderByType(VideoEncoder.MIME);
            MediaFormat format = MediaFormat.createVideoFormat(VideoEncoder.MIME, width, height);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd.get(0)));
            if (csd.size() > 1) format.setByteBuffer("csd-1", ByteBuffer.wrap(csd.get(1)));
            codec.configure(format, surface, null, 0);
            codec.start();
            running = true;
            waitingForKeyframe = true;
            queue.clear();
            thread = new Thread(this::decodeLoop, "video-decoder");
            thread.setDaemon(true);
            thread.start();
            listener.onDecoderSize(width, height);
            Log.i(TAG, "decoder started " + width + "x" + height);
        } catch (Exception e) {
            Log.e(TAG, "cannot start decoder", e);
            listener.onDecoderError("无法启动视频解码器");
            running = false;
        }
    }

    private void decodeLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        Frame pending = null;
        while (running) {
            try {
                if (pending == null) pending = queue.poll(10, TimeUnit.MILLISECONDS);
                if (pending != null) {
                    int in = codec.dequeueInputBuffer(0);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        if (buffer != null) {
                            buffer.clear();
                            int n = Math.min(buffer.remaining(), pending.data.length);
                            buffer.put(pending.data, 0, n);
                            codec.queueInputBuffer(in, 0, n,
                                    Math.max(0, pending.ptsUs), 0);
                        }
                        pending = null;
                    }
                }
                int out = codec.dequeueOutputBuffer(info, 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat fmt = codec.getOutputFormat();
                    int w = fmt.getInteger(MediaFormat.KEY_WIDTH);
                    int h = fmt.getInteger(MediaFormat.KEY_HEIGHT);
                    if (w > 0 && h > 0) listener.onDecoderSize(w, h);
                } else if (out >= 0) {
                    codec.releaseOutputBuffer(out, info.size > 0);
                }
            } catch (IllegalStateException e) {
                if (running) Log.e(TAG, "decode loop", e);
                return;
            } catch (Exception e) {
                if (running) {
                    Log.e(TAG, "decode loop", e);
                    listener.onDecoderError("视频解码异常");
                }
                return;
            }
        }
    }

    public synchronized void reset() {
        waitingForKeyframe = true;
        queue.clear();
        stopInternal();
        restartIfReady();
    }

    private void stopInternal() {
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(300);
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
        queue.clear();
    }

    public synchronized void release() {
        stopInternal();
        surface = null;
        csd = null;
    }
}
