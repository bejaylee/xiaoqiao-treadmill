package com.xq.treadmill;

import android.app.Activity;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.DatagramPacket;
import java.net.DatagramSocket;

/**
 * 添加设备：先分情形，再走对应路径。
 *
 *   情形 A｜已配过网，只是换手机/重装 —— 直接扫描发现并连接
 *   情形 B｜首次使用，或换了路由器/WiFi —— 按键引导 → 配网 → 自动扫描连接
 *
 * 入口会根据使用历史给出推荐，失败页也允许随时改走另一条路。
 */
public class AddDeviceActivity extends Activity implements ConnectionManager.Listener {

    private static final int REQ_PROVISION = 2001;
    private static final int SCAN_TIMEOUT_MS = 30000;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Ui u;
    private Palette p;

    private TextView tvStep;
    private LinearLayout content;

    private RadarView radar;
    private volatile boolean scanning;
    private WifiManager.MulticastLock multicastLock;

    private ConnectionManager conn;
    private boolean connectedOnce;
    private String foundIp = "";

    @Override
    protected void onCreate(Bundle b) {
        ThemeManager.apply(this);
        super.onCreate(b);
        u = new Ui(this);
        p = u.p;
        setContentView(buildUi());
        conn = ConnectionManager.get();
        conn.register(this);
        showChoose();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);      // S4
        stopScan();
        if (conn != null) conn.unregister(this);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PROVISION && resultCode == RESULT_OK) {
            showScan("配网成功，正在搜索设备…");
        }
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(p.bg);
        sv.setFillViewport(true);
        LinearLayout root = u.column();
        u.pad(root, 20, 8, 20, 28);
        sv.addView(root);

        LinearLayout head = u.row();
        TextView back = u.circle("‹", 48, p.surfaceVariant, p.onSurface, Palette.alpha(p.onSurface, 26));
        back.setContentDescription("返回");
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(back);
        LinearLayout col = u.column();
        col.addView(u.headline("添加设备", 24));
        tvStep = u.text("", 12, p.onSurfaceVariant);
        u.gap(tvStep, 2);
        col.addView(tvStep);
        LinearLayout.LayoutParams hlp = u.lpWeight(1);
        hlp.leftMargin = u.dp(12);
        head.addView(col, hlp);
        root.addView(head);

        content = u.column();
        u.gap(content, 8);
        root.addView(content);
        return sv;
    }

    private void step(String label) {
        tvStep.setText(label);
    }

    // ------------------------------------------------------------ 0. 选情形
    private void showChoose() {
        stopScan();
        step("先确认你的跑步机属于哪种情况");
        content.removeAllViews();

        boolean everConnected = getSharedPreferences("xq", MODE_PRIVATE)
                .getBoolean("ever", false);

        TextView lead = u.text("两种情况走的路不一样，选错也没关系，下一页可以随时切换。",
                13, p.onSurfaceVariant);
        lead.setLineSpacing(u.dp(2), 1f);
        u.gap(lead, 16);
        content.addView(lead);

        content.addView(actionCard(
                "连接已配网的设备",
                "已连过家里 WiFi，只是换了手机或重装 App",
                "扫描并连接",
                everConnected,
                everConnected,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showScan(null);
                    }
                }));

        content.addView(actionCard(
                "首次配网 / 换了 WiFi",
                "全新设备，或路由器、WiFi 密码变了",
                "开始配网",
                !everConnected,
                !everConnected,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showGuide();
                    }
                }));
    }

    /** primary=true 用主色按钮；recommended=true 加「推荐」角标 */
    private LinearLayout actionCard(String title, String desc, String button,
                                    boolean primary, boolean recommended,
                                    View.OnClickListener onClick) {
        LinearLayout card = u.card();
        u.gap(card, 12);
        LinearLayout titleRow = u.row();
        titleRow.addView(u.text(title, 17, p.onSurface, true), u.lpWeight(1));
        if (recommended) titleRow.addView(u.badge("推荐", p.primaryContainer, p.onPrimaryContainer));
        card.addView(titleRow);
        TextView d = u.text(desc, 12, p.onSurfaceVariant);
        d.setLineSpacing(u.dp(2), 1f);
        u.gap(d, 6);
        card.addView(d);

        TextView b = primary ? u.filled(button) : u.outlined(button);
        b.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(48)));
        u.gap(b, 12);
        b.setOnClickListener(onClick);
        card.addView(b);
        return card;
    }

    // ------------------------------------------------------------ 1. 配网引导
    private void showGuide() {
        stopScan();
        step("第 1 步 / 3 · 让跑步机进入配网状态");
        content.removeAllViews();

        LinearLayout card = u.card();
        u.gap(card, 16);
        card.addView(u.headline("按键让跑步机进入配网", 20));
        TextView lead = u.text("跑步机只有在配网状态才会接收 WiFi 信息，这一步不能省。",
                13, p.onSurfaceVariant);
        lead.setLineSpacing(u.dp(2), 1f);
        u.gap(lead, 6);
        card.addView(lead);

        addStepRow(card, "1", "待机时按住面板「-」键 5 秒",
                "旋钮机型：拔掉安全锁后按住旋钮 5 秒");
        addStepRow(card, "2", "听到「哔」的一声，说明已进入配网状态",
                "此时跑带不会动，面板可能没有明显变化");
        addStepRow(card, "3", "确认手机连着家里的 2.4GHz WiFi",
                "5G 频段收不到跑步机的广播");
        content.addView(card);

        TextView ok = u.filled("我按好了，去配网");
        ok.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(52)));
        u.gap(ok, 18);
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(AddDeviceActivity.this, ProvisionActivity.class);
                i.putExtra("auto_finish", true);
                startActivityForResult(i, REQ_PROVISION);
            }
        });
        content.addView(ok);

        TextView scan = u.textButton("已经配过网？直接扫描");
        u.gap(scan, 8);
        scan.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showScan(null);
            }
        });
        content.addView(scan);
    }

    private void addStepRow(LinearLayout parent, String num, String title, String detail) {
        LinearLayout row = u.row();
        row.setGravity(Gravity.TOP);
        row.setPadding(0, u.dp(14), 0, 0);
        TextView n = u.text(num, 12, p.onPrimaryContainer, true);
        n.setGravity(Gravity.CENTER);
        n.setBackground(u.round(p.primaryContainer, 12));
        row.addView(n, u.lp(u.dp(24), u.dp(24)));
        LinearLayout col = u.column();
        TextView t = u.text(title, 14, p.onSurface, true);
        TextView d = u.text(detail, 12, p.onSurfaceVariant);
        t.setLineSpacing(u.dp(2), 1f);
        u.gap(d, 3);
        col.addView(t);
        col.addView(d);
        LinearLayout.LayoutParams lp = u.lpWeight(1);
        lp.leftMargin = u.dp(12);
        row.addView(col, lp);
        parent.addView(row);
    }

    // ------------------------------------------------------------ 2. 扫描
    private void showScan(String subTitle) {
        step("第 2 步 / 3 · 正在扫描附近的跑步机");
        content.removeAllViews();

        LinearLayout box = u.column();
        box.setGravity(Gravity.CENTER);
        radar = new RadarView(this, p.primary);
        box.addView(radar, u.lp(u.dp(220), u.dp(220)));
        u.gap(box, 12);
        content.addView(box);

        TextView title = u.text("正在寻找跑步机…", 18, p.onSurface, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title);

        TextView hint = u.text(subTitle != null ? subTitle
                : "保持跑步机开机，手机和它连同一个 WiFi", 13, p.onSurfaceVariant);
        hint.setGravity(Gravity.CENTER);
        u.gap(hint, 8);
        content.addView(hint);

        TextView reprov = u.textButton("扫不到？可能换了 WiFi，去重新配网");
        u.gap(reprov, 16);
        reprov.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGuide();
            }
        });
        content.addView(reprov);

        TextView cancel = u.textButton("取消");
        u.gap(cancel, 4);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showChoose();
            }
        });
        content.addView(cancel);

        radar.start();
        startScan();
    }

    private void startScan() {
        if (scanning) return;
        scanning = true;
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            multicastLock = wm.createMulticastLock("xq-add");
            multicastLock.setReferenceCounted(false);
            multicastLock.acquire();
        }
        Logs.add("添加设备：开始扫描");

        new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket sock = null;
                long start = System.currentTimeMillis();
                try {
                    sock = new DatagramSocket(null);
                    sock.setReuseAddress(true);
                    sock.bind(new java.net.InetSocketAddress(TreadmillClient.UDP_PORT));
                    sock.setSoTimeout(1200);
                    byte[] buf = new byte[64];
                    while (scanning) {
                        if (System.currentTimeMillis() - start > SCAN_TIMEOUT_MS) {
                            ui.post(new Runnable() {
                                @Override
                                public void run() {
                                    showFail("没有找到设备",
                                            "如果跑步机换了 WiFi，或路由器改过密码，需要重新配网。");
                                }
                            });
                            return;
                        }
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        try {
                            sock.receive(pkt);
                        } catch (java.net.SocketTimeoutException te) {
                            continue;
                        }
                        final TreadmillClient.Device d = TreadmillClient.parseDiscovery(pkt);
                        if (d == null) continue;
                        scanning = false;
                        foundIp = d.ip;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                showConnecting(d);
                            }
                        });
                        return;
                    }
                } catch (Exception e) {
                    final String msg = e.getMessage();
                    ui.post(new Runnable() {
                        @Override
                        public void run() {
                            showFail("扫描失败", msg);
                        }
                    });
                } finally {
                    scanning = false;
                    if (sock != null) sock.close();
                    releaseLock();
                }
            }
        }, "xq-add-scan").start();
    }

    private void stopScan() {
        scanning = false;
        releaseLock();
        if (radar != null) {
            radar.stop();
            radar = null;
        }
    }

    private void releaseLock() {
        if (multicastLock != null) {
            try {
                multicastLock.release();
            } catch (Exception ignored) {
            }
            multicastLock = null;
        }
    }

    // ------------------------------------------------------------ 3. 连接
    private void showConnecting(TreadmillClient.Device d) {
        step("第 3 步 / 3 · 找到设备，正在连接");
        content.removeAllViews();

        LinearLayout card = u.card();
        u.gap(card, 60);
        card.addView(u.text("找到设备", 18, p.onSurface, true));
        TextView addr = u.text(d.ip + "   " + d.mac, 13, p.onSurfaceVariant);
        u.gap(addr, 6);
        card.addView(addr);
        TextView state = u.text("正在连接…", 14, p.primary, true);
        u.gap(state, 14);
        card.addView(state);
        content.addView(card);

        foundIp = d.ip;
        conn.connect(d.ip, true);
    }

    // ------------------------------------------------------------ 4. 成功 / 失败
    private void showSuccess(String ip) {
        step("添加完成");
        content.removeAllViews();

        LinearLayout box = u.column();
        box.setGravity(Gravity.CENTER);
        TextView check = u.text("✓", 34, p.onPrimary, true);
        check.setGravity(Gravity.CENTER);
        check.setBackground(u.round(p.success, 40));
        box.addView(check, u.lp(u.dp(80), u.dp(80)));
        u.gap(box, 40);
        content.addView(box);

        TextView title = u.text("跑步机已添加", 20, p.onSurface, true);
        title.setGravity(Gravity.CENTER);
        content.addView(title);

        TextView sub = u.text(ip, 13, p.onSurfaceVariant);
        sub.setGravity(Gravity.CENTER);
        u.gap(sub, 8);
        content.addView(sub);

        TextView go = u.filled("开始使用");
        go.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(52)));
        u.gap(go, 24);
        go.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        content.addView(go);
    }

    private void showFail(String title, String detail) {
        stopScan();
        step("没有完成");
        content.removeAllViews();

        LinearLayout card = u.card();
        u.gap(card, 40);
        card.addView(u.text(title, 18, p.onSurface, true));
        TextView d = u.text(detail, 13, p.onSurfaceVariant);
        d.setLineSpacing(u.dp(2), 1f);
        u.gap(d, 8);
        card.addView(d);
        content.addView(card);

        TextView reprov = u.filled("重新配网");
        reprov.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(50)));
        u.gap(reprov, 16);
        reprov.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showGuide();
            }
        });
        content.addView(reprov);

        TextView retry = u.outlined("再扫一次");
        retry.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(50)));
        u.gap(retry, 10);
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showScan(null);
            }
        });
        content.addView(retry);
    }

    // ------------------------------------------------------------ 回调
    @Override
    public void onConnected() {
        connectedOnce = true;
        foundIp = conn.ip();
        getSharedPreferences("xq", MODE_PRIVATE).edit()
                .putString("ip", foundIp)
                .putBoolean("ever", true).apply();
        Logs.add("添加设备成功 " + foundIp);
        ui.post(new Runnable() {
            @Override
            public void run() {
                showSuccess(foundIp);
                setResult(RESULT_OK);
                ui.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        finish();
                    }
                }, 1200);
            }
        });
    }

    @Override
    public void onDisconnected(final String reason) {
        if (connectedOnce) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                showFail("连接失败", "设备在，但连不上：" + reason
                        + "\n可能它已经换到别的 WiFi，建议重新配网。");
            }
        });
    }

    @Override
    public void onStatus(TreadmillClient.Status s) {
    }

    @Override
    public void onFrame(byte[] payload) {
    }
}
