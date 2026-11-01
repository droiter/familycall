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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Newline-delimited JSON control channel over a direct TCP connection.
 * All writes happen on a writer thread, so callers on the UI thread never
 * violate Android's NetworkOnMainThread policy. The heartbeat doubles as an
 * RTT probe (2 s interval, 10 s timeout).
 */
public final class ControlLink {
    private static final String TAG = "ControlLink";
    private static final String POISON = "\u0000__close__";

    public interface Listener {
        void onControlMessage(ControlLink link, JSONObject message);

        void onControlClosed(ControlLink link, String reason);
    }

    private final Socket socket;
    private final Listener listener;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>();
    private volatile BufferedReader reader;
    private volatile BufferedWriter writer;
    private volatile long lastReceiveMs;
    private volatile int rttMs;
    private volatile boolean closing;
    private Thread readerThread;
    private Thread heartbeatThread;
    private Thread writerThread;

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
        writerThread = new Thread(this::writeLoop, "control-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        readerThread = new Thread(this::readLoop, "control-reader");
        readerThread.setDaemon(true);
        readerThread.start();
        heartbeatThread = new Thread(this::heartbeatLoop, "control-heartbeat");
        heartbeatThread.setDaemon(true);
        heartbeatThread.start();
    }

    /** Queues a message; the actual socket write happens on the writer thread. */
    public boolean send(JSONObject message) {
        if (closed.get() || closing) return false;
        try {
            outbox.offer(message.toString());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void writeLoop() {
        try {
            while (!closed.get()) {
                String line = outbox.take();
                if (POISON.equals(line)) {
                    return;
                }
                BufferedWriter w = writer;
                if (w == null) return;
                w.write(line);
                w.write('\n');
                w.flush();
            }
        } catch (InterruptedException ignored) {
        } catch (IOException e) {
            closeInternal("控制连接写入失败");
        } catch (Exception e) {
            closeInternal("控制连接异常");
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

    /** Graceful close: lets already queued messages (e.g. BYE) reach the socket. */
    public void close(String reason) {
        if (closed.get() || closing) return;
        closing = true;
        outbox.offer(POISON);
        Thread wt = writerThread;
        if (wt == null) {
            closeInternal(reason);
            return;
        }
        Thread closer = new Thread(() -> {
            try {
                wt.join(600);
            } catch (InterruptedException ignored) {
            }
            closeInternal(reason);
        }, "control-close");
        closer.setDaemon(true);
        closer.start();
    }

    private void closeInternal(String reason) {
        if (!closed.compareAndSet(false, true)) return;
        closing = true;
        Thread wt = writerThread;
        if (wt != null) wt.interrupt();
        try {
            socket.close();
        } catch (Exception ignored) {
        }
        listener.onControlClosed(this, reason);
    }
}
