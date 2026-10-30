package com.familycall.app.net;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Locale;
import java.util.UUID;

/** Wire constants and small helpers shared by signalling and media. */
public final class Protocol {
    public static final int VERSION = 1;
    public static final int MAGIC = 0x56434D31; // "VCM1"

    public static final byte TYPE_VIDEO = 1;
    public static final byte TYPE_AUDIO = 2;
    public static final byte TYPE_FEEDBACK = 3;
    public static final byte TYPE_PROBE = 4;
    public static final byte TYPE_BYE = 5;

    public static final byte FLAG_START = 1;
    public static final byte FLAG_END = 1 << 1;
    public static final byte FLAG_KEY = 1 << 2;
    public static final byte FLAG_CONFIG = 1 << 3;

    public static final int FEEDBACK_KEYFRAME = 1;
    public static final int FEEDBACK_CONFIG = 1 << 1;

    public static final int HEADER_SIZE = 29;
    public static final int MAX_PAYLOAD = 1150;
    public static final int MAX_DATAGRAM = 1400;

    private Protocol() {
    }

    public static String newCallId() {
        return UUID.randomUUID().toString();
    }

    public static int streamId(String callId) {
        long h = 1125899906842597L;
        for (int i = 0; i < callId.length(); i++) h = 31 * h + callId.charAt(i);
        return (int) (h ^ (h >>> 32));
    }

    public static boolean isSequenceNewer(int candidate, int reference) {
        return candidate != reference && (candidate - reference) > 0;
    }

    public static int mediaTimestampUsToInt(long ptsUs, int rate) {
        return (int) ((ptsUs * rate / 1_000_000L) & 0x7fffffffL);
    }

    public static String localIpv4() {
        String v4 = firstAddress(true);
        if (v4 != null) return v4;
        String v6 = firstAddress(false);
        return v6 != null ? v6 : "未连接";
    }

    private static String firstAddress(boolean wantV4) {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (wantV4 && a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String host = a.getHostAddress();
                        if (host != null && !host.startsWith("169.254.")) return host;
                    }
                    if (!wantV4 && a instanceof Inet6Address && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress() && !a.isSiteLocalAddress()) {
                        return "[" + a.getHostAddress() + "]";
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    public static String localIpv4Detailed() {
        StringBuilder sb = new StringBuilder();
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while (ifaces != null && ifaces.hasMoreElements()) {
                NetworkInterface nif = ifaces.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String host = a.getHostAddress();
                        if (host == null || host.startsWith("169.254.")) continue;
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(nif.getDisplayName()).append(": ").append(host);
                    } else if (a instanceof Inet6Address && !a.isLoopbackAddress()
                            && !a.isLinkLocalAddress() && !a.isSiteLocalAddress()) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(nif.getDisplayName()).append(" IPv6: [")
                                .append(a.getHostAddress()).append("]");
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() == 0 ? "未连接" : sb.toString();
    }

    public static String formatDuration(long ms) {
        long total = Math.max(0, ms) / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        if (h > 0) return String.format(Locale.US, "%d:%02d:%02d", h, m, s);
        return String.format(Locale.US, "%02d:%02d", m, s);
    }
}
