package com.xq.treadmill;

import android.app.Activity;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

/**
 * 配网页：把家里的 WiFi 账号密码传给跑步机（ESP-Touch）。
 */
public class ProvisionActivity extends Activity {

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Ui u;
    private Palette p;

    private EditText etSsid, etPwd, etBssid;
    private TextView btnStart, btnBack, tvLog;
    private EsptouchProvisioner prov;

    @Override
    protected void onCreate(Bundle b) {
        ThemeManager.apply(this);
        super.onCreate(b);
        u = new Ui(this);
        p = u.p;
        setContentView(buildUi());
        // H5：配网期间保持屏幕常亮，避免系统挂起广播线程
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    // ------------------------------------------------------------ UI
    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(p.bg);
        sv.setFillViewport(true);

        LinearLayout root = u.column();
        u.pad(root, 20, 8, 20, 28);
        sv.addView(root);

        LinearLayout head = u.row();
        btnBack = u.circle("‹", 48, p.surfaceVariant, p.onSurface, Palette.alpha(p.onSurface, 26));
        btnBack.setContentDescription("返回");
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        head.addView(btnBack);
        LinearLayout headCol = u.column();
        headCol.addView(u.headline("给跑步机配网", 24));
        headCol.addView(u.text("把家里 WiFi 传给跑步机", 12, p.onSurfaceVariant));
        LinearLayout.LayoutParams hlp = u.lpWeight(1);
        hlp.leftMargin = u.dp(12);
        head.addView(headCol, hlp);
        root.addView(head);

        // 步骤
        LinearLayout steps = u.card();
        u.gap(steps, 16);
        steps.addView(u.label("开始之前"));
        String[] lines = {
                "跑步机待机时按住面板「-」键 5 秒，听到「哔」一声",
                "手机连到家里 2.4GHz 的 WiFi（不能是 5G）",
                "点下面的按钮，等 30~60 秒"
        };
        for (int i = 0; i < lines.length; i++) {
            LinearLayout row = u.row();
            u.gap(row, i == 0 ? 10 : 6);
            TextView n = u.text(String.valueOf(i + 1), 12, p.onPrimaryContainer, true);
            n.setGravity(android.view.Gravity.CENTER);
            n.setBackground(u.round(p.primaryContainer, 10));
            row.addView(n, u.lp(u.dp(22), u.dp(22)));
            TextView t = u.text(lines[i], 13, p.onSurfaceVariant);
            t.setLineSpacing(u.dp(2), 1f);
            LinearLayout.LayoutParams lp = u.lpWeight(1);
            lp.leftMargin = u.dp(10);
            row.addView(t, lp);
            steps.addView(row);
        }
        root.addView(steps);

        // 表单
        LinearLayout form = u.card();
        u.gap(form, 14);
        form.addView(u.label("WiFi 名称（SSID）"));
        etSsid = u.input("家里的 WiFi 名称", InputType.TYPE_CLASS_TEXT);
        etSsid.setSingleLine(true);
        u.gap(etSsid, 8);
        form.addView(etSsid);

        form.addView(u.label("WiFi 密码"));
        etPwd = u.input("密码", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        etPwd.setSingleLine(true);
        u.gap(etPwd, 8);
        form.addView(etPwd);

        form.addView(u.label("路由器 BSSID（可留空）"));
        etBssid = u.input("自动读取，可留空", InputType.TYPE_CLASS_TEXT);
        etBssid.setSingleLine(true);
        u.gap(etBssid, 8);
        form.addView(etBssid);
        root.addView(form);

        btnStart = u.filled("开始配网");
        btnStart.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(52)));
        u.gap(btnStart, 16);
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (prov != null) {
                    prov.stop();
                    prov = null;
                    btnStart.setText("开始配网");
                    return;
                }
                String ssid = etSsid.getText().toString().trim();
                String pwd = etPwd.getText().toString();
                String bssid = etBssid.getText().toString().trim();
                if (ssid.isEmpty()) {
                    log("请填写 WiFi 名称");
                    return;
                }
                if (bssid.isEmpty()) bssid = "00:00:00:00:00:00";
                String ip = localIp();
                log("本机 IP " + ip + "，开始广播…");
                prov = new EsptouchProvisioner(cb);
                prov.start(ssid, pwd, bssid, ip, 120);
                btnStart.setText("停止");
            }
        });
        root.addView(btnStart);

        // 日志
        LinearLayout logCard = u.card();
        u.gap(logCard, 14);
        logCard.addView(u.label("进度"));
        tvLog = u.text("", 11, p.onSurfaceVariant);
        tvLog.setLineSpacing(u.dp(3), 1f);
        u.gap(tvLog, 6);
        logCard.addView(tvLog);
        root.addView(logCard);

        return sv;
    }

    private String localIp() {
        // H1：优先 WiFi 地址，回执才会回到手机；蜂窝/VPN 地址会导致配网超时
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0) {
                return String.format(java.util.Locale.US, "%d.%d.%d.%d",
                        ip & 0xFF, (ip >> 8) & 0xFF, (ip >> 16) & 0xFF, (ip >> 24) & 0xFF);
            }
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                String n = nif.getName() == null ? "" : nif.getName().toLowerCase(java.util.Locale.US);
                if (!nif.isUp() || nif.isLoopback()) continue;
                if (n.contains("ppp") || n.contains("tun") || n.contains("rmnet")) continue;
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress() && a.getHostAddress().indexOf(':') < 0) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "192.168.1.100";
    }

    private void log(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                CharSequence old = tvLog.getText();
                String text = old == null ? "" : old.toString();
                text = text + (text.isEmpty() ? "" : "\n") + s;
                String[] lines = text.split("\n");
                if (lines.length > 10) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = lines.length - 10; i < lines.length; i++) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(lines[i]);
                    }
                    text = sb.toString();
                }
                tvLog.setText(text);
            }
        });
    }

    private final EsptouchProvisioner.Callback cb = new EsptouchProvisioner.Callback() {
        private int lastRound = -1;

        @Override
        public void onProgress(final int round, long elapsedMs) {
            if (round == lastRound) return;
            lastRound = round;
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (round == 1 || round % 3 == 0) log("第 " + round + " 轮广播…");
                }
            });
        }

        @Override
        public void onDeviceFound(final String mac, final String ip) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    log("配网成功：设备 " + mac + "  IP=" + ip);
                    if (getIntent().getBooleanExtra("auto_finish", false)) {
                        log("正在返回，接着搜索设备…");
                        setResult(RESULT_OK);
                        ui.postDelayed(new Runnable() {
                            @Override
                            public void run() {
                                finish();
                            }
                        }, 1500);
                    } else {
                        log("返回主页点扫描/连接即可控制");
                    }
                }
            });
        }

        @Override
        public void onFinish(boolean ok, final String reason) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    log("结束：" + reason);
                    btnStart.setText("开始配网");
                    prov = null;
                }
            });
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (prov != null) prov.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}
