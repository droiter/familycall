package com.familycall.app.data;

public final class CallSnapshot {
    public enum Stage {
        IDLE, DIALING, RINGING, CONNECTING, ACTIVE, RECONNECTING, ENDED, FAILED
    }

    public Stage stage = Stage.IDLE;
    public String peerName = "";
    public String detail = "";
    public boolean incoming;
    public boolean childMode;
    public boolean muted;
    public boolean speakerOn;
    public boolean frontCamera = true;
    public long startedAtMs;
    public CallInfo info;
    public CallStats stats = new CallStats();
    public int peerRotation;
    public boolean peerMirror;
    public int localRotation;
    public boolean localMirror;
    public int localWidth = 640;
    public int localHeight = 480;
    public int peerWidth = 640;
    public int peerHeight = 480;

    public boolean isBusy() {
        return stage != Stage.IDLE && stage != Stage.ENDED && stage != Stage.FAILED;
    }

    public long durationMs() {
        if (startedAtMs <= 0) return 0;
        return Math.max(0, System.currentTimeMillis() - startedAtMs);
    }
}
