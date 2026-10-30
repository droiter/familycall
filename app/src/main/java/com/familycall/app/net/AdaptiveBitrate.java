package com.familycall.app.net;

/**
 * Small loss-driven congestion controller. It raises the target slowly when a
 * feedback window is clean and backs off quickly on loss, which keeps latency
 * bounded on cellular links.
 */
public final class AdaptiveBitrate {
    private final int minKbps;
    private final int maxKbps;
    private int targetKbps;
    private long lastChangeMs;
    private int goodWindows;

    public AdaptiveBitrate(int startKbps, int minKbps, int maxKbps) {
        this.targetKbps = clamp(startKbps, minKbps, maxKbps);
        this.minKbps = minKbps;
        this.maxKbps = maxKbps;
    }

    public synchronized int getTargetKbps() {
        return targetKbps;
    }

    public synchronized int getMaxKbps() {
        return maxKbps;
    }

    /** @return true when the encoder bitrate should be changed. */
    public synchronized boolean update(float lossPercent, int receivedKbps, long nowMs) {
        if (nowMs - lastChangeMs < 1500) return false;
        int old = targetKbps;
        if (lossPercent >= 12f) {
            targetKbps = (int) (targetKbps * 0.65f);
            goodWindows = 0;
        } else if (lossPercent >= 5f) {
            targetKbps = (int) (targetKbps * 0.82f);
            goodWindows = 0;
        } else if (lossPercent >= 1.5f) {
            targetKbps = (int) (targetKbps * 0.94f);
            goodWindows = 0;
        } else if (lossPercent < 0.8f) {
            goodWindows++;
            if (goodWindows >= 2 && (receivedKbps <= 0 || receivedKbps > targetKbps * 0.75f)) {
                targetKbps = (int) (targetKbps * 1.08f) + 8;
                goodWindows = 0;
            }
        }
        targetKbps = clamp(targetKbps, minKbps, maxKbps);
        if (targetKbps != old) {
            lastChangeMs = nowMs;
            return true;
        }
        return false;
    }

    public synchronized void force(int kbps) {
        targetKbps = clamp(kbps, minKbps, maxKbps);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
