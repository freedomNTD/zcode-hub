package com.zcodehub.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 本机保存的多台 ZCode 设备（扫一台存一台），以及对配对链接的解析。
 */
public class DeviceStore {

    private static final String PREFS = "zcode_hub_devices";
    private static final String KEY = "devices";

    public static class Device {
        public String sid = "";
        public String hash = "";
        public String origin = "";
        public String mid = "";
        public String name = "";
        public String appVersion = "";
        public String label = "";
        public String link = "";

        public String relayWsUrl() {
            String base = origin == null ? "" : origin;
            if (base.startsWith("https://")) base = "wss://" + base.substring("https://".length());
            else if (base.startsWith("http://")) base = "ws://" + base.substring("http://".length());
            return base + "/ws";
        }

        public JSONObject toJson() throws Exception {
            JSONObject o = new JSONObject();
            o.put("sid", sid);
            o.put("hash", hash);
            o.put("origin", origin);
            o.put("mid", mid);
            o.put("name", name);
            o.put("appVersion", appVersion);
            o.put("label", label);
            o.put("link", link);
            return o;
        }

        public static Device fromJson(JSONObject o) {
            Device d = new Device();
            d.sid = o.optString("sid", "");
            d.hash = o.optString("hash", "");
            d.origin = o.optString("origin", "");
            d.mid = o.optString("mid", "");
            d.name = o.optString("name", "");
            d.appVersion = o.optString("appVersion", "");
            d.label = o.optString("label", "");
            d.link = o.optString("link", "");
            return d;
        }
    }

    /** 解析 ZCode 配对二维码里的链接：https://<origin>/remote/v4?sid=..&hash=..&mid=..&name=.. */
    public static Device parseLink(String text) {
        Uri uri = Uri.parse(String.valueOf(text).trim());
        String sid = uri.getQueryParameter("sid");
        String hash = uri.getQueryParameter("hash");
        if (sid == null || sid.length() == 0 || hash == null || hash.length() == 0) {
            throw new IllegalArgumentException("这个码里没有 sid/hash，不是 ZCode 的配对二维码");
        }
        Device d = new Device();
        d.sid = sid;
        d.hash = hash;
        String host = uri.getHost();
        if (host == null) throw new IllegalArgumentException("链接里没有主机名");
        int port = uri.getPort();
        d.origin = uri.getScheme() + "://" + host + (port > 0 ? ":" + port : "");
        d.mid = uri.getQueryParameter("mid") == null ? "" : uri.getQueryParameter("mid");
        d.name = uri.getQueryParameter("name") == null ? "" : uri.getQueryParameter("name");
        d.appVersion = uri.getQueryParameter("app_version") == null ? "" : uri.getQueryParameter("app_version");
        d.link = uri.toString();
        d.label = d.name.length() > 0 ? d.name : (d.mid.length() > 0 ? d.mid : "未命名设备");
        return d;
    }

    public static List<Device> list(Context context) {
        List<Device> out = new ArrayList<>();
        SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String raw = sp.getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) out.add(Device.fromJson(o));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void save(Context context, List<Device> devices) {
        JSONArray arr = new JSONArray();
        try {
            for (Device d : devices) arr.put(d.toJson());
        } catch (Exception ignored) {
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY, arr.toString()).apply();
    }

    /** 按 sid 去重添加/更新 */
    public static void add(Context context, Device device) {
        List<Device> devices = list(context);
        for (int i = 0; i < devices.size(); i++) {
            if (devices.get(i).sid.equals(device.sid)) {
                if (device.label.length() == 0) device.label = devices.get(i).label;
                devices.set(i, device);
                save(context, devices);
                return;
            }
        }
        devices.add(device);
        save(context, devices);
    }

    public static void remove(Context context, String sid) {
        List<Device> devices = list(context);
        for (int i = devices.size() - 1; i >= 0; i--) {
            if (devices.get(i).sid.equals(sid)) devices.remove(i);
        }
        save(context, devices);
    }

    public static void rename(Context context, String sid, String label) {
        List<Device> devices = list(context);
        for (Device d : devices) {
            if (d.sid.equals(sid)) d.label = label;
        }
        save(context, devices);
    }
}
