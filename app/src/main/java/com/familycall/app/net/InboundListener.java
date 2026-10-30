package com.familycall.app.net;

import android.util.Log;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Accepts direct TCP connections on the configured local port. */
public final class InboundListener {
    private static final String TAG = "InboundListener";

    public interface Callback {
        void onHello(Socket socket, JSONObject hello);

        void onListenerState(boolean listening, int port, String error);
    }

    private final Callback callback;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile boolean running;
    private volatile int generation;
    private volatile int port;
    private ServerSocket server;
    private Thread thread;

    public InboundListener(Callback callback) {
        this.callback = callback;
    }

    public synchronized void start(int port) {
        stop();
        this.port = port;
        running = true;
        final int gen = ++generation;
        thread = new Thread(() -> acceptLoop(port, gen), "tcp-listener");
        thread.setDaemon(true);
        thread.start();
    }

    private void acceptLoop(int localPort, int gen) {
        try {
            ServerSocket s = new ServerSocket();
            s.setReuseAddress(true);
            s.bind(new InetSocketAddress(localPort));
            s.setSoTimeout(1200);
            if (!running || gen != generation) {
                s.close();
                return;
            }
            server = s;
            callback.onListenerState(true, localPort, null);
            Log.i(TAG, "listening on " + localPort);
            while (running && gen == generation) {
                try {
                    Socket socket = s.accept();
                    workers.execute(() -> handle(socket));
                } catch (java.net.SocketTimeoutException ignored) {
                } catch (IOException e) {
                    if (running && gen == generation) {
                        Log.w(TAG, "accept failed", e);
                        callback.onListenerState(false, localPort, "端口监听中断，正在重试");
                        sleep(1000);
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "cannot bind " + localPort, e);
            if (gen == generation) {
                callback.onListenerState(false, localPort,
                        "本地端口 " + localPort + " 无法监听（可能被占用）");
            }
        }
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(10000);
            socket.setTcpNoDelay(true);
            String line = readLine(socket.getInputStream(), 64 * 1024);
            if (line == null) {
                close(socket);
                return;
            }
            JSONObject hello = new JSONObject(line);
            if (!"hello".equals(hello.optString("t"))) {
                reject(socket, "bad");
                return;
            }
            callback.onHello(socket, hello);
        } catch (Exception e) {
            Log.w(TAG, "bad incoming connection", e);
            close(socket);
        }
    }

    public static void sendLine(Socket socket, JSONObject object) {
        try {
            OutputStream out = socket.getOutputStream();
            out.write((object.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception ignored) {
        }
    }

    public static void sendEnded(Socket socket, String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "ended");
            o.put("reason", reason);
            sendLine(socket, o);
        } catch (Exception ignored) {
        }
        close(socket);
    }

    public static void reject(Socket socket, String reason) {
        try {
            JSONObject o = new JSONObject();
            o.put("t", "busy");
            o.put("reason", reason);
            sendLine(socket, o);
        } catch (Exception ignored) {
        }
        close(socket);
    }

    private static String readLine(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b == '\r') continue;
            out.write(b);
            if (out.size() > max) throw new IOException("line too long");
        }
        if (b == -1 && out.size() == 0) return null;
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (Exception ignored) {
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    public synchronized void stop() {
        running = false;
        ServerSocket s = server;
        server = null;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(600);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
