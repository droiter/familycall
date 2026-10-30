package com.familycall.app.net;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/** Serialises H.264/AAC csd buffers for the custom media protocol. */
public final class CodecConfig {
    private CodecConfig() {
    }

    public static byte[] pack(List<byte[]> parts) {
        int size = 4;
        for (byte[] p : parts) size += 4 + (p == null ? 0 : p.length);
        ByteBuffer b = ByteBuffer.allocate(size);
        b.putInt(parts.size());
        for (byte[] p : parts) {
            int len = p == null ? 0 : p.length;
            b.putInt(len);
            if (len > 0) b.put(p);
        }
        return b.array();
    }

    public static List<byte[]> unpack(byte[] raw) {
        List<byte[]> out = new ArrayList<>();
        if (raw == null || raw.length < 4) return out;
        try {
            ByteBuffer b = ByteBuffer.wrap(raw);
            int count = b.getInt();
            if (count < 0 || count > 8) return out;
            for (int i = 0; i < count; i++) {
                if (b.remaining() < 4) break;
                int len = b.getInt();
                if (len < 0 || len > b.remaining()) break;
                byte[] p = new byte[len];
                b.get(p);
                out.add(p);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static byte[] fromBuffer(ByteBuffer bb) {
        if (bb == null) return null;
        ByteBuffer dup = bb.duplicate();
        byte[] out = new byte[dup.remaining()];
        dup.get(out);
        return out;
    }
}
