package com.familycall.app.net;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.familycall.app.media.H264;

public class ProtocolTest {
    @Test
    public void packetRoundTrip() {
        byte[] payload = new byte[Protocol.MAX_PAYLOAD];
        new Random(42).nextBytes(payload);
        byte[] raw = MediaPacket.build(Protocol.TYPE_VIDEO,
                (byte) (Protocol.FLAG_START | Protocol.FLAG_END | Protocol.FLAG_KEY),
                1234567, 42, 9, 90000, 0, 1, payload, 0, payload.length);
        MediaPacket p = MediaPacket.parse(raw, raw.length);
        assertNotNull(p);
        assertEquals(Protocol.TYPE_VIDEO, p.type);
        assertEquals(1234567, p.streamId);
        assertEquals(42, p.seq);
        assertEquals(9, p.frameSeq);
        assertEquals(90000, p.timestamp);
        assertEquals(1, p.fragCount);
        assertArrayEquals(payload, p.payload);
    }

    @Test
    public void feedbackRoundTrip() {
        int sid = Protocol.streamId("abc");
        byte[] raw = MediaPacket.feedback(sid, 900, 880, 20, 400, 399, 1,
                720, 32, 8, Protocol.FEEDBACK_KEYFRAME);
        MediaPacket p = MediaPacket.parse(raw, raw.length);
        MediaPacket.Feedback f = MediaPacket.parseFeedback(p);
        assertNotNull(f);
        assertEquals(20, f.videoLost);
        assertEquals(720, f.rxVideoKbps);
        assertEquals(8, f.jitterMs);
        assertTrue((f.flags & Protocol.FEEDBACK_KEYFRAME) != 0);
    }

    @Test
    public void fragmentedFrameReassembles() {
        byte[] frame = new byte[5000];
        new Random(1).nextBytes(frame);
        int count = (frame.length + Protocol.MAX_PAYLOAD - 1) / Protocol.MAX_PAYLOAD;
        TreeMap<Integer, byte[]> pieces = new TreeMap<>();
        for (int i = count - 1; i >= 0; i--) {
            int offset = i * Protocol.MAX_PAYLOAD;
            int length = Math.min(Protocol.MAX_PAYLOAD, frame.length - offset);
            byte flags = (byte) ((i == 0 ? Protocol.FLAG_START : 0)
                    | (i == count - 1 ? Protocol.FLAG_END : 0)
                    | Protocol.FLAG_KEY);
            byte[] raw = MediaPacket.build(Protocol.TYPE_VIDEO, flags, 7, i + 1, 5,
                    1234, i, count, frame, offset, length);
            MediaPacket p = MediaPacket.parse(raw, raw.length);
            pieces.put(p.fragIndex, p.payload);
        }
        byte[] out = new byte[frame.length];
        int offset = 0;
        for (byte[] piece : pieces.values()) {
            System.arraycopy(piece, 0, out, offset, piece.length);
            offset += piece.length;
        }
        assertArrayEquals(frame, out);
    }

    @Test
    public void codecConfigRoundTrip() {
        byte[] a = {0, 0, 0, 1, 0x67, 0x42, 0, 0x1e};
        byte[] b = {0, 0, 0, 1, 0x68, (byte) 0xce, 0x3c, (byte) 0x80};
        List<byte[]> unpacked = CodecConfig.unpack(CodecConfig.pack(Arrays.asList(a, b)));
        assertEquals(2, unpacked.size());
        assertArrayEquals(a, unpacked.get(0));
        assertArrayEquals(b, unpacked.get(1));
    }

    @Test
    public void h264LengthPrefixIsConverted() {
        byte[] frame = {0, 0, 0, 3, 0x65, 0x11, 0x22, 0, 0, 0, 2, 0x41, 0x33};
        byte[] annexB = H264.toAnnexB(frame);
        assertArrayEquals(new byte[]{0, 0, 0, 1, 0x65, 0x11, 0x22,
                0, 0, 0, 1, 0x41, 0x33}, annexB);
        byte[] already = {0, 0, 1, 0x65, 1, 2, 3};
        assertArrayEquals(already, H264.toAnnexB(already));
    }

    @Test
    public void congestionControlBacksOffAndRecovers() {
        AdaptiveBitrate abr = new AdaptiveBitrate(800, 200, 1500);
        int before = abr.getTargetKbps();
        assertTrue(abr.update(15f, 500, 10000));
        assertTrue(abr.getTargetKbps() < before);
        int low = abr.getTargetKbps();
        for (int i = 0; i < 20; i++) {
            abr.update(0f, 1000, 20000 + i * 2000L);
        }
        assertTrue(abr.getTargetKbps() > low);
    }
}

