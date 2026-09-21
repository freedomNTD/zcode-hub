package com.zcodehub.app;

import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import org.json.JSONObject;

import java.net.URI;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * ZCode 远程控制中继客户端（手机侧角色 = terminal）。
 *
 * 协议（逆向自 ZCode 桌面端与官方移动端实现，已实测）：
 *   1) 连 wss://<host>/ws?mid=<deviceMid>
 *   2) 发 {type:auth_init, role:terminal, device_sid, meta, client_ts}
 *   3) ← {type:auth_challenge, nonce}
 *      发 {type:auth_response, device_sid, proof, client_ts}
 *      proof = base64url(HMAC-SHA256(passHash, "<nonce>|terminal|<deviceSid>"))
 *   4) ← {type:auth_ack|pair_status_ack, pair_status:"waiting"|"matched"}
 *   5) 应用层：{type:data, payload:{zcode_type:...}}
 *   心跳：{type:pair_status_query, device_sid, client_ts}
 */
public class RelayClient {

    public interface Listener {
        void onPairStatus(String status);

        void onError(String code, String message);
    }

    public interface Callback {
        void onResult(JSONObject payload);

        void onError(String message);
    }

    public interface Matcher {
        boolean matches(JSONObject payload);
    }

    private static final long HEARTBEAT_MS = 10000;

    private final DeviceStore.Device device;
    private final Listener listener;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Map<String, Pending> pending = new HashMap<>();
    private final Random random = new Random();

    private WebSocketClient socket;
    private Runnable heartbeat;
    private volatile boolean closed;

    private static class Pending {
        Matcher matcher;
        Callback callback;
        Runnable timeout;
    }

    public RelayClient(DeviceStore.Device device, Listener listener) {
        this.device = device;
        this.listener = listener;
    }

    public String getPairStatus() {
        return pairStatus;
    }

    private volatile String pairStatus;

    public void connect() throws Exception {
        String url = device.relayWsUrl();
        if (device.mid != null && device.mid.length() > 0) {
            url = url + (url.contains("?") ? "&" : "?") + "mid=" + device.mid;
        }
        socket = new WebSocketClient(new URI(url), new WebSocketClient.Listener() {
            @Override
            public void onOpen() {
                sendAuthInit();
            }

            @Override
            public void onText(String text) {
                handleMessage(text);
            }

            @Override
            public void onClosed(String reason) {
                failAll("中继连接已关闭");
            }

            @Override
            public void onError(Exception error) {
                failAll(error == null ? "中继连接出错" : String.valueOf(error.getMessage()));
            }
        });
        socket.connect();
    }

    private void sendAuthInit() {
        try {
            JSONObject meta = new JSONObject();
            meta.put("platform", "android");
            meta.put("version", "zcode-hub-android");
            meta.put("name", "zcode-hub-android");
            JSONObject msg = new JSONObject();
            msg.put("type", "auth_init");
            msg.put("role", "terminal");
            msg.put("device_sid", device.sid);
            msg.put("meta", meta);
            msg.put("client_ts", System.currentTimeMillis());
            send(msg);
        } catch (Exception e) {
            failAll("认证请求发送失败");
        }
    }

    private void handleMessage(String text) {
        JSONObject msg;
        try {
            msg = new JSONObject(text);
        } catch (Exception e) {
            return;
        }
        String type = msg.optString("type", "");
        try {
            if ("auth_challenge".equals(type)) {
                JSONObject response = new JSONObject();
                response.put("type", "auth_response");
                response.put("device_sid", device.sid);
                response.put("proof", proof(msg.optString("nonce", "")));
                response.put("client_ts", System.currentTimeMillis());
                send(response);
            } else if ("auth_ack".equals(type) || "pair_status_ack".equals(type)) {
                String status = msg.optString("pair_status", null);
                if (status != null && status.length() > 0) {
                    pairStatus = status;
                    startHeartbeat();
                    final String s = status;
                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.onPairStatus(s);
                        }
                    });
                }
            } else if ("data".equals(type)) {
                Object raw = msg.opt("payload");
                JSONObject payload = null;
                if (raw instanceof JSONObject) payload = (JSONObject) raw;
                else if (raw instanceof String) payload = new JSONObject((String) raw);
                if (payload != null) dispatch(payload);
            } else if ("error".equals(type)) {
                final String code = msg.optString("code", "ERROR");
                final String message = msg.optString("message", code);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onError(code, message);
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    /** proof = base64url(HMAC-SHA256(passHash, "nonce|terminal|sid"))，无填充 */
    private String proof(String nonce) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(device.hash.getBytes("UTF-8"), "HmacSHA256"));
        byte[] out = mac.doFinal((nonce + "|terminal|" + device.sid).getBytes("UTF-8"));
        return Base64.encodeToString(out, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    private synchronized void dispatch(JSONObject payload) {
        String requestId = payload.optString("requestId", null);
        String zcodeType = payload.optString("zcode_type", null);
        Iterator<Map.Entry<String, Pending>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Pending> entry = it.next();
            Pending p = entry.getValue();
            if (p.matcher.matches(payload)) {
                it.remove();
                if (p.timeout != null) handler.removeCallbacks(p.timeout);
                final Pending matched = p;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        matched.callback.onResult(payload);
                    }
                });
                return;
            }
        }
    }

    private void failAll(final String message) {
        if (closed) return;
        synchronized (this) {
            for (Pending p : pending.values()) {
                if (p.timeout != null) handler.removeCallbacks(p.timeout);
                final Pending item = p;
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        item.callback.onError(message);
                    }
                });
            }
            pending.clear();
        }
    }

    private void send(JSONObject msg) throws Exception {
        if (socket == null) throw new IllegalStateException("未连接");
        socket.sendText(msg.toString());
    }

    public void sendPayload(JSONObject payload) throws Exception {
        JSONObject wrapper = new JSONObject();
        wrapper.put("type", "data");
        wrapper.put("payload", payload);
        send(wrapper);
    }

    public void request(JSONObject payload, Matcher matcher, long timeoutMs, Callback callback) {
        final String key = String.valueOf(random.nextLong());
        Pending p = new Pending();
        p.matcher = matcher;
        p.callback = callback;
        p.timeout = new Runnable() {
            @Override
            public void run() {
                synchronized (RelayClient.this) {
                    if (pending.remove(key) != null) {
                        callback.onError("等待响应超时");
                    }
                }
            }
        };
        synchronized (this) {
            pending.put(key, p);
        }
        handler.postDelayed(p.timeout, timeoutMs);
        try {
            sendPayload(payload);
        } catch (Exception e) {
            synchronized (this) {
                pending.remove(key);
            }
            handler.removeCallbacks(p.timeout);
            callback.onError(e.getMessage() == null ? "发送失败" : e.getMessage());
        }
    }

    /** 取工作区列表（含当前激活工作区与任务） */
    public void workspaceList(long timeoutMs, Callback callback) {
        final String requestId = "workspace-list-" + Long.toHexString(random.nextLong());
        try {
            JSONObject payload = new JSONObject();
            payload.put("zcode_type", "workspace-list-request");
            payload.put("requestId", requestId);
            request(payload, new Matcher() {
                @Override
                public boolean matches(JSONObject p) {
                    return "workspace-list-response".equals(p.optString("zcode_type"))
                            && requestId.equals(p.optString("requestId"));
                }
            }, timeoutMs, callback);
        } catch (Exception e) {
            callback.onError(String.valueOf(e.getMessage()));
        }
    }

    private void startHeartbeat() {
        if (heartbeat != null) return;
        heartbeat = new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject msg = new JSONObject();
                    msg.put("type", "pair_status_query");
                    msg.put("device_sid", device.sid);
                    msg.put("client_ts", System.currentTimeMillis());
                    send(msg);
                } catch (Exception ignored) {
                }
                handler.postDelayed(this, HEARTBEAT_MS);
            }
        };
        handler.postDelayed(heartbeat, HEARTBEAT_MS);
    }

    public void close() {
        closed = true;
        if (heartbeat != null) {
            handler.removeCallbacks(heartbeat);
            heartbeat = null;
        }
        failAll("已断开");
        if (socket != null) socket.close();
        socket = null;
    }
}
