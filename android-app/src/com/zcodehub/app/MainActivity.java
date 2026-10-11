package com.zcodehub.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * ZCode 多机面板（独立版）：
 * 扫一台机器的配对二维码就存一台，之后各自通过 ZCode 的公网中继直连。
 * 不需要 hub、不需要隧道、不需要那些电脑有公网 IP。
 */
public class MainActivity extends Activity {

    private static final int REQ_SCAN = 4001;
    private static final int REQ_GALLERY = 4002;
    private static final long STATUS_TIMEOUT_MS = 12000;
    private static final long WAITING_GRACE_MS = 3000;

    private static final int C_BG = 0xFF0F1115;
    private static final int C_CARD = 0xFF171A21;
    private static final int C_FG = 0xFFE8EAF0;
    private static final int C_MUTED = 0xFF9AA3B2;
    private static final int C_ACCENT = 0xFF4C8DFF;
    private static final int C_OK = 0xFF35C46A;
    private static final int C_WARN = 0xFFE2B13C;
    private static final int C_ERR = 0xFFEF5F5F;

    /** 一台机器的探测结果 */
    private static class Probe {
        String pairStatus;
        String error;
        String code;
        String activeKey;
        String taskId;
        long ms;
        final List<String[]> workspaces = new ArrayList<>(); // {key, name}
    }

    private FrameLayout root;
    private LinearLayout homeView;
    private LinearLayout listContainer;
    private TextView statusText;
    private Button refreshButton;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<String, Probe> probes = new HashMap<>();
    private final List<String> probing = new ArrayList<>();
    private boolean busy;
    private float dp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        dp = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        root.setBackgroundColor(C_BG);
        homeView = buildHome();
        root.addView(homeView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        refreshAll();

        // 也支持从外部直接添加：adb/shell 或别的 App 传一个配对链接过来
        String link = getIntent() == null ? null : getIntent().getStringExtra("link");
        if (link != null && link.trim().length() > 0) addByLink(link.trim());

        // 扫码链路自检（仅用于排查）
        if (getIntent() != null && getIntent().getBooleanExtra("selftest", false)) {
            statusText.setTextColor(C_MUTED);
            statusText.setText("自检中…");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final String result = SelfTest.run();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            statusText.setTextColor(result.startsWith("PASS") ? C_OK : C_ERR);
                            statusText.setText("扫码自检：" + result);
                        }
                    });
                }
            }).start();
        }
    }

    /* ---------------- 界面 ---------------- */

    private int px(int value) {
        return Math.round(value * dp);
    }

    private TextView text(String s, int color, float size, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        if (bold) v.setTypeface(Typeface.DEFAULT_BOLD);
        return v;
    }

    private Button button(String label, boolean primary) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        if (primary) {
            b.setBackgroundColor(C_ACCENT);
            b.setTextColor(Color.WHITE);
        } else {
            b.setBackgroundColor(C_CARD);
            b.setTextColor(C_FG);
        }
        return b;
    }

    private LinearLayout buildHome() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundColor(C_BG);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(px(16), px(16), px(12), px(12));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text("ZCode 多机面板", C_FG, 18, true));
        titles.addView(text("扫码添加，各自走 ZCode 公网中继", C_MUTED, 12, false));
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        refreshButton = button("刷新", false);
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshAll();
            }
        });
        header.addView(refreshButton);

        Button add = button("添加", true);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showAddDialog();
            }
        });
        header.addView(add);
        box.addView(header);

        statusText = text("", C_MUTED, 13, false);
        statusText.setPadding(px(16), 0, px(16), px(10));
        box.addView(statusText);

        ScrollView scroll = new ScrollView(this);
        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listContainer.setPadding(px(12), 0, px(12), px(24));
        scroll.addView(listContainer);
        box.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return box;
    }

    private void render() {
        listContainer.removeAllViews();
        List<DeviceStore.Device> devices = DeviceStore.list(this);
        if (devices.isEmpty()) {
            TextView empty = text("还没有设备。\n\n点右上「添加」→ 扫 ZCode 桌面端「手机远控」显示的那个二维码，"
                    + "扫一台就存一台。\n之后手机在任何网络下都能看到它们的状态、并打开任意一台的控制台。",
                    C_MUTED, 13, false);
            empty.setPadding(px(4), px(16), px(4), 0);
            listContainer.addView(empty);
            return;
        }
        for (DeviceStore.Device device : devices) {
            listContainer.addView(buildCard(device));
        }
    }

    private View buildCard(final DeviceStore.Device device) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(C_CARD);
        card.setPadding(px(14), px(12), px(14), px(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(px(4), px(6), px(4), px(6));
        card.setLayoutParams(lp);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.addView(text(device.label.length() > 0 ? device.label : "未命名设备", C_FG, 16, true));
        String mid = device.mid == null ? "" : device.mid.trim();
        titles.addView(text(device.origin + " · " + (mid.length() == 0 ? "no-mid" : mid), C_MUTED, 12, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Probe probe = probes.get(device.sid);
        boolean isProbing = probing.contains(device.sid);
        if (isProbing) {
            head.addView(pill("检测中", C_MUTED));
        } else if (probe == null) {
            head.addView(pill("未检测", C_MUTED));
        } else if (probe.error != null) {
            head.addView(pill("离线", C_ERR));
        } else if ("matched".equals(probe.pairStatus)) {
            head.addView(pill("在线", C_OK));
        } else if ("waiting".equals(probe.pairStatus)) {
            head.addView(pill("待机", C_WARN));
        } else {
            head.addView(pill("未知", C_MUTED));
        }
        card.addView(head);

        if (probe != null && !probe.workspaces.isEmpty()) {
            HorizontalScrollView hsv = new HorizontalScrollView(this);
            hsv.setHorizontalScrollBarEnabled(false);
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setPadding(0, px(8), 0, 0);
            for (String[] w : probe.workspaces) {
                boolean active = probe.activeKey != null && probe.activeKey.equals(w[0]);
                TextView chip = text(w[1], active ? C_FG : C_MUTED, 12, active);
                chip.setPadding(px(8), px(4), px(8), px(4));
                chip.setBackgroundColor(active ? 0xFF16203A : C_BG);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.setMargins(0, 0, px(6), 0);
                chips.addView(chip, clp);
            }
            hsv.addView(chips);
            card.addView(hsv);
        }

        if (probe != null) {
            StringBuilder meta = new StringBuilder();
            if (probe.error != null) {
                meta.append("连不上：").append(probe.error);
            } else if ("waiting".equals(probe.pairStatus)) {
                meta.append("配对正常，但桌面端当前没开「手机远控」");
            } else if ("matched".equals(probe.pairStatus)) {
                meta.append("已连上（").append(probe.ms).append("ms）");
                if (probe.taskId != null && probe.taskId.length() > 0) {
                    meta.append(" · 任务 ").append(probe.taskId);
                }
            }
            if (meta.length() > 0) {
                TextView t = text(meta.toString(), probe.error != null ? C_ERR : C_MUTED, 12, false);
                t.setPadding(0, px(8), 0, 0);
                card.addView(t);
            }
        }

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, px(10), 0, 0);

        Button open = button("打开控制台", true);
        open.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(device.link)));
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "打不开浏览器：" + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        actions.addView(open);

        Button check = button("检测", false);
        check.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                probeDevice(device, true);
            }
        });
        actions.addView(check);

        Button rename = button("改名", false);
        rename.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                renameDevice(device);
            }
        });
        actions.addView(rename);

        Button delete = button("删除", false);
        delete.setTextColor(C_ERR);
        delete.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDelete(device);
            }
        });
        actions.addView(delete);
        card.addView(actions);

        TextView idView = text("设备号 " + device.sid, C_MUTED, 11, false);
        idView.setPadding(0, px(8), 0, 0);
        card.addView(idView);

        return card;
    }

    private TextView pill(String label, int color) {
        TextView v = text(label, color, 12, false);
        v.setPadding(px(8), px(3), px(8), px(3));
        return v;
    }

    /* ---------------- 添加设备 ---------------- */

    private void showAddDialog() {
        final String[] items = new String[]{"扫码（打开摄像头）", "从相册选二维码", "粘贴配对链接"};
        new AlertDialog.Builder(this)
                .setTitle("添加一台 ZCode")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        if (which == 0) {
                            startActivityForResult(new Intent(MainActivity.this, ScanActivity.class), REQ_SCAN);
                        } else if (which == 1) {
                            pickImage();
                        } else {
                            pasteLinkDialog();
                        }
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 从相册/文件里选一张二维码图片来识别（不需要存储权限） */
    private void pickImage() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            startActivityForResult(Intent.createChooser(intent, "选择二维码图片"), REQ_GALLERY);
        } catch (Exception e) {
            Toast.makeText(this, "打不开图库：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void pasteLinkDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("https://zcode.z.ai/remote/v4?sid=...&hash=...");
        new AlertDialog.Builder(this)
                .setTitle("粘贴配对链接")
                .setView(input)
                .setPositiveButton("添加", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        addByLink(input.getText().toString());
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void addByLink(String link) {
        try {
            DeviceStore.Device device = DeviceStore.parseLink(link);
            DeviceStore.add(this, device);
            Toast.makeText(this, "已添加：" + device.label, Toast.LENGTH_SHORT).show();
            render();
            probeDevice(device, true);
        } catch (Exception e) {
            Toast.makeText(this, "添加失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN) {
            if (resultCode == RESULT_OK && data != null) {
                String qr = data.getStringExtra(ScanActivity.EXTRA_QR);
                if (qr != null) addByLink(qr);
            }
            return;
        }
        if (requestCode == REQ_GALLERY) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                final android.net.Uri uri = data.getData();
                statusText.setTextColor(C_MUTED);
                statusText.setText("正在识别图片里的二维码…");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        final String qr = QrImages.decode(MainActivity.this, uri);
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                if (qr == null) {
                                    statusText.setTextColor(C_ERR);
                                    statusText.setText("这张图里没识别出二维码。尽量用屏幕直拍的清晰原图。");
                                } else {
                                    addByLink(qr);
                                }
                            }
                        });
                    }
                }).start();
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void renameDevice(final DeviceStore.Device device) {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(device.label);
        new AlertDialog.Builder(this)
                .setTitle("给这台设备起个名字")
                .setView(input)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        DeviceStore.rename(MainActivity.this, device.sid, input.getText().toString().trim());
                        render();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void confirmDelete(final DeviceStore.Device device) {
        new AlertDialog.Builder(this)
                .setTitle("删除这台设备？")
                .setMessage("只删除手机上的记录，不影响那台电脑上的 ZCode。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        DeviceStore.remove(MainActivity.this, device.sid);
                        probes.remove(device.sid);
                        render();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /* ---------------- 探测（直连中继） ---------------- */

    private void refreshAll() {
        List<DeviceStore.Device> devices = DeviceStore.list(this);
        probes.clear();
        render();
        if (devices.isEmpty()) {
            statusText.setTextColor(C_MUTED);
            statusText.setText("点右上「添加」扫码开始");
            return;
        }
        statusText.setTextColor(C_MUTED);
        statusText.setText("正在检测 " + devices.size() + " 台设备…");
        for (DeviceStore.Device device : devices) {
            probeDevice(device, false);
        }
    }

    private void probeDevice(final DeviceStore.Device device, final boolean renderAfter) {
        if (probing.contains(device.sid)) return;
        probing.add(device.sid);
        render();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Probe probe = probe(device);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        probing.remove(device.sid);
                        probes.put(device.sid, probe);
                        render();
                        if (renderAfter) {
                            if (probe.error != null) {
                                statusText.setTextColor(C_ERR);
                                statusText.setText(device.label + "：" + probe.error);
                            } else if ("matched".equals(probe.pairStatus)) {
                                statusText.setTextColor(C_OK);
                                statusText.setText(device.label + " 在线（" + probe.ms + "ms）");
                            } else {
                                statusText.setTextColor(C_WARN);
                                statusText.setText(device.label + "：配对正常，桌面端当前没开「手机远控」");
                            }
                        } else if (!probing.isEmpty()) {
                            // 全部完成后给个总结
                        } else {
                            int online = 0;
                            for (Probe p : probes.values()) {
                                if ("matched".equals(p.pairStatus)) online++;
                            }
                            statusText.setTextColor(online > 0 ? C_OK : C_MUTED);
                            statusText.setText(online + "/" + probes.size() + " 台在线");
                        }
                    }
                });
            }
        }).start();
    }

    /** 连一次中继拿状态；在线的话再取工作区列表 */
    private Probe probe(DeviceStore.Device device) {
        Probe r = new Probe();
        long start = System.currentTimeMillis();
        final CountDownLatch latch = new CountDownLatch(1);
        final RelayClient client = new RelayClient(device, new RelayClient.Listener() {
            @Override
            public void onPairStatus(String status) {
                // 状态由 client.getPairStatus() 读取
            }

            @Override
            public void onError(String code, String message) {
                synchronized (latch) {
                    latch.countDown();
                }
            }
        });
        try {
            client.connect();
            long deadline = System.currentTimeMillis() + STATUS_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline && client.getPairStatus() == null) {
                Thread.sleep(200);
            }
            String status = client.getPairStatus();
            if (status == null) {
                r.error = "中继没有响应（检查网络，或该设备的配对已失效）";
            } else if ("waiting".equals(status)) {
                // 设备可能刚开启远控，给几秒宽限
                long grace = System.currentTimeMillis() + WAITING_GRACE_MS;
                while (System.currentTimeMillis() < grace && !"matched".equals(client.getPairStatus())) {
                    Thread.sleep(200);
                }
                status = client.getPairStatus();
            }
            r.pairStatus = status;

            if ("matched".equals(status)) {
                final CountDownLatch wsLatch = new CountDownLatch(1);
                client.workspaceList(8000, new RelayClient.Callback() {
                    @Override
                    public void onResult(JSONObject payload) {
                        try {
                            JSONObject result = payload.optJSONObject("result");
                            JSONArray arr = result == null ? null : result.optJSONArray("workspaces");
                            if (arr != null) {
                                for (int i = 0; i < arr.length(); i++) {
                                    JSONObject w = arr.optJSONObject(i);
                                    if (w == null) continue;
                                    String key = w.optString("workspaceIdentity", "");
                                    if (key.length() == 0) key = w.optString("workspacePath", "");
                                    String path = w.optString("workspacePath", "");
                                    String name = path;
                                    int idx = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
                                    if (idx >= 0 && idx + 1 < path.length()) name = path.substring(idx + 1);
                                    synchronized (r) {
                                        r.workspaces.add(new String[]{key, name});
                                    }
                                }
                            }
                            if (result != null) {
                                String key = result.optString("activeWorkspaceKey", "");
                                r.activeKey = key;
                                String task = result.isNull("activeTaskId") ? "" : result.optString("activeTaskId", "");
                                if ("null".equals(task)) task = "";
                                r.taskId = task;
                            }
                        } catch (Exception ignored) {
                        }
                        wsLatch.countDown();
                    }

                    @Override
                    public void onError(String message) {
                        wsLatch.countDown();
                    }
                });
                wsLatch.await(9, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            r.error = e.getMessage() == null ? String.valueOf(e) : e.getMessage();
        } finally {
            client.close();
        }
        r.ms = System.currentTimeMillis() - start;
        return r;
    }
}
