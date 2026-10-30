package com.familycall.app.media;

import java.io.ByteArrayOutputStream;

/** Normalises H.264 access units to Annex-B start-code form for cross-device interop. */
public final class H264 {
    private H264() {
    }

    public static byte[] toAnnexB(byte[] data) {
        if (data == null || data.length < 5) return data;
        if (startsWithStartCode(data)) return data;
        int pos = 0;
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 16);
        while (pos + 4 <= data.length) {
            int len = ((data[pos] & 0xff) << 24) | ((data[pos + 1] & 0xff) << 16)
                    | ((data[pos + 2] & 0xff) << 8) | (data[pos + 3] & 0xff);
            if (len <= 0 || pos + 4 + len > data.length) return data;
            out.write(0);
            out.write(0);
            out.write(0);
            out.write(1);
            out.write(data, pos + 4, len);
            pos += 4 + len;
        }
        if (pos != data.length) return data;
        return out.toByteArray();
    }

    private static boolean startsWithStartCode(byte[] d) {
        if (d.length >= 4 && d[0] == 0 && d[1] == 0 && d[2] == 0 && d[3] == 1) return true;
        return d.length >= 3 && d[0] == 0 && d[1] == 0 && d[2] == 1;
    }
}
