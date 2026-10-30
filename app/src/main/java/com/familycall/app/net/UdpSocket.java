package com.familycall.app.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;

/** Rebindable UDP socket; rebinding is what lets a call survive Wi-Fi/cellular switches. */
public final class UdpSocket {
    private final int localPort;
    private volatile DatagramSocket socket;
    private volatile InetSocketAddress peer;

    public UdpSocket(int localPort) {
        this.localPort = localPort;
    }

    public synchronized void open() throws SocketException {
        closeSocket();
        DatagramSocket s = new DatagramSocket(null);
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(localPort));
        s.setSoTimeout(500);
        s.setReceiveBufferSize(1024 * 1024);
        s.setSendBufferSize(1024 * 1024);
        socket = s;
    }

    public synchronized void reopen() throws SocketException {
        InetSocketAddress p = peer;
        open();
        peer = p;
    }

    public DatagramSocket get() {
        return socket;
    }

    public void setPeer(InetSocketAddress address) {
        peer = address;
    }

    public InetSocketAddress getPeer() {
        return peer;
    }

    public boolean send(byte[] data, int length) {
        DatagramSocket s = socket;
        InetSocketAddress p = peer;
        if (s == null || p == null) return false;
        try {
            s.send(new DatagramPacket(data, length, p));
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public DatagramPacket receive(byte[] buffer) throws IOException {
        DatagramSocket s = socket;
        if (s == null) throw new SocketException("socket closed");
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        s.receive(packet);
        return packet;
    }

    public synchronized void close() {
        closeSocket();
    }

    private void closeSocket() {
        DatagramSocket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }
}
