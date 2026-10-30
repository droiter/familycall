package com.familycall.app.net;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Surface;

import com.familycall.app.data.CallInfo;
import com.familycall.app.data.CallSnapshot;
import com.familycall.app.data.CallStats;
import com.familycall.app.data.Contact;
import com.familycall.app.data.Prefs;
import com.familycall.app.media.MediaEngine;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** One call: control state machine plus the UDP media engine. */
public final class CallSession implements ControlLink.Listener, MediaEngine.Listener {
    private static final String TAG = "CallSession";
    public static final int RING_TIMEOUT_MS = 45000;
    public static final int RECONNECT_TIMEOUT_MS = 30000;

    public enum Role {CALLER, CALLEE}

    public interface Listener {
        void onSessionStage(CallSession session, CallSnapshot.Stage stage, String detail);

        void onSessionStats(CallSession session, CallStats stats);

        void onSessionPeerVideo(CallSession session, int rotation, boolean mirror);

        void onSessionEnded(CallSession session, CallSnapshot.Stage stage, String message);
    }

    private final Context context;
    private final Prefs prefs;
    private final Role role;
    private final String callId;
    private final String ownName;
    private final int localPort;
    private final Contact contact;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean ended = new AtomicBoolean(false);

    private volatile CallSnapshot.Stage stage = CallSnapshot.Stage.IDLE;
    private volatile String detail = "";
    private volatile String peerName;
    private volatile String peerHost;
    private volatile int peerPort;
    private volatile int peerMediaPort;
    private volatile int peerRotation;
    private volatile boolean peerMirror;
    private volatile int peerWidth = 640;
    private volatile int peerHeight = 480;
    private volatile int localWidth = 640;
    private volatile int localHeight = 480;
    private volatile int cameraRotation;
    private volatile boolean cameraMirror;
    private volatile boolean muted;
    private volatile boolean speakerOn;
    private volatile boolean front = true;
    private volatile long startedAtMs;
    private volatile long acceptedAtMs;
    private volatile boolean reconnecting;
    private volatile boolean running = true;
    private volatile boolean cleaningUp;

    private Socket pendingSocket;
    private JSONObject pendingHello;
    private ControlLink control;
    private MediaEngine media;
    private volatile SurfaceTexture previewTexture;
    private volatile Surface remoteSurface;
    private volatile CallStats lastStats = new CallStats();
    private Thread reconnectThread;
    private Runnable ringTimeout;
    private Runnable dialTimeout;
    private Runnable reconnectTimeout;
    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (control != null && media != null) media.setRtt(control.getRttMs());
            long age = media == null ? Long.MAX_VALUE : media.lastReceiveAgeMs();
            if (stage == CallSnapshot.Stage.ACTIVE && age > 12000) {
                setStage(CallSnapshot.Stage.RECONNECTING, "网络中断，正在恢复…");
            } else if (stage == CallSnapshot.Stage.RECONNECTING && age >= 0 && age < 4000
                    && !reconnecting) {
                setStage(CallSnapshot.Stage.ACTIVE, "通话中");
            }
            if (media == null && stage != CallSnapshot.Stage.RINGING
                    && stage != CallSnapshot.Stage.DIALING) {
                finish(CallSnapshot.Stage.FAILED, "媒体通道未能启动");
                return;
            }
            if (media != null && age > 60000) {
                finish(CallSnapshot.Stage.FAILED, "网络中断时间过长，通话已结束");
                return;
            }
            main.postDelayed(this, 2000);
        }
    };

    private CallSession(Context context, Prefs prefs, Role role, String callId, Contact contact,
                        String peerName, String peerHost, int peerPort, Listener listener) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.role = role;
        this.callId = callId;
        this.contact = contact;
        this.ownName = prefs.getName();
        this.localPort = prefs.getLocalPort();
        this.listener = listener;
        this.peerName = peerName;
        this.peerHost = peerHost;
        this.peerPort = peerPort;
        this.peerMediaPort = peerPort;
        this.speakerOn = prefs.isSpeakerOn();
    }

    public static CallSession outgoing(Context context, Prefs prefs, Contact contact,
                                       Listener listener) {
        CallSession s = new CallSession(context, prefs, Role.CALLER, Protocol.newCallId(),
                contact, contact.name, contact.host, contact.port, listener);
        s.pendingHello = new JSONObject();
        return s;
    }

    public static CallSession incoming(Context context, Prefs prefs, Socket socket,
                                       JSONObject hello, Listener listener) throws Exception {
        String callId = hello.optString("callId", "");
        if (callId.isEmpty()) throw new IllegalArgumentException("missing callId");
        String name = hello.optString("name", "对方");
        int listenPort = hello.optInt("mediaPort", hello.optInt("listenPort", 0));
        String host = socket.getInetAddress().getHostAddress();
        CallSession s = new CallSession(context, prefs, Role.CALLEE, callId, null,
                name, host, socket.getPort(), listener);
        s.pendingSocket = socket;
        s.pendingHello = hello;
        s.peerMediaPort = listenPort > 0 ? listenPort : socket.getPort();
        return s;
    }

    public String getCallId() {
        return callId;
    }

    public String getPeerName() {
        return peerName == null || peerName.isEmpty() ? "对方" : peerName;
    }

    public Role getRole() {
        return role;
    }

    public boolean isIncoming() {
        return role == Role.CALLEE;
    }

    public CallSnapshot.Stage getStage() {
        return stage;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isMuted() {
        return muted;
    }

    public boolean isSpeakerOn() {
        return speakerOn;
    }

    public boolean isFrontCamera() {
        return media == null ? front : media.isFrontCamera();
    }

    public long getStartedAtMs() {
        return startedAtMs;
    }

    public boolean isEnded() {
        return ended.get();
    }

    public boolean isCleaningUp() {
        return cleaningUp;
    }

    public CallStats getStats() {
        return lastStats;
    }

    public int getPeerRotation() {
        return peerRotation;
    }

    public boolean isPeerMirror() {
        return peerMirror;
    }

    public int getLocalRotation() {
        return cameraRotation;
    }

    public boolean isLocalMirror() {
        return cameraMirror;
    }

    public int getPeerWidth() {
        return peerWidth;
    }

    public int getPeerHeight() {
        return peerHeight;
    }

    public int getLocalWidth() {
        return localWidth;
    }

    public int getLocalHeight() {
        return localHeight;
    }

    public CallInfo getInfo() {
        return new CallInfo(callId, getPeerName(), peerHost, peerPort, isIncoming(),
                contact == null ? null : contact.id);
    }

    public void startOutgoing() {
        setStage(CallSnapshot.Stage.DIALING, "正在连接 " + peerHost + ":" + peerPort);
        dialTimeout = () -> {
            if (!ended.get() && stage == CallSnapshot.Stage.DIALING) {
                sendSimple("bye", "no-answer");
                finish(CallSnapshot.Stage.ENDED, "对方无应答");
            }
        };
        main.postDelayed(dialTimeout, RING_TIMEOUT_MS);
        Thread t = new Thread(this::connectOutgoing, "caller-connect");
        t.setDaemon(true);
        t.start();
    }

    public void startIncoming() {
        if (pendingSocket == null) {
            finish(CallSnapshot.Stage.FAILED, "来电连接已失效");
            return;
        }
        try {
            installControl(pendingSocket);
            pendingSocket = null;
            setStage(CallSnapshot.Stage.RINGING, "来电");
            sendRingAck();
            ringTimeout = () -> {
                if (!ended.get() && stage == CallSnapshot.Stage.RINGING) {
                    sendSimple("reject", "timeout");
                    finish(CallSnapshot.Stage.ENDED, "无人接听");
                }
            };
            main.postDelayed(ringTimeout, RING_TIMEOUT_MS);
        } catch (Exception e) {
            finish(CallSnapshot.Stage.FAILED, "无法建立控制连接");
        }
    }

    private void connectOutgoing() {
        Socket socket = null;
        try {
            socket = new Socket();
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.connect(new InetSocketAddress(peerHost, peerPort), 8000);
            if (ended.get()) {
                closeQuietly(socket);
                return;
            }
            installControl(socket);
            JSONObject hello = new JSONObject();
            hello.put("t", "hello");
            hello.put("v", Protocol.VERSION);
            hello.put("callId", callId);
            hello.put("name", ownName);
            hello.put("mediaPort", localPort);
            hello.put("listenPort", localPort);
            hello.put("resume", false);
            control.send(hello);
            if (ended.get()) {
                finish(CallSnapshot.Stage.ENDED, "已取消呼叫");
                return;
            }
            setStage(CallSnapshot.Stage.DIALING, "正在等待对方接听");
        } catch (Exception e) {
            Log.w(TAG, "outgoing connect", e);
            closeQuietly(socket);
            if (!ended.get()) finish(CallSnapshot.Stage.FAILED,
                    "无法连接到 " + peerHost + ":" + peerPort + "，请检查网络和端口");
        }
    }

    private void installControl(Socket socket) throws Exception {
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        ControlLink old = control;
        control = new ControlLink(socket, this);
        control.start();
        if (old != null) old.close("replaced");
        reconnecting = false;
        if (reconnectTimeout != null) {
            main.removeCallbacks(reconnectTimeout);
            reconnectTimeout = null;
        }
        startWatchdog();
    }

    private void startWatchdog() {
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, 2000);
    }

    public void answer() {
        if (role != Role.CALLEE || stage != CallSnapshot.Stage.RINGING || ended.get()) return;
        try {
            cancelRingTimeout();
            JSONObject accept = new JSONObject();
            accept.put("t", "accept");
            accept.put("callId", callId);
            accept.put("name", ownName);
            accept.put("mediaPort", localPort);
            accept.put("listenPort", localPort);
            accept.put("resume", false);
            accept.put("rot", cameraRotation);
            accept.put("mirror", false);
            control.send(accept);
            acceptedAtMs = System.currentTimeMillis();
            startedAtMs = acceptedAtMs;
            startMedia();
            setStage(CallSnapshot.Stage.ACTIVE, "通话中");
            if (media != null) media.requestKeyFrame();
        } catch (Exception e) {
            finish(CallSnapshot.Stage.FAILED, "接听失败");
        }
    }

    public void decline() {
        if (role != Role.CALLEE || ended.get()) return;
        cancelRingTimeout();
        sendSimple("reject", "declined");
        finish(CallSnapshot.Stage.ENDED, "已拒绝来电");
    }

    public void hangup() {
        if (ended.get()) return;
        sendSimple("bye", "hangup");
        finish(CallSnapshot.Stage.ENDED, "通话已结束");
    }

    private void sendRingAck() {
        try {
            JSONObject ringing = new JSONObject();
            ringing.put("t", "ringing");
            ringing.put("callId", callId);
            control.send(ringing);
        } catch (Exception ignored) {
        }
    }

    private void sendSimple(String type, String reason) {
        try {
            if (control != null) {
                JSONObject o = new JSONObject();
                o.put("t", type);
                o.put("callId", callId);
                if (reason != null) o.put("reason", reason);
                control.send(o);
            }
        } catch (Exception ignored) {
        }
    }

    private void startMedia() {
        if (media != null) return;
        int[] quality = chooseQuality();
        int width = quality[0];
        int height = quality[1];
        localWidth = width;
        localHeight = height;
        int fps = quality[2];
        int start = quality[3];
        int max = quality[4];
        try {
            InetSocketAddress peer = new InetSocketAddress(peerHost,
                    peerMediaPort > 0 ? peerMediaPort : peerPort);
            media = new MediaEngine(context, localPort, peer, callId,
                    Protocol.streamId(callId), width, height, fps, start, max,
                    speakerOn, this);
            media.setRtt(control == null ? 0 : control.getRttMs());
            if (previewTexture != null) media.attachPreview(previewTexture);
            if (remoteSurface != null) media.attachRemote(remoteSurface);
            if (!media.start()) {
                media = null;
                return;
            }
            media.setMuted(muted);
            media.setSpeaker(speakerOn);
        } catch (Exception e) {
            Log.e(TAG, "start media", e);
            finish(CallSnapshot.Stage.FAILED, "无法启动媒体传输");
        }
    }

    private int[] chooseQuality() {
        int q = prefs.getQuality();
        boolean cellular = isCellular();
        if (q == Prefs.QUALITY_SMOOTH) {
            return new int[]{640, 480, 15, cellular ? 260 : 320, cellular ? 420 : 550};
        }
        if (q == Prefs.QUALITY_HD) {
            return new int[]{1280, 720, 24, cellular ? 800 : 1400, cellular ? 1500 : 2600};
        }
        return new int[]{640, 480, 24, cellular ? 480 : 700, cellular ? 1000 : 1500};
    }

    private boolean isCellular() {
        try {
            ConnectivityManager cm = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Exception e) {
            return false;
        }
    }

    public void attachPreview(SurfaceTexture texture) {
        previewTexture = texture;
        if (media != null) media.attachPreview(texture);
    }

    public void detachPreview() {
        previewTexture = null;
        if (media != null) media.detachPreview();
    }

    public void attachRemote(Surface surface) {
        remoteSurface = surface;
        if (media != null) media.attachRemote(surface);
    }

    public void detachRemote() {
        remoteSurface = null;
        if (media != null) media.detachRemote();
    }

    public void setMuted(boolean value) {
        muted = value;
        if (media != null) media.setMuted(value);
    }

    public void setSpeaker(boolean value) {
        speakerOn = value;
        prefs.setSpeakerOn(value);
        if (media != null) media.setSpeaker(value);
    }

    public void switchCamera() {
        front = !front;
        if (media != null) media.switchCamera();
    }

    public void onNetworkChanged() {
        if (ended.get()) return;
        Thread t = new Thread(() -> {
            if (ended.get()) return;
            if (media != null) media.onNetworkChanged();
            if (stage == CallSnapshot.Stage.ACTIVE) {
                setStage(CallSnapshot.Stage.ACTIVE, "网络已切换，正在稳定画面…");
            }
        }, "network-change");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onControlMessage(ControlLink link, JSONObject message) {
        if (ended.get()) return;
        String t = message.optString("t", "");
        switch (t) {
            case "ringing":
                if (stage == CallSnapshot.Stage.DIALING) {
                    setStage(CallSnapshot.Stage.DIALING, "正在等待对方接听");
                }
                break;
            case "accept":
                if (role == Role.CALLER) handleAccept(message);
                break;
            case "reject":
                finish(CallSnapshot.Stage.ENDED, "对方已拒绝通话");
                break;
            case "busy":
                finish(CallSnapshot.Stage.ENDED, "对方正在通话中");
                break;
            case "bye":
                finish(CallSnapshot.Stage.ENDED, "对方已挂断");
                break;
            case "ended":
                finish(CallSnapshot.Stage.ENDED, "通话已结束");
                break;
            case "video":
                peerRotation = ((message.optInt("rot", 0) % 360) + 360) % 360;
                peerMirror = message.optBoolean("mirror", false);
                listener.onSessionPeerVideo(this, peerRotation, peerMirror);
                break;
            default:
                break;
        }
    }

    private void handleAccept(JSONObject message) {
        boolean resume = message.optBoolean("resume", false);
        cancelDialTimeout();
        String name = message.optString("name", "");
        if (contact == null && !name.isEmpty()) peerName = name;
        int mp = message.optInt("mediaPort", 0);
        if (mp > 0) peerMediaPort = mp;
        int rot = message.optInt("rot", 0);
        peerRotation = ((rot % 360) + 360) % 360;
        peerMirror = message.optBoolean("mirror", false);
        listener.onSessionPeerVideo(this, peerRotation, peerMirror);

        if (acceptedAtMs == 0) {
            acceptedAtMs = System.currentTimeMillis();
            startedAtMs = acceptedAtMs;
        }
        if (media == null) {
            startMedia();
        } else {
            media.setPeer(new InetSocketAddress(peerHost, peerMediaPort));
            media.setRtt(control == null ? 0 : control.getRttMs());
        }
        reconnecting = false;
        setStage(CallSnapshot.Stage.ACTIVE, resume ? "通话已恢复" : "通话中");
        if (media != null) media.requestKeyFrame();
    }

    @Override
    public void onControlClosed(ControlLink link, String reason) {
        if (ended.get() || link != control) return;
        control = null;
        if (stage == CallSnapshot.Stage.RINGING) {
            cancelRingTimeout();
            finish(CallSnapshot.Stage.ENDED, "对方已取消呼叫");
        } else if (stage == CallSnapshot.Stage.DIALING) {
            finish(CallSnapshot.Stage.FAILED, "连接已断开：" + reason);
        } else if (stage == CallSnapshot.Stage.ACTIVE
                || stage == CallSnapshot.Stage.RECONNECTING) {
            startReconnect();
        }
    }

    private void startReconnect() {
        if (ended.get() || reconnecting) return;
        reconnecting = true;
        setStage(CallSnapshot.Stage.RECONNECTING, "网络波动，正在重连…");
        if (role == Role.CALLER) {
            reconnectThread = new Thread(() -> {
                for (int i = 0; i < 20 && running && !ended.get(); i++) {
                    if (i > 0) {
                        try {
                            Thread.sleep(1200);
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    Socket socket = null;
                    try {
                        socket = new Socket();
                        socket.setTcpNoDelay(true);
                        socket.connect(new InetSocketAddress(peerHost, peerPort), 5000);
                        if (ended.get()) {
                            closeQuietly(socket);
                            return;
                        }
                        installControl(socket);
                        JSONObject hello = new JSONObject();
                        hello.put("t", "hello");
                        hello.put("v", Protocol.VERSION);
                        hello.put("callId", callId);
                        hello.put("name", ownName);
                        hello.put("mediaPort", localPort);
                        hello.put("listenPort", localPort);
                        hello.put("resume", true);
                        control.send(hello);
                        if (ended.get()) {
                            finish(CallSnapshot.Stage.ENDED, "已取消通话");
                        }
                        return;
                    } catch (Exception e) {
                        closeQuietly(socket);
                    }
                }
                if (!ended.get()) {
                    if (media != null && media.lastReceiveAgeMs() < 4000) {
                        reconnecting = false;
                        setStage(CallSnapshot.Stage.ACTIVE, "通话中（控制通道不可用）");
                    } else {
                        finish(CallSnapshot.Stage.FAILED, "网络中断，无法恢复通话");
                    }
                }
            }, "caller-reconnect");
            reconnectThread.setDaemon(true);
            reconnectThread.start();
        } else {
            reconnectTimeout = () -> {
                if (ended.get() || !reconnecting) return;
                if (media != null && media.lastReceiveAgeMs() < 4000) {
                    reconnecting = false;
                    setStage(CallSnapshot.Stage.ACTIVE, "通话中（控制通道不可用）");
                    return;
                }
                finish(CallSnapshot.Stage.FAILED, "网络中断，通话已结束");
            };
            main.postDelayed(reconnectTimeout, RECONNECT_TIMEOUT_MS);
        }
    }

    /** Called by the TCP listener when the peer reconnects during an active call. */
    public void attachResumed(Socket socket, JSONObject hello) throws Exception {
        if (ended.get()) throw new IllegalStateException("call ended");
        String host = socket.getInetAddress().getHostAddress();
        int mp = hello.optInt("mediaPort", hello.optInt("listenPort", 0));
        String name = hello.optString("name", "");
        if (!name.isEmpty()) peerName = name;
        peerHost = host;
        if (mp > 0) peerMediaPort = mp;
        installControl(socket);
        JSONObject accept = new JSONObject();
        accept.put("t", "accept");
        accept.put("callId", callId);
        accept.put("name", ownName);
        accept.put("mediaPort", localPort);
        accept.put("listenPort", localPort);
        accept.put("resume", true);
        accept.put("rot", cameraRotation);
        accept.put("mirror", false);
        control.send(accept);
        reconnecting = false;
        if (media != null) {
            media.setPeer(new InetSocketAddress(host, peerMediaPort));
            media.setRtt(control.getRttMs());
            media.requestKeyFrame();
        }
        setStage(CallSnapshot.Stage.ACTIVE, "通话已恢复");
    }

    @Override
    public void onStats(CallStats stats) {
        lastStats = stats;
        listener.onSessionStats(this, stats);
    }

    @Override
    public void onPeerVideoInfo(int rotation, boolean mirror) {
        cameraRotation = rotation;
        cameraMirror = mirror;
        listener.onSessionPeerVideo(this, peerRotation, peerMirror);
        // Rotation in the wire message describes the sender's raw stream; the
        // receiver needs that rotation to render it upright.
        sendVideoInfo(rotation, false);
    }

    private void sendVideoInfo(int rotation, boolean mirror) {
        if (control == null) return;
        try {
            JSONObject o = new JSONObject();
            o.put("t", "video");
            o.put("rot", rotation);
            o.put("mirror", mirror);
            control.send(o);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onPeerBye() {
        main.post(() -> finish(CallSnapshot.Stage.ENDED, "对方已挂断"));
    }

    @Override
    public void onPeerVideoSize(int width, int height) {
        if (width > 0) peerWidth = width;
        if (height > 0) peerHeight = height;
        listener.onSessionPeerVideo(this, peerRotation, peerMirror);
    }

    @Override
    public void onMediaError(String message, boolean fatal) {
        Log.w(TAG, "media: " + message + " fatal=" + fatal);
        main.post(() -> {
            if (ended.get()) return;
            if (fatal) {
                finish(CallSnapshot.Stage.FAILED, message);
            } else {
                final CallSnapshot.Stage current = stage;
                setStage(current == CallSnapshot.Stage.IDLE
                        ? CallSnapshot.Stage.CONNECTING : current, message);
                main.postDelayed(() -> {
                    if (!ended.get() && stage == current && detail.equals(message)) {
                        setStage(current, current == CallSnapshot.Stage.ACTIVE ? "通话中" : "");
                    }
                }, 4000);
            }
        });
    }

    @Override
    public void onFirstMediaReceived() {
        main.post(() -> {
            if (!ended.get() && stage != CallSnapshot.Stage.ACTIVE) {
                setStage(CallSnapshot.Stage.ACTIVE, "通话中");
            }
        });
    }

    private void setStage(CallSnapshot.Stage newStage, String newDetail) {
        if (ended.get() && newStage != CallSnapshot.Stage.ENDED
                && newStage != CallSnapshot.Stage.FAILED) return;
        stage = newStage;
        detail = newDetail;
        main.post(() -> listener.onSessionStage(this, newStage, newDetail));
    }

    private void finish(CallSnapshot.Stage finalStage, String message) {
        if (!ended.compareAndSet(false, true)) return;
        running = false;
        cancelRingTimeout();
        cancelDialTimeout();
        if (reconnectTimeout != null) main.removeCallbacks(reconnectTimeout);
        main.removeCallbacks(watchdog);
        setStage(finalStage, message);
        sendSimple("bye", "ended");
        if (control != null) {
            control.close("call finished");
            control = null;
        }
        closeQuietly(pendingSocket);
        pendingSocket = null;
        final MediaEngine cleanup = media;
        media = null;
        cleaningUp = true;
        Thread t = new Thread(() -> {
            try {
                if (cleanup != null) cleanup.stop();
            } catch (Exception ignored) {
            } finally {
                cleaningUp = false;
            }
        }, "call-cleanup");
        t.setDaemon(true);
        t.start();
        main.post(() -> listener.onSessionEnded(this, finalStage, message));
        Log.i(TAG, "call finished: " + finalStage + " " + message);
    }

    private void cancelRingTimeout() {
        if (ringTimeout != null) {
            main.removeCallbacks(ringTimeout);
            ringTimeout = null;
        }
    }

    private void cancelDialTimeout() {
        if (dialTimeout != null) {
            main.removeCallbacks(dialTimeout);
            dialTimeout = null;
        }
    }

    private static void closeQuietly(Socket s) {
        if (s == null) return;
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }
}
