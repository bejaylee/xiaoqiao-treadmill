# -*- coding: utf-8 -*-
"""S1：MainActivity 迁到 ConnectionManager 单例连接。"""
import io

p = "MainActivity.java"
s = io.open(p, encoding="utf-8").read()

if "ConnectionManager.Listener" in s:
    print("already migrated")
    raise SystemExit

# 类声明
s = s.replace("public class MainActivity extends Activity implements TreadmillClient.Listener {",
              "public class MainActivity extends Activity implements ConnectionManager.Listener {")

# 字段
s = s.replace("""    private TreadmillClient client;
    private String currentIp = "";
    private boolean connecting;
    private boolean manualDisconnect;
    private volatile boolean autoScan;""",
"""    private ConnectionManager conn;
    private String currentIp = "";""")

# 生命周期
s = s.replace("""        setContentView(buildUi());
        metro = Metronome.get();
        updateConnectionUi(false);

        String lastIp = getSharedPreferences("xq", MODE_PRIVATE).getString("ip", "");
        if (lastIp != null && !lastIp.isEmpty()) {
            tvStatus.setText("正在连接上次的设备…");
            connectTo(lastIp);
        }
    }""",
"""        setContentView(buildUi());
        metro = Metronome.get();
        conn = ConnectionManager.get();
        conn.register(this);
        updateConnectionUi(conn.isConnected());
        if (!conn.isConnected()) tvStatus.setText("正在寻找跑步机…");
        conn.autoFind(this);
    }""")

s = s.replace("""        if (metro != null) updateMetroUi();
        maybeAutoConnect();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopAutoScan();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        saveFreeRun();
        if (metro != null) metro.stop();
        stopAutoScan();
        if (client != null) client.disconnect();
    }""",
"""        if (metro != null) updateMetroUi();
        if (!conn.isConnected()) conn.autoFind(this);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);      // S4：清掉所有延迟任务
        saveFreeRun();
        if (conn != null) {
            conn.unregister(this);
            if (isFinishing()) conn.stopAutoFind();
        }
        if (isFinishing() && metro != null) metro.release();   // M4
    }""")

s = s.replace("""            String ip = getSharedPreferences("xq", MODE_PRIVATE).getString("ip", "");
            if (ip != null && !ip.isEmpty()) connectTo(ip);""",
"""            String ip = getSharedPreferences("xq", MODE_PRIVATE).getString("ip", "");
            if (ip != null && !ip.isEmpty()) conn.connect(ip, true);   // H4：强制切换""")

# 命令行走管理器
s = s.replace("client.send(TreadmillClient.cmdSpeed(speed))", "conn.send(TreadmillClient.cmdSpeed(speed))")
s = s.replace("client.send(TreadmillClient.cmdPowerOn())", "conn.send(TreadmillClient.cmdPowerOn())")
s = s.replace("client.send(cmd)", "conn.send(cmd)")
s = s.replace("client.safeStop()", "conn.safeStop()")
s = s.replace("TreadmillClient.Status st = client.lastStatus;", "TreadmillClient.Status st = conn.lastStatus();")
s = s.replace("TreadmillClient.Status st = client != null ? client.lastStatus : null;",
              "TreadmillClient.Status st = conn.lastStatus();")
s = s.replace("        return client != null && client.isConnected();", "        return conn != null && conn.isConnected();")

# 连接块整体替换
start = s.find("    // ------------------------------------------------------------ 连接")
end = s.find("    private void updateConnectionUi(boolean on) {", start)
if start < 0 or end < 0:
    raise SystemExit("connection block not found: %d %d" % (start, end))
new_block = '''    // ------------------------------------------------------------ 连接
    private void connectTo(String ip) {
        connectTo(ip, false);
    }

    private void connectTo(String ip, boolean force) {
        currentIp = ip;
        conn.connect(ip, force);
    }

    private void disconnect() {
        conn.disconnect(true);          // 手动切换：不触发自动重连
        updateConnectionUi(false);
    }

'''
s = s[:start] + new_block + s[end:]

# 回调
s = s.replace("""    public void onConnected() {
        connecting = false;
        autoScan = false;
        manualDisconnect = false;
        ui.post(new Runnable() {
            @Override
            public void run() {
                updateConnectionUi(true);
                if (tvEmptyDesc != null) {
                    tvEmptyDesc.setText("添加一次之后，打开 App 会自动连接");
                }
                renderSpeed();
                getSharedPreferences("xq", MODE_PRIVATE).edit()
                        .putString("ip", currentIp)
                        .putBoolean("ever", true).apply();
                Logs.add("已连接 " + currentIp);
            }
        });
    }""",
"""    public void onConnected() {
        if (isDestroyed()) return;
        currentIp = conn.ip();
        updateConnectionUi(true);
        if (tvEmptyDesc != null) {
            tvEmptyDesc.setText("添加一次之后，打开 App 会自动连接");
        }
        renderSpeed();
        getSharedPreferences("xq", MODE_PRIVATE).edit()
                .putString("ip", currentIp)
                .putBoolean("ever", true).apply();
        Logs.add("已连接 " + currentIp);
    }""")

s = s.replace("""    public void onDisconnected(final String reason) {
        connecting = false;
        final boolean manual = manualDisconnect;
        manualDisconnect = false;
        ui.post(new Runnable() {
            @Override
            public void run() {
                boolean hadDevice = currentIp != null && !currentIp.isEmpty();
                updateConnectionUi(false);
                if (hadDevice && tvEmptyDesc != null) {
                    tvEmptyDesc.setText("上次连接的设备没响应，可能路由器换了 WiFi");
                }
                Logs.add("断开：" + reason);
                saveFreeRun();
                if (!manual) {
                    // 掉线后再给一次机会：3 秒后重新广播发现 + 重连
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            maybeAutoConnect();
                        }
                    }, 3000);
                }
            }
        });
    }""",
"""    public void onDisconnected(final String reason) {
        if (isDestroyed()) return;
        boolean hadDevice = currentIp != null && !currentIp.isEmpty();
        updateConnectionUi(false);
        if (hadDevice && !conn.isManualStop() && tvEmptyDesc != null) {
            tvEmptyDesc.setText("上次连接的设备没响应，可能路由器换了 WiFi");
        }
        Logs.add("断开：" + reason);
        saveFreeRun();
        // 自动重连与退避统一由 ConnectionManager 负责（H7）
    }""")

io.open(p, "w", encoding="utf-8").write(s)
print("MainActivity migrated")
