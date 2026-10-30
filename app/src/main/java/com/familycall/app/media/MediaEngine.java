package com.familycall.app.media;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;
import android.view.Surface;

import com.familycall.app.data.CallStats;
import com.familycall.app.net.AdaptiveBitrate;
import com.familycall.app.net.CodecConfig;
import com.familycall.app.net.MediaPacket;
import com.familycall.app.net.Protocol;
import com.familycall.app.net.UdpSocket;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * UDP media engine. It owns video/audio capture, the send/receive paths, a
 * loss-driven bitrate controller, frame reassembly and a jitter buffer.
 */
public final class MediaEngine {
    private static final String TAG = "MediaEngine";

    public interface Listener {
        void onStats(CallStats stats);

        void onPeerVideoInfo(int rotation, boolean mirror);

        void onPeerVideoSize(int width, int height);

        void onMediaError(String message, boolean fatal);

        void onFirstMediaReceived();

        void onPeerBye();
    }

    private final Context context;
    private final int localPort;
    private final int width;
    private final int height;
    private final int fps;
    private final int startKbps;
    private final int maxKbps;
    private final int streamId;
    private final Listener listener;
    private final UdpSocket udp;
    private final AdaptiveBitrate abr;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object sendLock = new Object();
    private final Object queueLock = new Object();
    private final ArrayDeque<VideoFrame> videoQueue = new ArrayDeque<>();
    private final AtomicInteger videoSeq = new AtomicInteger(0);
    private final AtomicInteger videoFrameSeq = new AtomicInteger(0);
    private final AtomicInteger audioSeq = new AtomicInteger(0);
    private final AtomicBoolean videoNeedsKeyframe = new AtomicBoolean(false);
    private final Map<Integer, Assembly> assemblies = new HashMap<>();
    private final AudioManager audioManager;
    private final ConnectivityManager connectivityManager;
    private PowerManager.WakeLock wakeLock;

    private volatile boolean running;
    private boolean videoStarted;
    private SurfaceTexture previewTexture;
    private Surface remoteSurface;
    private volatile InetSocketAddress peer;
    private volatile int streamRotation;
    private volatile boolean streamMirror;
    private volatile int rttMs;
    private volatile int audioRxKbps;
    private volatile int audioLossPercent;
    private volatile long lastMediaAtMs;
    private long firstMediaNotifiedMs;
    private volatile byte[] videoConfig;
    private volatile byte[] audioConfig;
    private long lastAudioConfigSentMs;
    private long lastVideoConfigSentMs;
    private long videoStatsWindowStart;
    private long videoBytesWindow;
    private int videoPacketsWindow;
    private int videoLostWindow;
    private int videoFramesWindow;
    private volatile int lastVideoSeq = -1;
    private volatile int videoLossPercent;
    private volatile float videoFps;
    private volatile int videoRxKbps;
    private long nextSendNanos;
    private boolean speakerOn = true;
    private long videoDeadlineMs;
    private long lastStallNotifyMs;

    private Thread receiverThread;
    private Thread feedbackThread;
    private Thread videoSendThread;
    private volatile VideoEncoder encoder;
    private volatile VideoDecoder decoder;
    private CameraController camera;
    private AudioCapture capture;
    private AudioPlayback playback;

    private static final class VideoFrame {
        final byte[] data;
        final boolean key;
        final long ptsUs;

        VideoFrame(byte[] data, boolean key, long ptsUs) {
            this.data = data;
            this.key = key;
            this.ptsUs = ptsUs;
        }
    }

    private static final class Assembly {
        final TreeMap<Integer, byte[]> fragments = new TreeMap<>();
        int fragCount;
        boolean started;
        boolean ended;
        boolean key;
        long updatedAt;

        boolean complete() {
            return started && ended && fragCount > 0 && fragments.size() == fragCount;
        }
    }

    public MediaEngine(Context context, int localPort, InetSocketAddress peer,
                       String streamIdCall, int streamId, int width, int height, int fps,
                       int startKbps, int maxKbps, boolean speakerOn, Listener listener) {
        this.context = context.getApplicationContext();
        this.localPort = localPort;
        this.peer = peer;
        this.streamId = streamId;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.startKbps = startKbps;
        this.maxKbps = maxKbps;
        this.abr = new AdaptiveBitrate(startKbps, Math.min(220, startKbps), maxKbps);
        this.speakerOn = speakerOn;
        this.listener = listener;
        this.udp = new UdpSocket(localPort);
        this.audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        this.connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public boolean start() {
        if (running) return true;
        try {
            udp.setPeer(peer);
            udp.open();
        } catch (Exception e) {
            Log.e(TAG, "udp bind", e);
            listener.onMediaError("无法绑定媒体端口 " + localPort + "，可能已被占用", true);
            return false;
        }
        running = true;
        lastMediaAtMs = System.currentTimeMillis();
        videoDeadlineMs = System.currentTimeMillis() + 900;
        acquireWakeLock();
        configureAudioMode();
        startReceiver();
        startVideoSender();
        startFeedbackLoop();
        startAudio();
        if (previewTexture != null) {
            main.post(() -> maybeStartVideo(false));
        } else {
            main.postDelayed(() -> maybeStartVideo(false), 900);
        }
        return true;
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FamilyCall:media");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {
        }
    }

    private void configureAudioMode() {
        try {
            audioManager.setMode(AudioManager.MODE_IN_COMMUNICATION);
            applySpeaker(speakerOn);
        } catch (Exception e) {
            Log.w(TAG, "audio mode", e);
        }
    }

    private void applySpeaker(boolean on) {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                AudioDeviceInfo[] devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                AudioDeviceInfo target = null;
                for (AudioDeviceInfo d : devices) {
                    if (on && d.getType() == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) target = d;
                    if (!on && d.getType() == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) target = d;
                }
                if (target != null) audioManager.setCommunicationDevice(target);
            } else {
                audioManager.setSpeakerphoneOn(on);
            }
        } catch (Exception e) {
            try {
                audioManager.setSpeakerphoneOn(on);
            } catch (Exception ignored) {
            }
        }
    }

    private void startAudio() {
        try {
            playback = new AudioPlayback(AudioCapture.SAMPLE_RATE, new AudioPlayback.Listener() {
                @Override
                public void onAudioStats(int kbps, int lossPercent) {
                    audioRxKbps = kbps;
                    audioLossPercent = lossPercent;
                }

                @Override
                public void onAudioError(String message) {
                    listener.onMediaError(message, false);
                }
            });
            playback.start();
            capture = new AudioCapture(context, new AudioCapture.Listener() {
                @Override
                public void onAudioConfig(byte[] csd0) {
                    audioConfig = csd0;
                }

                @Override
                public void onAudioFrame(byte[] aac, long ptsUs) {
                    sendAudioFrame(aac, ptsUs);
                }

                @Override
                public void onAudioError(String message) {
                    listener.onMediaError(message, false);
                }
            });
            capture.start();
        } catch (Exception e) {
            Log.e(TAG, "start audio", e);
            listener.onMediaError("音频启动失败", false);
        }
    }

    private void startReceiver() {
        receiverThread = new Thread(() -> {
            byte[] buffer = new byte[Protocol.MAX_DATAGRAM];
            while (running) {
                try {
                    DatagramPacket dp = udp.receive(buffer);
                    long nowMs = System.currentTimeMillis();
                    MediaPacket p = MediaPacket.parse(dp.getData(), dp.getLength());
                    if (p == null || p.streamId != streamId) continue;
                    if (p.type == Protocol.TYPE_VIDEO || p.type == Protocol.TYPE_AUDIO) {
                        lastMediaAtMs = nowMs;
                        if (firstMediaNotifiedMs == 0) {
                            firstMediaNotifiedMs = nowMs;
                            main.post(listener::onFirstMediaReceived);
                        }
                    }
                    InetSocketAddress src = new InetSocketAddress(dp.getAddress(), dp.getPort());
                    if (peer == null || !src.equals(peer)) {
                        // Accept a moved peer (NAT rebinding / network change).
                        peer = src;
                        udp.setPeer(src);
                    }
                    handlePacket(p);
                } catch (SocketTimeoutException ignored) {
                } catch (IOException e) {
                    if (!running) break;
                    try {
                        Thread.sleep(80);
                    } catch (InterruptedException ignored) {
                    }
                } catch (Exception e) {
                    if (running) Log.w(TAG, "receive", e);
                }
            }
        }, "media-receiver");
        receiverThread.setDaemon(true);
        receiverThread.start();
    }

    private void handlePacket(MediaPacket p) {
        switch (p.type) {
            case Protocol.TYPE_VIDEO:
                handleVideoPacket(p);
                break;
            case Protocol.TYPE_AUDIO:
                handleAudioPacket(p);
                break;
            case Protocol.TYPE_FEEDBACK:
                handleFeedback(MediaPacket.parseFeedback(p));
                break;
            case Protocol.TYPE_PROBE:
                sendFeedback(true);
                break;
            case Protocol.TYPE_BYE:
                main.post(listener::onPeerBye);
                break;
            default:
                break;
        }
    }

    private void handleVideoPacket(MediaPacket p) {
        videoPacketsWindow++;
        videoBytesWindow += p.payloadLength + Protocol.HEADER_SIZE;
        if (lastVideoSeq >= 0) {
            int diff = p.seq - lastVideoSeq;
            if (diff > 1) {
                videoLostWindow += diff - 1;
                videoNeedsKeyframe.set(true);
            }
            if (diff > 0) lastVideoSeq = p.seq;
        } else {
            lastVideoSeq = p.seq;
        }
        if ((p.flags & Protocol.FLAG_CONFIG) != 0) {
            byte[] payload = p.payload;
            int w = width;
            int h = height;
            int off = 0;
            if (payload != null && payload.length >= 8) {
                java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(payload);
                int rw = bb.getInt();
                int rh = bb.getInt();
                if (rw > 0 && rh > 0 && rw <= 4096 && rh <= 4096) {
                    w = rw;
                    h = rh;
                }
                off = 8;
            }
            byte[] csdRaw = new byte[payload == null ? 0 : Math.max(0, payload.length - off)];
            if (payload != null && off < payload.length) {
                System.arraycopy(payload, off, csdRaw, 0, csdRaw.length);
            }
            List<byte[]> parts = CodecConfig.unpack(csdRaw);
            if (!parts.isEmpty()) {
                decoderSetConfig(parts, w, h);
            }
            return;
        }
        if (p.fragCount <= 0 || p.fragIndex >= p.fragCount) {
            videoNeedsKeyframe.set(true);
            return;
        }
        Assembly a;
        synchronized (assemblies) {
            a = assemblies.get(p.frameSeq);
            if (a == null) {
                a = new Assembly();
                assemblies.put(p.frameSeq, a);
            }
        }
        a.fragCount = Math.max(a.fragCount, p.fragCount);
        if ((p.flags & Protocol.FLAG_START) != 0) a.started = true;
        if ((p.flags & Protocol.FLAG_END) != 0) a.ended = true;
        if ((p.flags & Protocol.FLAG_KEY) != 0) a.key = true;
        a.fragments.put(p.fragIndex, p.payload);
        a.updatedAt = System.currentTimeMillis();
        if (a.complete()) {
            synchronized (assemblies) {
                assemblies.remove(p.frameSeq);
            }
            byte[] frame = concat(a);
            if (frame != null) {
                if (decoder != null) {
                    decoder.submit(frame, a.key, p.timestamp * 1000L / 90L);
                    videoFramesWindow++;
                } else {
                    videoNeedsKeyframe.set(true);
                }
            }
        }
    }

    private byte[] concat(Assembly a) {
        int total = 0;
        for (byte[] b : a.fragments.values()) total += b.length;
        if (total <= 0 || total > 4 * 1024 * 1024) return null;
        byte[] out = new byte[total];
        int off = 0;
        for (byte[] b : a.fragments.values()) {
            System.arraycopy(b, 0, out, off, b.length);
            off += b.length;
        }
        return out;
    }

    private synchronized void decoderSetConfig(List<byte[]> parts, int w, int h) {
        if (decoder == null) {
            decoder = new VideoDecoder(new VideoDecoder.Listener() {
                @Override
                public void onDecoderSize(int vw, int vh) {
                    listener.onPeerVideoSize(vw, vh);
                }

                @Override
                public void onDecoderNeedsKeyframe() {
                    videoNeedsKeyframe.set(true);
                }

                @Override
                public void onDecoderError(String message) {
                    listener.onMediaError(message, false);
                }
            });
            if (remoteSurface != null) decoder.setSurface(remoteSurface);
        }
        decoder.setConfig(parts, w, h);
        listener.onPeerVideoSize(w, h);
        videoNeedsKeyframe.set(true);
    }

    private void handleAudioPacket(MediaPacket p) {
        if ((p.flags & Protocol.FLAG_CONFIG) != 0) {
            List<byte[]> parts = CodecConfig.unpack(p.payload);
            if (!parts.isEmpty() && playback != null) {
                playback.onConfig(parts.get(0));
            }
            return;
        }
        if (playback != null) playback.onFrame(p.seq, p.payload);
    }

    private void handleFeedback(MediaPacket.Feedback f) {
        if (f == null) return;
        int total = f.videoReceived + f.videoLost;
        float loss = total <= 0 ? 0 : f.videoLost * 100f / total;
        long now = System.currentTimeMillis();
        if (abr.update(loss, f.rxVideoKbps, now) && encoder != null) {
            encoder.setBitrate(abr.getTargetKbps());
        }
        if ((f.flags & Protocol.FEEDBACK_KEYFRAME) != 0 && encoder != null) {
            encoder.requestKeyFrame();
        }
        if (f.jitterMs > 300) {
            int reduced = Math.max(220, abr.getTargetKbps() * 85 / 100);
            abr.force(reduced);
            if (encoder != null) encoder.setBitrate(reduced);
        }
    }

    private void maybeStartVideo(boolean force) {
        if (!running || videoStarted) {
            if (videoStarted && previewTexture != null && camera != null) {
                camera.updatePreview(previewTexture);
            }
            return;
        }
        if (!force && previewTexture == null && System.currentTimeMillis() < videoDeadlineMs) {
            main.postDelayed(() -> maybeStartVideo(false), 200);
            return;
        }
        videoStarted = true;
        try {
            encoder = new VideoEncoder(width, height, fps, abr.getTargetKbps(), new VideoEncoder.Listener() {
                @Override
                public void onEncoderSurface(Surface surface, int w, int h) {
                    main.post(() -> startCamera(surface, previewTexture));
                }

                @Override
                public void onEncoderConfig(List<byte[]> csd) {
                    try {
                        byte[] packed = CodecConfig.pack(csd);
                        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(8 + packed.length);
                        bb.putInt(width);
                        bb.putInt(height);
                        bb.put(packed);
                        videoConfig = bb.array();
                        if (encoder != null) encoder.requestKeyFrame();
                    } catch (Exception ignored) {
                    }
                }

                @Override
                public void onEncodedFrame(byte[] annexB, boolean keyFrame, long ptsUs) {
                    enqueueVideo(annexB, keyFrame, ptsUs);
                }

                @Override
                public void onEncoderInfo(int w, int h, int frameRate) {
                }

                @Override
                public void onEncoderError(String message) {
                    listener.onMediaError(message, false);
                }
            });
            encoder.start();
        } catch (Exception e) {
            Log.e(TAG, "start video", e);
            listener.onMediaError("视频启动失败", false);
        }
    }

    private void startCamera(Surface surface, SurfaceTexture preview) {
        try {
            camera = new CameraController(context, width, height, new CameraController.Listener() {
                @Override
                public void onCameraReady(int sensorOrientation, boolean front, int rotation, boolean mirror) {
                    streamRotation = ((rotation % 360) + 360) % 360;
                    streamMirror = mirror;
                    listener.onPeerVideoInfo(streamRotation, streamMirror);
                }

                @Override
                public void onCameraError(String message) {
                    listener.onMediaError(message, false);
                }
            });
            camera.start(surface, preview, true);
        } catch (Exception e) {
            Log.e(TAG, "camera start", e);
            listener.onMediaError("摄像头启动失败", false);
        }
    }

    private void enqueueVideo(byte[] data, boolean key, long ptsUs) {
        if (data == null || data.length == 0) return;
        synchronized (queueLock) {
            while (videoQueue.size() >= 3) {
                VideoFrame removed = videoQueue.pollFirst();
                if (removed == null) break;
                // Dropping a delta frame breaks the reference chain; ask the encoder
                // for a fresh IDR so the receiver can recover quickly.
                videoNeedsKeyframe.set(true);
                if (encoder != null) encoder.requestKeyFrame();
            }
            videoQueue.offerLast(new VideoFrame(data, key, ptsUs));
            queueLock.notifyAll();
        }
    }

    private void startVideoSender() {
        videoSendThread = new Thread(() -> {
            while (running) {
                VideoFrame frame;
                synchronized (queueLock) {
                    if (videoQueue.isEmpty()) {
                        try {
                            queueLock.wait(100);
                        } catch (InterruptedException ignored) {
                        }
                        continue;
                    }
                    frame = videoQueue.pollFirst();
                }
                if (frame != null) sendVideo(frame);
            }
        }, "video-sender");
        videoSendThread.setDaemon(true);
        videoSendThread.start();
    }

    private void sendVideo(VideoFrame frame) {
        if (!running) return;
        long now = System.currentTimeMillis();
        if (frame.key && videoConfig != null && now - lastVideoConfigSentMs > 1000) {
            sendVideoConfig();
        }
        int max = Protocol.MAX_PAYLOAD;
        int count = (frame.data.length + max - 1) / max;
        if (count <= 0 || count > 65535) return;
        int frameSeq = videoFrameSeq.incrementAndGet();
        int ts = Protocol.mediaTimestampUsToInt(frame.ptsUs, 90000);
        for (int i = 0; i < count; i++) {
            if (!running) return;
            int off = i * max;
            int len = Math.min(max, frame.data.length - off);
            byte flags = 0;
            if (i == 0) flags |= Protocol.FLAG_START;
            if (i == count - 1) flags |= Protocol.FLAG_END;
            if (frame.key) flags |= Protocol.FLAG_KEY;
            byte[] packet = MediaPacket.build(Protocol.TYPE_VIDEO, flags, streamId,
                    videoSeq.incrementAndGet(), frameSeq, ts, i, count,
                    frame.data, off, len);
            sendPaced(packet);
        }
    }

    private void sendVideoConfig() {
        byte[] cfg = videoConfig;
        if (cfg == null) return;
        lastVideoConfigSentMs = System.currentTimeMillis();
        int ts = Protocols.nowTs90k();
        byte[] packet = MediaPacket.build(Protocol.TYPE_VIDEO,
                (byte) (Protocol.FLAG_CONFIG | Protocol.FLAG_START | Protocol.FLAG_END | Protocol.FLAG_KEY),
                streamId, videoSeq.incrementAndGet(), videoFrameSeq.incrementAndGet(),
                ts, 0, 1, cfg, 0, cfg.length);
        sendPaced(packet);
    }

    private void sendPaced(byte[] packet) {
        int rate = Math.max(120000, abr.getTargetKbps() * 1000);
        long packetNs = packet.length * 8L * 1_000_000_000L / rate;
        long now = System.nanoTime();
        if (nextSendNanos < now || nextSendNanos - now > 250_000_000L) nextSendNanos = now;
        long wait = nextSendNanos - now;
        if (wait > 0) {
            LockSupport.parkNanos(wait);
        }
        nextSendNanos = Math.max(nextSendNanos, System.nanoTime()) + packetNs;
        synchronized (sendLock) {
            udp.send(packet, packet.length);
        }
    }

    private void sendAudioFrame(byte[] aac, long ptsUs) {
        if (!running || aac == null) return;
        long now = System.currentTimeMillis();
        if (audioConfig != null && now - lastAudioConfigSentMs > 3000) {
            sendAudioConfig();
        }
        int ts = Protocol.mediaTimestampUsToInt(ptsUs, AudioCapture.SAMPLE_RATE);
        byte[] packet = MediaPacket.build(Protocol.TYPE_AUDIO,
                (byte) (Protocol.FLAG_START | Protocol.FLAG_END), streamId,
                audioSeq.incrementAndGet(), audioSeq.get(), ts, 0, 1, aac, 0, aac.length);
        synchronized (sendLock) {
            udp.send(packet, packet.length);
        }
    }

    private void sendAudioConfig() {
        byte[] cfg = audioConfig;
        if (cfg == null) return;
        lastAudioConfigSentMs = System.currentTimeMillis();
        byte[] payload = CodecConfig.pack(java.util.Collections.singletonList(cfg));
        byte[] packet = MediaPacket.build(Protocol.TYPE_AUDIO,
                (byte) (Protocol.FLAG_CONFIG | Protocol.FLAG_START | Protocol.FLAG_END),
                streamId, audioSeq.incrementAndGet(), audioSeq.get(), 0, 0, 1,
                payload, 0, payload.length);
        synchronized (sendLock) {
            udp.send(packet, packet.length);
        }
    }

    private void sendBye() {
        try {
            byte[] p = MediaPacket.build(Protocol.TYPE_BYE, (byte) 0, streamId, 0, 0, 0,
                    0, 0, new byte[0], 0, 0);
            synchronized (sendLock) {
                udp.send(p, p.length);
            }
        } catch (Exception ignored) {
        }
    }

    private void startFeedbackLoop() {
        videoStatsWindowStart = System.currentTimeMillis();
        feedbackThread = new Thread(() -> {
            int tick = 0;
            while (running) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
                tick++;
                sendFeedback(tick % 4 == 0);
                expireAssemblies();
                updateStats();
            }
        }, "media-feedback");
        feedbackThread.setDaemon(true);
        feedbackThread.start();
    }

    private void sendFeedback(boolean probe) {
        if (!running) return;
        int flags = 0;
        if (videoNeedsKeyframe.getAndSet(false)) flags |= Protocol.FEEDBACK_KEYFRAME;
        int total = videoPacketsWindow + videoLostWindow;
        int loss = total == 0 ? 0 : Math.min(100, videoLostWindow * 100 / total);
        byte[] fb = MediaPacket.feedback(streamId, lastVideoSeq, videoPacketsWindow,
                videoLostWindow, audioSeq.get(), 0, 0,
                videoRxKbps, audioRxKbps, 0, flags);
        synchronized (sendLock) {
            udp.send(fb, fb.length);
        }
        if (probe) {
            byte[] p = MediaPacket.build(Protocol.TYPE_PROBE, (byte) 0, streamId, 0, 0, 0,
                    0, 0, new byte[0], 0, 0);
            synchronized (sendLock) {
                udp.send(p, p.length);
            }
        }
    }

    private void expireAssemblies() {
        long now = System.currentTimeMillis();
        synchronized (assemblies) {
            Iterator<Map.Entry<Integer, Assembly>> it = assemblies.entrySet().iterator();
            while (it.hasNext()) {
                Assembly a = it.next().getValue();
                if (now - a.updatedAt > 500 && !a.complete()) {
                    it.remove();
                    videoNeedsKeyframe.set(true);
                    if (decoder != null) decoder.requestKeyframe();
                }
            }
        }
    }

    private void updateStats() {
        long now = System.currentTimeMillis();
        long elapsed = Math.max(1, now - videoStatsWindowStart);
        int kbps = (int) (videoBytesWindow * 8 / elapsed);
        int total = videoPacketsWindow + videoLostWindow;
        int loss = total == 0 ? 0 : Math.min(100, videoLostWindow * 100 / total);
        float fps = videoFramesWindow * 1000f / elapsed;
        videoRxKbps = kbps;
        videoLossPercent = loss;
        videoFps = fps;
        CallStats snap = new CallStats();
        snap.videoKbps = kbps;
        snap.audioKbps = audioRxKbps;
        snap.fps = fps;
        snap.lossPercent = Math.max(loss, audioLossPercent);
        snap.rttMs = rttMs;
        snap.width = width;
        snap.height = height;
        snap.targetKbps = abr.getTargetKbps();
        snap.network = networkLabel();
        snap.lastMediaAgeMs = lastMediaAtMs <= 0 ? -1 : now - lastMediaAtMs;
        listener.onStats(snap);
        videoBytesWindow = 0;
        videoPacketsWindow = 0;
        videoLostWindow = 0;
        videoFramesWindow = 0;
        videoStatsWindowStart = now;
        long now2 = now;
        synchronized (assemblies) {
            Iterator<Map.Entry<Integer, Assembly>> it = assemblies.entrySet().iterator();
            while (it.hasNext()) {
                if (now2 - it.next().getValue().updatedAt > 500) {
                    it.remove();
                    videoNeedsKeyframe.set(true);
                    if (decoder != null) decoder.requestKeyframe();
                }
            }
        }
        if (lastMediaAtMs > 0 && now - lastMediaAtMs > 8000
                && now - lastStallNotifyMs > 6000) {
            lastStallNotifyMs = now;
            listener.onMediaError("媒体数据中断，正在尝试恢复", false);
        }
    }

    private String networkLabel() {
        try {
            android.net.Network n = connectivityManager.getActiveNetwork();
            NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(n);
            if (caps == null) return "无网络";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return "Wi-Fi";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) return "移动网络";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return "以太网";
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return "VPN";
        } catch (Exception ignored) {
        }
        return "未知";
    }

    public void attachPreview(SurfaceTexture texture) {
        if (previewTexture == texture) return;
        previewTexture = texture;
        if (running) main.post(() -> maybeStartVideo(false));
    }

    public void detachPreview() {
        previewTexture = null;
        if (camera != null) camera.updatePreview(null);
    }

    public synchronized void attachRemote(Surface surface) {
        if (remoteSurface == surface) return;
        remoteSurface = surface;
        if (decoder != null) decoder.setSurface(surface);
        videoNeedsKeyframe.set(true);
    }

    public synchronized void detachRemote() {
        remoteSurface = null;
    }

    public void setMuted(boolean muted) {
        if (capture != null) capture.setMuted(muted);
    }

    public void setSpeaker(boolean on) {
        speakerOn = on;
        applySpeaker(on);
    }

    public void switchCamera() {
        if (camera != null) camera.switchCamera();
    }

    public boolean isFrontCamera() {
        return camera == null || camera.isFront();
    }

    public void setRtt(int rttMs) {
        this.rttMs = Math.max(0, rttMs);
    }

    public void requestKeyFrame() {
        if (encoder != null) encoder.requestKeyFrame();
        videoNeedsKeyframe.set(true);
    }

    public void setPeer(InetSocketAddress address) {
        this.peer = address;
        udp.setPeer(address);
        videoNeedsKeyframe.set(true);
    }

    public long lastReceiveAgeMs() {
        return lastMediaAtMs <= 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastMediaAtMs;
    }

    public void onNetworkChanged() {
        if (!running) return;
        boolean ok = false;
        for (int i = 0; i < 3 && running && !ok; i++) {
            try {
                udp.reopen();
                udp.setPeer(peer);
                ok = true;
            } catch (Exception e) {
                Log.w(TAG, "network rebind attempt " + (i + 1), e);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (ok) {
            sendFeedback(true);
            if (encoder != null) encoder.requestKeyFrame();
            listener.onMediaError("网络已切换，正在恢复画面", false);
        } else {
            listener.onMediaError("网络切换后媒体端口不可用", true);
        }
    }

    public int getStreamRotation() {
        return streamRotation;
    }

    public boolean isStreamMirror() {
        return streamMirror;
    }

    public void stop() {
        if (running) sendBye();
        running = false;
        if (receiverThread != null) {
            try {
                udp.close();
            } catch (Exception ignored) {
            }
        }
        join(receiverThread, 400);
        join(feedbackThread, 400);
        join(videoSendThread, 400);
        if (camera != null) camera.stop();
        camera = null;
        if (encoder != null) encoder.stop();
        encoder = null;
        if (decoder != null) decoder.release();
        decoder = null;
        if (capture != null) capture.stop();
        capture = null;
        if (playback != null) playback.stop();
        playback = null;
        udp.close();
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }
        try {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            if (Build.VERSION.SDK_INT >= 31) audioManager.clearCommunicationDevice();
        } catch (Exception ignored) {
        }
        synchronized (queueLock) {
            videoQueue.clear();
            queueLock.notifyAll();
        }
        synchronized (assemblies) {
            assemblies.clear();
        }
    }

    private static void join(Thread t, long ms) {
        if (t == null) return;
        try {
            t.join(ms);
        } catch (InterruptedException ignored) {
        }
    }

    /** Small helper kept here to avoid another class for one calculation. */
    private static final class Protocols {
        static int nowTs90k() {
            return (int) ((System.nanoTime() / 1000L * 90L / 1000L) & 0x7fffffff);
        }
    }
}
