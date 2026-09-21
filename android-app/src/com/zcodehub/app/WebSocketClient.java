package com.zcodehub.app;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.security.SecureRandom;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * 极简 WebSocket 客户端（只实现我们需要的部分：文本帧、ping/pong、关闭）。
 * Android 没有内置的 WebSocket 客户端，而 App 要直连 ZCode 的公网中继，所以自己实现。
 */
public class WebSocketClient {

    public interface Listener {
        void onOpen();

        void onText(String text);

        void onClosed(String reason);

        void onError(Exception error);
    }

    private final URI uri;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private Socket socket;
    private OutputStream out;
    private DataInputStream in;
    private volatile boolean closed;

    public WebSocketClient(URI uri, Listener listener) {
        this.uri = uri;
        this.listener = listener;
    }

    public void connect() throws IOException {
        String scheme = uri.getScheme() == null ? "wss" : uri.getScheme();
        String host = uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : ("wss".equals(scheme) ? 443 : 80);

        if ("wss".equals(scheme)) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            ssl.connect(new InetSocketAddress(host, port), 12000);
            ssl.startHandshake();
            socket = ssl;
        } else {
            socket = new Socket();
            socket.connect(new InetSocketAddress(host, port), 12000);
        }
        socket.setTcpNoDelay(true);
        out = new BufferedOutputStream(socket.getOutputStream());
        in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));

        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        String key = Base64.encodeToString(nonce, Base64.NO_WRAP);

        StringBuilder req = new StringBuilder();
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) path = "/";
        req.append("GET ").append(path);
        if (uri.getRawQuery() != null) req.append('?').append(uri.getRawQuery());
        req.append(" HTTP/1.1\r\n");
        req.append("Host: ").append(host).append(port == 443 || port == 80 ? "" : ":" + port).append("\r\n");
        req.append("Upgrade: websocket\r\n");
        req.append("Connection: Upgrade\r\n");
        req.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        req.append("Sec-WebSocket-Version: 13\r\n");
        req.append("Origin: https://").append(host).append("\r\n");
        req.append("User-Agent: zcode-hub-android\r\n");
        req.append("\r\n");
        out.write(req.toString().getBytes("UTF-8"));
        out.flush();

        String statusLine = readLine();
        if (statusLine == null || !statusLine.contains("101")) {
            throw new IOException("WebSocket 握手失败：" + statusLine);
        }
        String line;
        while ((line = readLine()) != null && line.length() > 0) {
            // 跳过响应头
        }

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop();
            }
        }, "ws-reader");
        reader.setDaemon(true);
        reader.start();

        listener.onOpen();
    }

    private String readLine() throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int c = in.read();
            if (c < 0) return buf.size() == 0 ? null : buf.toString("UTF-8");
            if (prev == '\r' && c == '\n') {
                byte[] bytes = buf.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), "UTF-8");
            }
            buf.write(c);
            prev = c;
        }
    }

    public synchronized void sendText(String text) throws IOException {
        byte[] payload = text.getBytes("UTF-8");
        int len = payload.length;
        ByteArrayOutputStream frame = new ByteArrayOutputStream(len + 14);
        frame.write(0x81); // FIN + text
        if (len < 126) {
            frame.write(0x80 | len);
        } else if (len < 65536) {
            frame.write(0x80 | 126);
            frame.write((len >> 8) & 0xFF);
            frame.write(len & 0xFF);
        } else {
            frame.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) frame.write((int) (((long) len >> (8 * i)) & 0xFF));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        frame.write(mask);
        for (int i = 0; i < len; i++) frame.write(payload[i] ^ mask[i & 3]);
        out.write(frame.toByteArray());
        out.flush();
    }

    private void sendControl(int opcode, byte[] payload) {
        try {
            synchronized (this) {
                ByteArrayOutputStream frame = new ByteArrayOutputStream();
                frame.write(0x80 | opcode);
                frame.write(0x80 | (payload.length & 0x7F));
                byte[] mask = new byte[4];
                random.nextBytes(mask);
                frame.write(mask);
                for (int i = 0; i < payload.length; i++) frame.write(payload[i] ^ mask[i & 3]);
                out.write(frame.toByteArray());
                out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private void readLoop() {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        try {
            while (!closed) {
                int b0 = in.read();
                if (b0 < 0) break;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                int b1 = in.read();
                if (b1 < 0) break;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) {
                    len = ((long) in.read() << 8) | in.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | in.read();
                }
                if (len > 8 * 1024 * 1024) throw new IOException("帧过大：" + len);
                byte[] maskKey = null;
                if (masked) {
                    maskKey = new byte[4];
                    in.readFully(maskKey);
                }
                byte[] data = new byte[(int) len];
                in.readFully(data);
                if (masked) {
                    for (int i = 0; i < data.length; i++) data[i] ^= maskKey[i & 3];
                }
                if (opcode == 0x9) {
                    sendControl(0xA, data); // ping → pong
                } else if (opcode == 0xA) {
                    // pong，忽略
                } else if (opcode == 0x8) {
                    break;
                } else if (opcode == 0x1 || opcode == 0x0) {
                    message.write(data);
                    if (fin) {
                        String text = message.toString("UTF-8");
                        message.reset();
                        listener.onText(text);
                    }
                }
            }
        } catch (Exception e) {
            if (!closed) listener.onError(e);
        } finally {
            boolean wasClosed = closed;
            close();
            if (!wasClosed) listener.onClosed("closed");
        }
    }

    public void close() {
        closed = true;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        socket = null;
        out = null;
        in = null;
    }

    public boolean isClosed() {
        return closed;
    }
}
