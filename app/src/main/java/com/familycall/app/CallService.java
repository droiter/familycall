package com.familycall.app;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;

import com.familycall.app.data.CallInfo;
import com.familycall.app.data.CallSnapshot;
import com.familycall.app.data.CallStats;
import com.familycall.app.data.Contact;
import com.familycall.app.data.Prefs;
import com.familycall.app.net.CallSession;
import com.familycall.app.net.InboundListener;
import com.familycall.app.net.Protocol;

import org.json.JSONObject;

import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Foreground call engine: listens for direct calls, owns the active call and notifications. */
public final class CallService extends Service implements CallSession.Listener,
        InboundListener.Callback {
    private static final String TAG = "CallService";
    public static final String ACTION_START = "com.familycall.app.START";
    public static final String ACTION_ANSWER = "com.familycall.app.ANSWER";
    public static final String ACTION_DECLINE = "com.familycall.app.DECLINE";
    public static final String ACTION_HANGUP = "com.familycall.app.HANGUP";
    public static final String ACTION_STOP = "com.familycall.app.STOP_SERVICE";

    private static final String CHANNEL_SERVICE = "service";
    private static final String CHANNEL_INCOMING = "incoming";
    private static final int NOTIF_SERVICE = 1001;
    private static final int NOTIF_INCOMING = 1002;

    public interface UiListener {
        void onSnapshot(CallSnapshot snapshot);

        void onIncoming(CallInfo info);

        void onIncomingCancelled();

        void onCallEnded(String message);

        void onServiceMessage(String message);
    }

    public final class LocalBinder extends Binder {
        public CallService getService() {
            return CallService.this;
        }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final CopyOnWriteArrayList<UiListener> listeners = new CopyOnWriteArrayList<>();
    private Prefs prefs;
    private InboundListener inbound;
    private volatile CallSession session;
    private volatile CallInfo incomingInfo;
    private volatile CallStats lastStats = new CallStats();
    private String listenerError;
    private boolean listening;
    private boolean foreground;
    private Ringtone ringtone;
    private Vibrator vibrator;
    private ConnectivityManager connectivityManager;
    private ConnectivityManager.NetworkCallback networkCallback;
    private long lastClearRunnableToken;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new Prefs(this);
        createChannels();
        ensureForeground();
        registerNetworkCallback();
        inbound = new InboundListener(this);
        startListening();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_ANSWER.equals(action)) {
                answer();
            } else if (ACTION_DECLINE.equals(action)) {
                decline();
            } else if (ACTION_HANGUP.equals(action)) {
                hangup();
            } else if (ACTION_STOP.equals(action)) {
                requestStop();
                return START_NOT_STICKY;
            }
        }
        ensureForeground();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        stopRinging();
        stopVibrating();
        if (inbound != null) inbound.stop();
        if (session != null) {
            session.hangup();
            session = null;
        }
        unregisterNetworkCallback();
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    public void addUiListener(UiListener listener) {
        if (!listeners.contains(listener)) listeners.add(listener);
        final CallSnapshot s = getSnapshot();
        final CallInfo incoming = incomingInfo;
        main.post(() -> {
            listener.onSnapshot(s);
            if (incoming != null) listener.onIncoming(incoming);
        });
        updateServiceNotification();
    }

    public void removeUiListener(UiListener listener) {
        listeners.remove(listener);
    }

    public CallSnapshot getSnapshot() {
        CallSnapshot s = new CallSnapshot();
        s.childMode = prefs != null && prefs.isChildMode();
        s.stats = lastStats;
        CallSession cs = session;
        if (cs == null) {
            s.stage = CallSnapshot.Stage.IDLE;
            s.detail = listening ? "正在等待来电" : (listenerError == null ? "未监听" : listenerError);
            return s;
        }
        s.stage = cs.getStage();
        s.peerName = cs.getPeerName();
        s.detail = cs.getDetail();
        s.incoming = cs.isIncoming();
        s.muted = cs.isMuted();
        s.speakerOn = cs.isSpeakerOn();
        s.frontCamera = cs.isFrontCamera();
        s.startedAtMs = cs.getStartedAtMs();
        s.info = cs.getInfo();
        s.stats = lastStats;
        s.peerRotation = cs.getPeerRotation();
        s.peerMirror = cs.isPeerMirror();
        s.localRotation = cs.getLocalRotation();
        s.localMirror = cs.isLocalMirror();
        s.localWidth = cs.getLocalWidth();
        s.localHeight = cs.getLocalHeight();
        s.peerWidth = cs.getPeerWidth();
        s.peerHeight = cs.getPeerHeight();
        return s;
    }

    public boolean isChildMode() {
        return prefs != null && prefs.isChildMode();
    }

    public boolean isBusy() {
        return session != null && (!session.isEnded() || session.isCleaningUp());
    }

    public CallInfo getIncomingInfo() {
        return incomingInfo;
    }

    public boolean isListening() {
        return listening;
    }

    public String getListenerError() {
        return listenerError;
    }

    public int getListeningPort() {
        return prefs == null ? 50000 : prefs.getLocalPort();
    }

    public void callContact(final Contact contact) {
        if (contact == null || !contact.isValid()) {
            message("联系人信息不完整");
            return;
        }
        if (isBusy()) {
            message("当前正在通话中或正在结束通话，请稍候");
            return;
        }
        if (prefs.isChildMode()) {
            List<Contact> contacts = prefs.getContacts();
            if (contacts.isEmpty()) {
                message("儿童模式下没有可拨打的联系人");
                return;
            }
            if (!prefs.isApprovedContact(contact)) {
                message("儿童模式只能拨打已配置的联系人");
                return;
            }
        }
        if (!hasPermissions()) {
            message("请先授予摄像头和麦克风权限");
            return;
        }
        refreshForegroundTypes();
        CallSession s = CallSession.outgoing(this, prefs, contact, this);
        session = s;
        incomingInfo = null;
        lastStats = new CallStats();
        emitSnapshot();
        s.startOutgoing();
        startActivity(new Intent(this, CallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        updateServiceNotification();
    }

    public boolean answer() {
        CallSession s = session;
        if (s == null || !s.isIncoming() || s.getStage() != CallSnapshot.Stage.RINGING) return false;
        if (!hasPermissions()) {
            message("请先授予摄像头和麦克风权限，然后接听");
            return false;
        }
        stopRinging();
        stopVibrating();
        cancelIncomingNotification();
        refreshForegroundTypes();
        s.answer();
        incomingInfo = null;
        emitSnapshot();
        startActivity(new Intent(this, CallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        updateServiceNotification();
        return true;
    }

    public void decline() {
        stopRinging();
        stopVibrating();
        cancelIncomingNotification();
        incomingInfo = null;
        emitIncomingCancelled();
        if (session != null && session.isIncoming()) session.decline();
        updateServiceNotification();
    }

    /**
     * Child mode may cancel an outgoing call before it is answered, but never
     * terminate an answered call.
     */
    public boolean cancelOutgoing() {
        CallSession s = session;
        if (s == null) return false;
        if (s.getStage() == CallSnapshot.Stage.DIALING) {
            s.hangup();
            return true;
        }
        return false;
    }

    /** @return false when child mode forbids hanging up from the app. */
    public boolean hangup() {
        if (prefs.isChildMode()) return false;
        CallSession s = session;
        if (s != null) s.hangup();
        return true;
    }

    public void requestStop() {
        if (session != null) session.hangup();
        session = null;
        if (inbound != null) inbound.stop();
        stopRinging();
        stopVibrating();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    public void refreshForegroundTypes() {
        if (!foreground) {
            ensureForeground();
            return;
        }
        if (Build.VERSION.SDK_INT < 29) return;
        try {
            startForeground(NOTIF_SERVICE, buildServiceNotification(), foregroundTypes());
        } catch (Exception e) {
            Log.w(TAG, "refresh foreground types", e);
        }
    }

    private int foregroundTypes() {
        int types;
        if (Build.VERSION.SDK_INT >= 34) {
            // Android 15 limits dataSync foreground services to six hours. A
            // direct-call listener is a special use, so it can stay resident.
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE;
        } else {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA;
            }
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            }
        }
        return types;
    }

    public void applySettingsChanged() {
        if (inbound == null) inbound = new InboundListener(this);
        startListening();
        updateServiceNotification();
        for (UiListener l : listeners) main.post(() -> l.onSnapshot(getSnapshot()));
    }

    public void setMuted(boolean muted) {
        if (session != null) session.setMuted(muted);
        emitSnapshot();
    }

    public void setSpeaker(boolean on) {
        if (session != null) session.setSpeaker(on);
        emitSnapshot();
    }

    public void switchCamera() {
        if (session != null) session.switchCamera();
        emitSnapshot();
    }

    public void attachPreview(String callId, android.graphics.SurfaceTexture texture) {
        CallSession s = session;
        if (s != null && s.getCallId().equals(callId)) s.attachPreview(texture);
    }

    public void detachPreview(String callId) {
        CallSession s = session;
        if (s != null && s.getCallId().equals(callId)) s.detachPreview();
    }

    public void attachRemote(String callId, android.view.Surface surface) {
        CallSession s = session;
        if (s != null && s.getCallId().equals(callId)) s.attachRemote(surface);
    }

    public void detachRemote(String callId) {
        CallSession s = session;
        if (s != null && s.getCallId().equals(callId)) s.detachRemote();
    }

    private void startListening() {
        int port = prefs.getLocalPort();
        if (inbound == null) inbound = new InboundListener(this);
        inbound.start(port);
        updateServiceNotification();
    }

    private boolean hasPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onHello(Socket socket, JSONObject hello) {
        main.post(() -> handleHello(socket, hello));
    }

    private void handleHello(Socket socket, JSONObject hello) {
        String callId = hello.optString("callId", "");
        if (callId.isEmpty()) {
            InboundListener.reject(socket, "bad-call");
            return;
        }
        boolean resume = hello.optBoolean("resume", false);
        CallSession current = session;
        if (resume && (current == null || !current.getCallId().equals(callId))) {
            InboundListener.sendEnded(socket, "unknown-call");
            return;
        }
        if (current != null && current.isCleaningUp()) {
            InboundListener.reject(socket, "cleaning-up");
            return;
        }
        if (current != null && !current.isEnded() && current.getCallId().equals(callId)) {
            if (resume) {
                try {
                    current.attachResumed(socket, hello);
                } catch (Exception e) {
                    Log.w(TAG, "resume failed", e);
                    InboundListener.reject(socket, "resume-failed");
                }
            } else {
                InboundListener.reject(socket, "busy");
            }
            return;
        }
        if (current != null && !current.isEnded()) {
            InboundListener.reject(socket, "busy");
            return;
        }
        if (current != null && current.isEnded()) {
            session = null;
        }
        try {
            CallSession s = CallSession.incoming(this, prefs, socket, hello, this);
            session = s;
            lastStats = new CallStats();
            s.startIncoming();
            incomingInfo = s.getInfo();
            emitSnapshot();
            emitIncoming(incomingInfo);
            showIncomingNotification(incomingInfo);
            startRinging();
            startActivity(new Intent(this, IncomingCallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        } catch (Exception e) {
            Log.e(TAG, "incoming call", e);
            InboundListener.reject(socket, "error");
        }
    }

    @Override
    public void onListenerState(boolean listening, int port, String error) {
        if (prefs != null && port != prefs.getLocalPort()) return;
        this.listening = listening;
        this.listenerError = error;
        main.post(() -> {
            updateServiceNotification();
            for (UiListener l : listeners) {
                l.onSnapshot(getSnapshot());
                if (error != null) l.onServiceMessage(error);
            }
        });
    }

    @Override
    public void onSessionStage(CallSession s, CallSnapshot.Stage stage, String detail) {
        if (s != session) return;
        main.post(() -> {
            emitSnapshot();
            updateServiceNotification();
            if (stage == CallSnapshot.Stage.RINGING) {
                updateIncomingNotification();
            }
        });
    }

    @Override
    public void onSessionStats(CallSession s, CallStats stats) {
        if (s != session) return;
        lastStats = stats;
        main.post(() -> {
            for (UiListener l : listeners) l.onSnapshot(getSnapshot());
        });
    }

    @Override
    public void onSessionPeerVideo(CallSession s, int rotation, boolean mirror) {
        if (s != session) return;
        main.post(this::emitSnapshot);
    }

    @Override
    public void onSessionEnded(CallSession s, CallSnapshot.Stage stage, String message) {
        if (s != session) return;
        main.post(() -> {
            stopRinging();
            stopVibrating();
            cancelIncomingNotification();
            incomingInfo = null;
            emitIncomingCancelled();
            emitSnapshot();
            for (UiListener l : listeners) l.onCallEnded(message);
            emitServiceEndedNotificationSafe(message);
            updateServiceNotification();
            long token = ++lastClearRunnableToken;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (token != lastClearRunnableToken || session != s) return;
                    if (s.isCleaningUp()) {
                        main.postDelayed(this, 1000);
                        return;
                    }
                    session = null;
                    lastStats = new CallStats();
                    emitSnapshot();
                    updateServiceNotification();
                }
            }, 5000);
        });
    }

    private void emitServiceEndedNotificationSafe(String message) {
        // The call UI shows the reason; service notification returns to listening.
        Log.i(TAG, "call ended: " + message);
    }

    private void emitSnapshot() {
        CallSnapshot s = getSnapshot();
        for (UiListener l : listeners) {
            try {
                l.onSnapshot(s);
            } catch (Exception ignored) {
            }
        }
    }

    private void emitIncoming(CallInfo info) {
        for (UiListener l : listeners) {
            try {
                l.onIncoming(info);
            } catch (Exception ignored) {
            }
        }
    }

    private void emitIncomingCancelled() {
        for (UiListener l : listeners) {
            try {
                l.onIncomingCancelled();
            } catch (Exception ignored) {
            }
        }
    }

    private void message(String text) {
        for (UiListener l : listeners) {
            try {
                l.onServiceMessage(text);
            } catch (Exception ignored) {
            }
        }
    }

    private void ensureForeground() {
        if (foreground) return;
        Notification n = buildServiceNotification();
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIF_SERVICE, n, foregroundTypes());
            } else {
                startForeground(NOTIF_SERVICE, n);
            }
        } catch (Exception e) {
            Log.w(TAG, "foreground start with types failed; retrying", e);
            try {
                startForeground(NOTIF_SERVICE, n);
            } catch (Exception e2) {
                Log.e(TAG, "cannot start foreground", e2);
            }
        }
        foreground = true;
    }

    private void createChannels() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel service = new NotificationChannel(CHANNEL_SERVICE,
                    "通话服务", NotificationManager.IMPORTANCE_LOW);
            service.setDescription("保持通话监听与通话进行");
            service.setShowBadge(false);
            nm.createNotificationChannel(service);

            NotificationChannel incoming = new NotificationChannel(CHANNEL_INCOMING,
                    "视频来电", NotificationManager.IMPORTANCE_HIGH);
            incoming.setDescription("视频通话来电提醒");
            incoming.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            incoming.setSound(null, null);
            incoming.enableVibration(false);
            nm.createNotificationChannel(incoming);
        }
    }

    private Notification buildServiceNotification() {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 1, open,
                pendingFlags());
        Notification.Builder b = baseBuilder(CHANNEL_SERVICE)
                .setContentTitle("亲情视频")
                .setContentText(listening ? "正在监听 · 端口 " + prefs.getLocalPort() : "服务未监听")
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        CallSession s = session;
        if (s != null && !s.isEnded()
                && s.getStage() != CallSnapshot.Stage.RINGING) {
            b.setContentTitle("与 " + s.getPeerName() + " 视频通话中");
            b.setContentText(statusText(s.getStage()));
            Intent call = new Intent(this, CallActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                            | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            b.setContentIntent(PendingIntent.getActivity(this, 3, call, pendingFlags()));
            if (!prefs.isChildMode()) {
                Intent hang = new Intent(this, CallService.class).setAction(ACTION_HANGUP);
                b.addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_call_end),
                        "挂断", PendingIntent.getService(this, 4, hang, pendingFlags())).build());
            }
        } else if (s == null || s.isEnded()) {
            Intent stop = new Intent(this, CallService.class).setAction(ACTION_STOP);
            b.addAction(new Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close),
                    "停止监听", PendingIntent.getService(this, 5, stop, pendingFlags())).build());
        }
        return b.build();
    }

    private String statusText(CallSnapshot.Stage stage) {
        switch (stage) {
            case DIALING:
                return "正在呼叫…";
            case CONNECTING:
                return "正在接通…";
            case RECONNECTING:
                return "网络波动，正在重连…";
            case ACTIVE:
                return "通话中";
            default:
                return "";
        }
    }

    private Notification.Builder baseBuilder(String channel) {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            b = new Notification.Builder(this, channel);
        } else {
            b = new Notification.Builder(this);
        }
        return b;
    }

    private int pendingFlags() {
        return Build.VERSION.SDK_INT >= 23
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
    }

    private void updateServiceNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            nm.notify(NOTIF_SERVICE, buildServiceNotification());
        } catch (Exception ignored) {
        }
    }

    private void showIncomingNotification(CallInfo info) {
        Intent full = new Intent(this, IncomingCallActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent fullPi = PendingIntent.getActivity(this, 10, full, pendingFlags());

        Intent answer = new Intent(this, CallService.class).setAction(ACTION_ANSWER);
        PendingIntent answerPi = PendingIntent.getService(this, 11, answer, pendingFlags());
        Intent decline = new Intent(this, CallService.class).setAction(ACTION_DECLINE);
        PendingIntent declinePi = PendingIntent.getService(this, 12, decline, pendingFlags());

        Notification.Builder b = baseBuilder(CHANNEL_INCOMING)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle("视频来电")
                .setContentText(info.peerName + " 邀请你视频通话")
                .setContentIntent(fullPi)
                .setFullScreenIntent(fullPi, true)
                .setCategory(Notification.CATEGORY_CALL)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(true)
                .setAutoCancel(false)
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_call),
                        "接听", answerPi).build())
                .addAction(new Notification.Action.Builder(
                        android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_call_end),
                        "拒绝", declinePi).build());
        if (Build.VERSION.SDK_INT >= 26) b.setTimeoutAfter(50000);
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE))
                    .notify(NOTIF_INCOMING, b.build());
        } catch (Exception ignored) {
        }
    }

    private void updateIncomingNotification() {
        if (incomingInfo != null) showIncomingNotification(incomingInfo);
    }

    private void cancelIncomingNotification() {
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).cancel(NOTIF_INCOMING);
        } catch (Exception ignored) {
        }
    }

    private void startRinging() {
        stopRinging();
        try {
            Uri uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE);
            if (uri != null) {
                ringtone = RingtoneManager.getRingtone(this, uri);
                if (Build.VERSION.SDK_INT >= 21) {
                    ringtone.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build());
                }
                if (Build.VERSION.SDK_INT >= 28) ringtone.setLooping(true);
                ringtone.play();
            }
        } catch (Exception ignored) {
        }
        try {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (vibrator != null && vibrator.hasVibrator()) {
                long[] pattern = {0, 800, 600, 800, 600, 800};
                if (Build.VERSION.SDK_INT >= 26) {
                    vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
                } else {
                    vibrator.vibrate(pattern, 0);
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void stopRinging() {
        if (ringtone != null) {
            try {
                ringtone.stop();
            } catch (Exception ignored) {
            }
            ringtone = null;
        }
    }

    private void stopVibrating() {
        if (vibrator != null) {
            try {
                vibrator.cancel();
            } catch (Exception ignored) {
            }
            vibrator = null;
        }
    }

    private void registerNetworkCallback() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                private final Runnable change = () -> {
                    if (session != null && !session.isEnded()) session.onNetworkChanged();
                    emitSnapshot();
                };

                @Override
                public void onAvailable(Network network) {
                    main.removeCallbacks(change);
                    main.postDelayed(change, 600);
                }

                @Override
                public void onLost(Network network) {
                    main.removeCallbacks(change);
                    main.postDelayed(change, 1200);
                }

                @Override
                public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                }
            };
            connectivityManager.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception e) {
            Log.w(TAG, "network callback", e);
        }
    }

    private void unregisterNetworkCallback() {
        try {
            if (connectivityManager != null && networkCallback != null) {
                connectivityManager.unregisterNetworkCallback(networkCallback);
            }
        } catch (Exception ignored) {
        }
    }
}
