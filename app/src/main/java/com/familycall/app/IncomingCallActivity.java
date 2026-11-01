package com.familycall.app;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.TextView;

import com.familycall.app.data.CallInfo;
import com.familycall.app.data.CallSnapshot;
import com.familycall.app.data.Prefs;
import com.familycall.app.ui.UiUtils;

public class IncomingCallActivity extends Activity implements CallService.UiListener {
    public static final String EXTRA_AUTO_ANSWER = "auto_answer";

    private CallService service;
    private boolean bound;
    private boolean answered;
    private boolean autoAnswer;
    private CallInfo info;
    private TextView nameView;
    private TextView avatarView;
    private TextView childBadge;
    private ImageButton answerButton;
    private ImageButton declineButton;
    private PowerManager.WakeLock wakeLock;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CallService.LocalBinder) binder).getService();
            bound = true;
            service.addUiListener(IncomingCallActivity.this);
            info = service.getIncomingInfo();
            if (info == null) {
                CallSnapshot s = service.getSnapshot();
                if (s.stage != CallSnapshot.Stage.RINGING) {
                    finish();
                    return;
                }
            }
            render();
            if (autoAnswer) answer();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            bound = false;
            finish();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        autoAnswer = getIntent().getBooleanExtra(EXTRA_AUTO_ANSWER, false);
        setContentView(R.layout.activity_incoming);
        acquireWake();
        nameView = findViewById(R.id.name);
        avatarView = findViewById(R.id.avatar);
        childBadge = findViewById(R.id.childBadge);
        answerButton = findViewById(R.id.answerButton);
        declineButton = findViewById(R.id.declineButton);
        answerButton.setOnClickListener(v -> answer());
        declineButton.setOnClickListener(v -> decline());
        applyImmersive();
        bindService(new Intent(this, CallService.class), connection, Context.BIND_AUTO_CREATE);
    }

    @SuppressWarnings("deprecation")
    private void acquireWake() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK
                        | PowerManager.ACQUIRE_CAUSES_WAKEUP
                        | PowerManager.ON_AFTER_RELEASE, "FamilyCall:incoming");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire(15000);
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWake() {
        if (wakeLock != null) {
            try {
                if (wakeLock.isHeld()) wakeLock.release();
            } catch (Exception ignored) {
            }
            wakeLock = null;
        }
    }

    private void applyImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void render() {
        if (info == null && service != null) info = service.getIncomingInfo();
        if (info == null) {
            finish();
            return;
        }
        String peer = info.peerName == null || info.peerName.isEmpty() ? "对方" : info.peerName;
        nameView.setText(peer);
        avatarView.setText(peer.substring(0, Math.min(2, peer.length())));
        childBadge.setVisibility(service != null && service.isChildMode() ? View.VISIBLE : View.GONE);
    }

    private void answer() {
        if (answered) return;
        if (service != null && service.answer()) {
            answered = true;
            finish();
        } else {
            autoAnswer = false;
            UiUtils.toastLong(this, "请先授予摄像头和麦克风权限，然后接听");
        }
    }

    private void decline() {
        if (service != null) {
            service.decline();
        }
        finish();
    }

    @Override
    public void onBackPressed() {
        decline();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) applyImmersive();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        autoAnswer = intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false);
        if (service != null) {
            info = service.getIncomingInfo();
            render();
            if (autoAnswer) answer();
        }
    }

    @Override
    protected void onDestroy() {
        releaseWake();
        if (bound) {
            if (service != null) service.removeUiListener(this);
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onSnapshot(CallSnapshot snapshot) {
        if (snapshot.stage != CallSnapshot.Stage.RINGING) {
            finish();
        }
    }

    @Override
    public void onIncoming(CallInfo info) {
        this.info = info;
        render();
    }

    @Override
    public void onIncomingCancelled() {
        finish();
    }

    @Override
    public void onCallEnded(String message) {
        finish();
    }

    @Override
    public void onServiceMessage(String message) {
        UiUtils.toast(this, message);
    }
}
