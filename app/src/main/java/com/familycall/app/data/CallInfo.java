package com.familycall.app.data;

public final class CallInfo {
    public final String callId;
    public final String peerName;
    public final String host;
    public final int port;
    public final boolean incoming;
    public final String contactId;

    public CallInfo(String callId, String peerName, String host, int port,
                    boolean incoming, String contactId) {
        this.callId = callId;
        this.peerName = peerName;
        this.host = host;
        this.port = port;
        this.incoming = incoming;
        this.contactId = contactId;
    }
}
