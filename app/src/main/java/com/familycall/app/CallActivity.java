package com.familycall.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.Surface;
import android.view.TextureView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.familycall.app.data.CallSnapshot;
import com.familycall.app.data.CallStats;
import com.familycall.app.data.Prefs;
import com.familycall.app.ui.UiUtils;

public class CallActivity extends Activity implements CallService.UiListener {
    private CallService service;
    private boolean bound;
    private CallSnapshot snapshot;
    private boolean childMode;
    private String callId;
    private boolean endingScheduled;
    private boolean statsVisible;
    private Prefs prefs;
    private int lastPeerW = -1, lastPeerH = -1, lastPeerRot = -1;
    private boolean lastPeerMirror;
    private int lastLocalW = -1, lastLocalH = -1;
    private boolean lastLocalMirror;
    private int lastLocalRot = -1;
    private final Handler main = new Handler(Looper.getMainLooper());

    private TextureView remoteVideo;
    private TextureView localVideo;
    private FrameLayout remoteContainer;
    private FrameLayout localPreviewContainer;
    private Surface remoteSurface;
    private SurfaceTexture remoteTexture;
    private SurfaceTexture localTexture;

    private LinearLayout topBar;
    private LinearLayout controlBar;
    private View topFade;
    private View bottomFade;
    private TextView peerName;
    private TextView callStatus;
    private TextView statsPanel;
    private LinearLayout statusOverlay;
    private TextView statusOverlayText;
    private TextView childCancelButton;
    private LinearLayout endingOverlay;
    private TextView endingTitle;
    private TextView endingDetail;
    private ImageButton muteButton;
    private ImageButton speakerButton;
    private ImageButton statsButton;
    private TextView muteLabel;
    private TextView speakerLabel;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (snapshot != null && snapshot.stage == CallSnapshot.Stage.ACTIVE) {
                String detail = snapshot.detail;
                if (detail != null && (detail.contains("网络") || detail.contains("恢复"))) {
                    callStatus.setText(detail + " · " + UiUtils.duration(snapshot.durationMs()));
                } else {
                    callStatus.setText("已连接 · " + UiUtils.duration(snapshot.durationMs()));
                }
            }
            main.postDelayed(this, 1000);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((CallService.LocalBinder) binder).getService();
            bound = true;
            service.addUiListener(CallActivity.this);
            applySnapshot(service.getSnapshot());
            attachSurfaces();
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
        setVolumeControlStream(android.media.AudioManager.STREAM_VOICE_CALL);
        prefs = new Prefs(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        setContentView(R.layout.activity_call);
        remoteVideo = findViewById(R.id.remoteVideo);
        localVideo = findViewById(R.id.localVideo);
        remoteContainer = findViewById(R.id.remoteContainer);
        localPreviewContainer = findViewById(R.id.localPreviewContainer);
        topBar = findViewById(R.id.topBar);
        controlBar = findViewById(R.id.controlBar);
        topFade = findViewById(R.id.topFade);
        bottomFade = findViewById(R.id.bottomFade);
        peerName = findViewById(R.id.peerName);
        callStatus = findViewById(R.id.callStatus);
        statsPanel = findViewById(R.id.statsPanel);
        statusOverlay = findViewById(R.id.statusOverlay);
        statusOverlayText = findViewById(R.id.statusOverlayText);
        childCancelButton = findViewById(R.id.childCancelButton);
        endingOverlay = findViewById(R.id.endingOverlay);
        endingTitle = findViewById(R.id.endingTitle);
        endingDetail = findViewById(R.id.endingDetail);
        muteButton = findViewById(R.id.muteButton);
        speakerButton = findViewById(R.id.speakerButton);
        statsButton = findViewById(R.id.statsButton);
        muteLabel = findViewById(R.id.muteLabel);
        speakerLabel = findViewById(R.id.speakerLabel);

        setupSurfaceListeners();
        setupButtons();
        bindService(new Intent(this, CallService.class), connection, Context.BIND_AUTO_CREATE);
        main.postDelayed(ticker, 1000);
        main.postDelayed(() -> {
            if (!bound) {
                UiUtils.toast(this, "通话服务连接失败");
                finish();
            }
        }, 5000);
    }

    private void setupSurfaceListeners() {
        remoteVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                remoteTexture = st;
                st.setDefaultBufferSize(1280, 720);
                remoteSurface = new Surface(st);
                remoteVideo.setVisibility(View.VISIBLE);
                if (service != null && callId != null) service.attachRemote(callId, remoteSurface);
                applyRemoteTransform();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                applyRemoteTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (service != null && callId != null) service.detachRemote(callId);
                if (remoteSurface != null) {
                    try {
                        remoteSurface.release();
                    } catch (Exception ignored) {
                    }
                    remoteSurface = null;
                }
                remoteTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
        localVideo.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
                localTexture = st;
                if (service != null && callId != null) service.attachPreview(callId, st);
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
                applyLocalTransform();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                if (service != null && callId != null) service.detachPreview(callId);
                localTexture = null;
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) {
            }
        });
    }

    private void setupButtons() {
        muteButton.setOnClickListener(v -> {
            if (service == null || snapshot == null || childMode) return;
            service.setMuted(!snapshot.muted);
        });
        speakerButton.setOnClickListener(v -> {
            if (service == null || snapshot == null || childMode) return;
            service.setSpeaker(!snapshot.speakerOn);
            UiUtils.toast(this, getString(snapshot.speakerOn ? R.string.speaker_off : R.string.speaker_on));
        });
        findViewById(R.id.switchButton).setOnClickListener(v -> {
            if (service == null || childMode) return;
            service.switchCamera();
        });
        statsButton.setOnClickListener(v -> {
            statsVisible = !statsVisible;
            statsPanel.setVisibility(statsVisible ? View.VISIBLE : View.GONE);
            renderStats();
        });
        findViewById(R.id.hangupButton).setOnClickListener(v -> {
            if (childMode) return;
            if (service != null) service.hangup();
            finish();
        });
        childCancelButton.setOnClickListener(v -> {
            if (service != null) service.cancelOutgoing();
            finish();
        });
        localPreviewContainer.setOnClickListener(v -> rotateLocalPreview());
    }

    private void attachSurfaces() {
        if (service == null || callId == null) return;
        if (remoteSurface != null) service.attachRemote(callId, remoteSurface);
        if (localTexture != null) service.attachPreview(callId, localTexture);
    }

    @Override
    public void onSnapshot(CallSnapshot s) {
        applySnapshot(s);
    }

    private void applySnapshot(CallSnapshot s) {
        if (s == null) return;
        snapshot = s;
        if (s.info != null) callId = s.info.callId;
        boolean child = s.childMode;
        if (child != childMode) {
            childMode = child;
            applyChildMode();
        }
        if (childMode && !bound) applyChildMode();

        if (s.stage == CallSnapshot.Stage.ENDED || s.stage == CallSnapshot.Stage.FAILED
                || s.stage == CallSnapshot.Stage.IDLE) {
            showEnding(s.stage == CallSnapshot.Stage.FAILED, s.detail);
            return;
        }

        peerName.setText(s.peerName == null || s.peerName.isEmpty() ? "对方" : s.peerName);
        renderButtons(s);
        renderStats();
        renderStage(s);
        applyRemoteTransform();
        applyLocalTransform();
        attachSurfaces();
    }

    private void renderStage(CallSnapshot s) {
        if (childMode) {
            // Child mode keeps the video full screen; only status text while connecting.
            if (s.stage == CallSnapshot.Stage.ACTIVE) {
                statusOverlay.setVisibility(View.GONE);
            } else {
                statusOverlay.setVisibility(View.VISIBLE);
                statusOverlayText.setText(stageText(s));
                boolean canCancel = !s.incoming && s.stage == CallSnapshot.Stage.DIALING;
                childCancelButton.setVisibility(canCancel ? View.VISIBLE : View.GONE);
            }
            callStatus.setVisibility(View.GONE);
            return;
        }
        switch (s.stage) {
            case DIALING:
                statusOverlay.setVisibility(View.VISIBLE);
                statusOverlayText.setText("正在呼叫…");
                callStatus.setText("正在连接对方");
                break;
            case CONNECTING:
                statusOverlay.setVisibility(View.VISIBLE);
                statusOverlayText.setText("正在接通…");
                callStatus.setText("正在接通");
                break;
            case RECONNECTING:
                statusOverlay.setVisibility(View.VISIBLE);
                statusOverlayText.setText("网络波动，正在重连…");
                callStatus.setText("网络波动");
                break;
            case ACTIVE:
                statusOverlay.setVisibility(View.GONE);
                callStatus.setText("已连接 · " + UiUtils.duration(s.durationMs()));
                break;
            default:
                break;
        }
    }

    private String stageText(CallSnapshot s) {
        switch (s.stage) {
            case DIALING:
                return "正在呼叫…";
            case CONNECTING:
                return "正在接通…";
            case RECONNECTING:
                return "网络恢复中…";
            case ACTIVE:
                return "通话中";
            default:
                return "";
        }
    }

    private void renderButtons(CallSnapshot s) {
        if (s.muted) {
            muteButton.setImageResource(R.drawable.ic_mic_off);
            muteButton.setBackgroundResource(R.drawable.bg_control_glass_on);
            UiUtils.tint(muteButton, getColor(R.color.ink));
            muteLabel.setText("取消静音");
        } else {
            muteButton.setImageResource(R.drawable.ic_mic);
            muteButton.setBackgroundResource(R.drawable.bg_control_glass);
            UiUtils.tint(muteButton, getColor(R.color.white));
            muteLabel.setText(R.string.mute);
        }
        if (s.speakerOn) {
            speakerButton.setImageResource(R.drawable.ic_volume);
            speakerButton.setBackgroundResource(R.drawable.bg_control_glass_on);
            UiUtils.tint(speakerButton, getColor(R.color.ink));
            speakerLabel.setText(R.string.speaker);
        } else {
            speakerButton.setImageResource(R.drawable.ic_volume_off);
            speakerButton.setBackgroundResource(R.drawable.bg_control_glass);
            UiUtils.tint(speakerButton, getColor(R.color.white));
            speakerLabel.setText(R.string.earpiece);
        }
    }

    private void renderStats() {
        if (snapshot == null) return;
        if (!statsVisible) {
            statsPanel.setVisibility(View.GONE);
            return;
        }
        statsPanel.setVisibility(View.VISIBLE);
        CallStats st = snapshot.stats;
        if (st == null) st = new CallStats();
        int peerW = snapshot.peerWidth;
        int peerH = snapshot.peerHeight;
        statsPanel.setText("画面  " + peerW + "x" + peerH
                + "\n编码  " + st.targetKbps + " kbps · " + String.format(java.util.Locale.US, "%.1f", st.fps) + " fps"
                + "\n接收  " + st.videoKbps + " kbps"
                + "\n丢包  " + String.format(java.util.Locale.US, "%.1f", st.lossPercent) + "%"
                + "\n往返  " + (st.rttMs > 0 ? st.rttMs + " ms" : "—")
                + "\n网络  " + st.network);
    }

    private void applyRemoteTransform() {
        if (snapshot == null) return;
        int w = remoteContainer.getWidth();
        int h = remoteContainer.getHeight();
        if (w <= 0 || h <= 0) {
            remoteContainer.post(this::applyRemoteTransform);
            return;
        }
        int vw = snapshot.peerWidth > 0 ? snapshot.peerWidth : 640;
        int vh = snapshot.peerHeight > 0 ? snapshot.peerHeight : 480;
        int rot = snapshot.peerRotation;
        boolean mirror = snapshot.peerMirror;
        if (vw == lastPeerW && vh == lastPeerH && rot == lastPeerRot && mirror == lastPeerMirror) return;
        lastPeerW = vw;
        lastPeerH = vh;
        lastPeerRot = rot;
        lastPeerMirror = mirror;
        UiUtils.layoutRotatedTexture(remoteVideo, w, h, vw, vh, rot, mirror);
    }

    private void applyLocalTransform() {
        if (snapshot == null || childMode) return;
        int w = localPreviewContainer.getWidth();
        int h = localPreviewContainer.getHeight();
        if (w <= 0 || h <= 0) {
            localPreviewContainer.post(this::applyLocalTransform);
            return;
        }
        int vw = snapshot.localWidth > 0 ? snapshot.localWidth : 640;
        int vh = snapshot.localHeight > 0 ? snapshot.localHeight : 480;
        int offset = effectivePreviewOffset();
        int rot = ((snapshot.localRotation + offset) % 360 + 360) % 360;
        boolean mirror = snapshot.localMirror;
        if (vw == lastLocalW && vh == lastLocalH && rot == lastLocalRot && mirror == lastLocalMirror) return;
        lastLocalW = vw;
        lastLocalH = vh;
        lastLocalRot = rot;
        lastLocalMirror = mirror;
        UiUtils.layoutRotatedTexture(localVideo, w, h, vw, vh, rot, mirror);
    }

    private int effectivePreviewOffset() {
        if (snapshot == null || !snapshot.localMirror) return 0;
        int saved = prefs == null ? -1 : prefs.getPreviewOffset();
        if (saved >= 0) return saved;
        // Phone front cameras frequently need one extra quarter turn for the
        // self-view even when the sensor orientation is reported correctly.
        // Gravity still drives the base rotation; this is only a local preview
        // correction and can be cycled by tapping the small preview.
        return isPhone() ? 90 : 0;
    }

    private boolean isPhone() {
        return getResources().getConfiguration().smallestScreenWidthDp < 600;
    }

    private void rotateLocalPreview() {
        if (childMode || snapshot == null) return;
        if (!snapshot.localMirror) {
            UiUtils.toast(this, "后置摄像头使用自动方向，无需校正");
            return;
        }
        int next = (effectivePreviewOffset() + 90) % 360;
        prefs.setPreviewOffset(next);
        lastLocalRot = -1;
        applyLocalTransform();
        UiUtils.toast(this, "本机预览已旋转 " + next + "°");
    }

    private void applyChildMode() {
        int visibility = childMode ? View.GONE : View.VISIBLE;
        topBar.setVisibility(visibility);
        controlBar.setVisibility(visibility);
        localPreviewContainer.setVisibility(visibility);
        topFade.setVisibility(visibility);
        bottomFade.setVisibility(visibility);
        statsButton.setVisibility(visibility);
        if (childMode) {
            statsPanel.setVisibility(View.GONE);
            statusOverlay.setVisibility(snapshot != null
                    && snapshot.stage == CallSnapshot.Stage.ACTIVE ? View.GONE : View.VISIBLE);
            childCancelButton.setVisibility(View.GONE);
            enterImmersive();
        } else {
            exitImmersive();
        }
    }

    private void enterImmersive() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void exitImmersive() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void showEnding(boolean failed, String detail) {
        if (endingScheduled) return;
        endingScheduled = true;
        endingOverlay.setVisibility(View.VISIBLE);
        endingTitle.setText(failed ? "通话未接通" : "通话已结束");
        endingDetail.setText(detail == null ? "" : detail);
        controlBar.setVisibility(View.GONE);
        topBar.setVisibility(View.GONE);
        localPreviewContainer.setVisibility(View.GONE);
        statusOverlay.setVisibility(View.GONE);
        statsPanel.setVisibility(View.GONE);
        childCancelButton.setVisibility(View.GONE);
        main.postDelayed(this::finish, 2600);
    }

    @Override
    public void onBackPressed() {
        if (childMode) {
            return;
        }
        if (endingScheduled) {
            finish();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.end_call_title)
                .setMessage(R.string.end_call_message)
                .setPositiveButton(R.string.confirm, (d, w) -> {
                    if (service != null) service.hangup();
                    finish();
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && childMode) enterImmersive();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (service != null) service.refreshCameraOrientation();
        lastPeerW = -1;
        lastPeerH = -1;
        lastPeerRot = -1;
        lastLocalW = -1;
        lastLocalH = -1;
        lastLocalRot = -1;
        remoteContainer.post(this::applyRemoteTransform);
        localPreviewContainer.post(this::applyLocalTransform);
        if (childMode) enterImmersive();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacksAndMessages(null);
        if (bound) {
            if (service != null) {
                service.removeUiListener(this);
                if (callId != null) {
                    service.detachRemote(callId);
                    service.detachPreview(callId);
                }
            }
            try {
                unbindService(connection);
            } catch (Exception ignored) {
            }
            bound = false;
        }
        if (remoteSurface != null) {
            try {
                remoteSurface.release();
            } catch (Exception ignored) {
            }
            remoteSurface = null;
        }
        super.onDestroy();
    }

    @Override
    public void onCallEnded(String message) {
        showEnding(false, message);
    }

    @Override
    public void onIncoming(com.familycall.app.data.CallInfo info) {
    }

    @Override
    public void onIncomingCancelled() {
    }

    @Override
    public void onServiceMessage(String message) {
        UiUtils.toast(this, message);
    }
}
