package com.familycall.app.net;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Newline-delimited JSON control channel over a direct TCP connection.
 * The heartbeat doubles as an RTT probe (2 s interval, 10 s timeout).
 */
public final class ControlLink {
    private static final String TAG = "ControlLink";

    public interface Listener {
        void onControlMessage(ControlLink link, JSONObject message);

        void onControlClosed(ControlLink link, String reason);
    }

    private final Socket socket;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private volatile BufferedWriter writer;
    private volatile BufferedReader reader;
    private volatile long lastReceiveMs;
    private volatile int rttMs;
    private Thread readerThread;
    private Thread heartbeatThread;

    public ControlLink(Socket socket, Listener listener) {
        this.socket = socket;
        this.listener = listener;
        this.lastReceiveMs = System.currentTimeMillis();
        try {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(0);
        } catch (Exception ignored) {
        }
    }

    public int getRttMs() {
        return rttMs;
    }

    public void start() {
        try {
            writer = new BufferedWriter(new OutputStreamWriter(
                    socket.getOutputStream(), StandardCharsets.UTF_8));
            reader = new BufferedReader(new InputStreamReader(
                    socket.getInputStream(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            closeInternal("控制连接不可用");
            return;
        }
        readerThread = new Thread(this::readLoop, "control-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        heartbeatThread = new Thread(this::heartbeatLoop, "control-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    public boolean send(JSONObject message) {
        if (closed.get()) return false;
        final String line = message.toString();
        synchronized (this) {
            try {
                if (writer == null) return false;
                writer.write(line);
                writer.write('\n');
                writer.flush();
                return true;
            } catch (IOException e) {
                closeInternal("控制连接已断开");
                return false;
            }
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                String line = reader.readLine();
                if (line == null) {
                    closeInternal("对方已断开控制连接");
                    return;
                }
                lastReceiveMs = System.currentTimeMillis();
                if (line.isEmpty() || line.length() > 64 * 1024) continue;
                try {
                    JSONObject obj = new JSONObject(line);
                    String t = obj.optString("t", "");
                    if ("ping".equals(t)) {
                        JSONObject pong = new JSONObject();
                        pong.put("t", "pong");
                        pong.put("ts", obj.optLong("ts", 0));
                        send(pong);
                        continue;
                    }
                    if ("pong".equals(t)) {
                        long sent = obj.optLong("ts", 0);
                        if (sent > 0) rttMs = (int) Math.min(9999,
                                Math.max(0, System.currentTimeMillis() - sent));
                        continue;
                    }
                    listener.onControlMessage(this, obj);
                } catch (Exception e) {
                    Log.w(TAG, "Bad control message", e);
                }
            }
        } catch (SocketTimeoutException ignored) {
            closeInternal("控制连接超时");
        } catch (Exception e) {
            if (!closed.get()) closeInternal("控制连接中断");
        }
    }

    private void heartbeatLoop() {
        while (!closed.get()) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
            if (closed.get()) return;
            long silent = System.currentTimeMillis() - lastReceiveMs;
            if (silent > 10000) {
                closeInternal("控制连接心跳超时");
                return;
            }
            JSONObject ping = new JSONObject();
            try {
                ping.put("t", "ping");
                ping.put("ts", System.currentTimeMillis());
                send(ping);
            } catch (Exception ignored) {
            }
        }
    }

    public void close(String reason) {
        closeInternal(reason);
    }

    private void closeInternal(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        try {
            socket.close();
        } catch (Exception ignored) {
        }
        listener.onControlClosed(this, reason);
    }
}
