import com.familycall.app.net.*;
import java.util.*;

public final class ProtocolSelfTest {
    static int checks;
    static void check(boolean ok, String what) {
        checks++;
        if (!ok) throw new AssertionError("FAIL: " + what);
    }
    public static void main(String[] args) {
        String callId = Protocol.newCallId();
        int sid = Protocol.streamId(callId);
        byte[] payload = new byte[Protocol.MAX_PAYLOAD];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i * 7);
        byte[] raw = MediaPacket.build(Protocol.TYPE_VIDEO,
                (byte)(Protocol.FLAG_START | Protocol.FLAG_END | Protocol.FLAG_KEY),
                sid, 42, 7, 90000, 0, 1, payload, 0, payload.length);
        check(raw.length == Protocol.HEADER_SIZE + payload.length, "packet size");
        MediaPacket p = MediaPacket.parse(raw, raw.length);
        check(p != null, "parse");
        check(p.type == Protocol.TYPE_VIDEO && p.seq == 42 && p.frameSeq == 7, "header fields");
        check(p.timestamp == 90000 && p.fragIndex == 0 && p.fragCount == 1, "frame fields");
        check(p.payloadLength == payload.length && Arrays.equals(p.payload, payload), "payload");

        byte[] fb = MediaPacket.feedback(sid, 500, 480, 20, 200, 200, 0, 850, 32, 12,
                Protocol.FEEDBACK_KEYFRAME);
        MediaPacket fp = MediaPacket.parse(fb, fb.length);
        check(fp != null && fp.type == Protocol.TYPE_FEEDBACK, "feedback type");
        MediaPacket.Feedback f = MediaPacket.parseFeedback(fp);
        check(f != null && f.videoLost == 20 && f.rxVideoKbps == 850 && f.jitterMs == 12, "feedback fields");
        check((f.flags & Protocol.FEEDBACK_KEYFRAME) != 0, "feedback keyframe flag");

        // Fragment a frame and reassemble in reverse order.
        int target = 4000;
        byte[] frame = new byte[target];
        new Random(7).nextBytes(frame);
        int count = (target + Protocol.MAX_PAYLOAD - 1) / Protocol.MAX_PAYLOAD;
        TreeMap<Integer, byte[]> parts = new TreeMap<>();
        for (int i = 0; i < count; i++) {
            int off = i * Protocol.MAX_PAYLOAD;
            int len = Math.min(Protocol.MAX_PAYLOAD, target - off);
            byte flags = (byte) ((i == 0 ? Protocol.FLAG_START : 0)
                    | (i == count - 1 ? Protocol.FLAG_END : 0)
                    | Protocol.FLAG_KEY);
            byte[] pk = MediaPacket.build(Protocol.TYPE_VIDEO, flags, sid, i + 1, 8, 123456,
                    i, count, frame, off, len);
            MediaPacket mp = MediaPacket.parse(pk, pk.length);
            parts.put(mp.fragIndex, mp.payload);
        }
        byte[] rejoined = new byte[target];
        int off = 0;
        for (byte[] part : parts.values()) {
            System.arraycopy(part, 0, rejoined, off, part.length);
            off += part.length;
        }
        check(off == target && Arrays.equals(rejoined, frame), "reassembly");

        // Codec config roundtrip.
        byte[] c0 = {0,0,0,1,0x67,0x42,0,0x1e};
        byte[] c1 = {0,0,0,1,0x68,(byte)0xce,0x3c,(byte)0x80};
        byte[] packed = CodecConfig.pack(Arrays.asList(c0, c1));
        List<byte[]> unpacked = CodecConfig.unpack(packed);
        check(unpacked.size() == 2 && Arrays.equals(unpacked.get(0), c0), "csd roundtrip");

        // Congestion control should back off on loss and recover eventually.
        AdaptiveBitrate abr = new AdaptiveBitrate(800, 200, 1500);
        check(abr.getTargetKbps() == 800, "abr start");
        abr.update(15f, 500, 10000);
        check(abr.getTargetKbps() < 800, "abr cuts on loss");
        int low = abr.getTargetKbps();
        for (int i = 0; i < 10; i++) abr.update(0f, 900, 20000 + i * 2000L);
        check(abr.getTargetKbps() > low, "abr recovers");

        // Sequence wrap helper must treat wrapped values as newer.
        check(Protocol.isSequenceNewer(Integer.MIN_VALUE, Integer.MAX_VALUE), "sequence wrap");

        System.out.println("ProtocolSelfTest OK: " + checks + " checks");
    }
}
