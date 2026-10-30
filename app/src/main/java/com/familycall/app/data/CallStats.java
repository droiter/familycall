package com.familycall.app.data;

public final class CallStats {
    public int videoKbps;
    public int audioKbps;
    public float fps;
    public float lossPercent;
    public int rttMs;
    public int width;
    public int height;
    public int targetKbps;
    public String network = "未知";
    public long lastMediaAgeMs = -1;

    public String compact() {
        return "画面 " + width + "x" + height + " · " + targetKbps + "kbps\n"
                + "帧率 " + String.format(java.util.Locale.US, "%.1f", fps) + " fps\n"
                + "丢包 " + String.format(java.util.Locale.US, "%.1f", lossPercent) + "%\n"
                + "往返 " + (rttMs > 0 ? rttMs + " ms" : "—") + "\n"
                + "网络 " + network;
    }
}
