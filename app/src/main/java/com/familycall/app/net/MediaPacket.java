package com.familycall.app.net;

import java.nio.ByteBuffer;

/** Fixed-size UDP media header plus fragmentation helpers. */
public final class MediaPacket {
    public int magic;
    public byte type;
    public byte flags;
    public int streamId;
    public int seq;
    public int frameSeq;
    public int timestamp;
    public int fragIndex;
    public int fragCount;
    public int payloadLength;
    public int version;
    public byte[] payload;

    public static MediaPacket parse(byte[] data, int length) {
        if (length < Protocol.HEADER_SIZE) return null;
        ByteBuffer b = ByteBuffer.wrap(data, 0, length);
        int magic = b.getInt();
        if (magic != Protocol.MAGIC) return null;
        MediaPacket p = new MediaPacket();
        p.magic = magic;
        p.type = b.get();
        p.flags = b.get();
        p.streamId = b.getInt();
        p.seq = b.getInt();
        p.frameSeq = b.getInt();
        p.timestamp = b.getInt();
        p.fragIndex = b.getShort() & 0xffff;
        p.fragCount = b.getShort() & 0xffff;
        p.payloadLength = b.getShort() & 0xffff;
        p.version = b.get();
        int available = length - Protocol.HEADER_SIZE;
        int copy = Math.min(p.payloadLength, available);
        if (copy < 0) copy = 0;
        p.payload = new byte[copy];
        System.arraycopy(data, Protocol.HEADER_SIZE, p.payload, 0, copy);
        return p;
    }

    public static byte[] build(byte type, byte flags, int streamId, int seq, int frameSeq,
                               int timestamp, int fragIndex, int fragCount, byte[] payload,
                               int offset, int length) {
        byte[] out = new byte[Protocol.HEADER_SIZE + length];
        ByteBuffer b = ByteBuffer.wrap(out);
        b.putInt(Protocol.MAGIC);
        b.put(type);
        b.put(flags);
        b.putInt(streamId);
        b.putInt(seq);
        b.putInt(frameSeq);
        b.putInt(timestamp);
        b.putShort((short) fragIndex);
        b.putShort((short) fragCount);
        b.putShort((short) length);
        b.put((byte) Protocol.VERSION);
        if (payload != null && length > 0) b.put(payload, offset, length);
        return out;
    }

    public static byte[] feedback(int streamId, int videoHigh, int videoReceived, int videoLost,
                                  int audioHigh, int audioReceived, int audioLost,
                                  int rxVideoKbps, int rxAudioKbps, int jitterMs, int flags) {
        ByteBuffer b = ByteBuffer.allocate(32);
        b.putInt(videoHigh);
        b.putInt(videoReceived);
        b.putInt(videoLost);
        b.putInt(audioHigh);
        b.putInt(audioReceived);
        b.putInt(audioLost);
        b.putShort((short) Math.min(65535, Math.max(0, rxVideoKbps)));
        b.putShort((short) Math.min(65535, Math.max(0, rxAudioKbps)));
        b.putShort((short) Math.min(65535, Math.max(0, jitterMs)));
        b.putShort((short) flags);
        return build(Protocol.TYPE_FEEDBACK, (byte) 0, streamId, 0, 0, 0, 0, 1,
                b.array(), 0, b.array().length);
    }

    public static Feedback parseFeedback(MediaPacket p) {
        if (p == null || p.payload == null || p.payload.length < 32) return null;
        ByteBuffer b = ByteBuffer.wrap(p.payload);
        Feedback f = new Feedback();
        f.videoHigh = b.getInt();
        f.videoReceived = b.getInt();
        f.videoLost = b.getInt();
        f.audioHigh = b.getInt();
        f.audioReceived = b.getInt();
        f.audioLost = b.getInt();
        f.rxVideoKbps = b.getShort() & 0xffff;
        f.rxAudioKbps = b.getShort() & 0xffff;
        f.jitterMs = b.getShort() & 0xffff;
        f.flags = b.getShort() & 0xffff;
        return f;
    }

    public static final class Feedback {
        public int videoHigh;
        public int videoReceived;
        public int videoLost;
        public int audioHigh;
        public int audioReceived;
        public int audioLost;
        public int rxVideoKbps;
        public int rxAudioKbps;
        public int jitterMs;
        public int flags;
    }
}
