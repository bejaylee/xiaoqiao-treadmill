# -*- coding: utf-8 -*-
"""S1/H2/M3/M8：课程页与添加设备页迁到 ConnectionManager。"""
import io

# ---------------- CourseActivity ----------------
p = "CourseActivity.java"
s = io.open(p, encoding="utf-8").read()
if "ConnectionManager.Listener" in s:
    print("CourseActivity already migrated")
else:
    s = s.replace("public class CourseActivity extends Activity implements TreadmillClient.Listener {",
                  "public class CourseActivity extends Activity implements ConnectionManager.Listener {")
    s = s.replace("    private TreadmillClient client;", "    private ConnectionManager conn;")
    s = s.replace("""        voice = VoiceCoach.get(this);
        metro = Metronome.get();
        connect();""",
"""        voice = VoiceCoach.get(this);
        metro = Metronome.get();
        conn = ConnectionManager.get();
        conn.register(this);
        if (!conn.isConnected()) conn.autoFind(this);
        tvConn.setText(conn.isConnected() ? "已连接" : "正在寻找跑步机…");""")
    s = s.replace("""        if (runner != null) runner.stop();
        if (metro != null) metro.stop();
        if (voice != null) voice.stop();
        if (client != null) client.disconnect();""",
"""        if (runner != null) runner.stop();
        // M3：只停本页自己启动的节拍器，不动主页的
        if (metroStartedHere && metro != null) metro.stop();
        if (voice != null) voice.stop();
        if (conn != null) conn.unregister(this);""")
    s = s.replace("    private int lastAnnounced = -1;",
                  "    private int lastAnnounced = -1;\n    private boolean metroStartedHere;")
    s = s.replace("""                } else {
                    metro.setSpeed(speed > 0.2 ? speed : 0);
                    metro.start();
                }""",
"""                } else {
                    metroStartedHere = true;
                    metro.start();
                }""")
    # 课程页的节拍器开按钮（若存在）
    s = s.replace("""                } else {
                    metro.start();
                    btnMetro.setText("停止");
                }""",
"""                } else {
                    metroStartedHere = true;
                    metro.start();
                    btnMetro.setText("停止");
                }""")
    s = s.replace("""        if (client == null || !client.isConnected()) {
            log("还没连上跑步机，请回主页连接");""",
"""        if (!conn.isConnected()) {
            log("还没连上跑步机，请回主页连接");""")
    s = s.replace("runner = new PlanRunner(client, listener);",
                  "runner = new PlanRunner(conn.client(), listener);")
    # 删除本地 connect()
    start = s.find("    // ------------------------------------------------------------ 连接")
    end = s.find("    private void log(String s) {", start)
    if start > 0 and end > start:
        s = s[:start] + '''    // ------------------------------------------------------------ 连接
    @Override
    public void onConnected() {
        if (!isDestroyed()) tvConn.setText("已连接");
    }

    @Override
    public void onDisconnected(String reason) {
        if (!isDestroyed()) tvConn.setText("连接断开，正在重连");
    }

    @Override
    public void onStatus(TreadmillClient.Status s) {
    }

    @Override
    public void onFrame(byte[] payload) {
    }

''' + s[end:]
    io.open(p, "w", encoding="utf-8").write(s)
    print("CourseActivity migrated")

# ---------------- AddDeviceActivity ----------------
p = "AddDeviceActivity.java"
s = io.open(p, encoding="utf-8").read()
if "ConnectionManager.Listener" in s:
    print("AddDeviceActivity already migrated")
else:
    s = s.replace("public class AddDeviceActivity extends Activity implements TreadmillClient.Listener {",
                  "public class AddDeviceActivity extends Activity implements ConnectionManager.Listener {")
    s = s.replace("    private TreadmillClient client;", "    private ConnectionManager conn;")
    s = s.replace("""        setContentView(buildUi());
        showChoose();""",
"""        setContentView(buildUi());
        conn = ConnectionManager.get();
        conn.register(this);
        showChoose();""")
    s = s.replace("""        stopScan();
        if (client != null) client.disconnect();""",
"""        stopScan();
        if (conn != null) conn.unregister(this);""")
    # H2：超时判断移到循环顶部
    s = s.replace("""                    while (scanning) {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        try {
                            sock.receive(pkt);
                        } catch (java.net.SocketTimeoutException te) {
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
                            continue;
                        }""",
"""                    while (scanning) {
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
                        }""")
    # H8：SO_REUSEADDR
    s = s.replace("sock = new DatagramSocket(TreadmillClient.UDP_PORT);",
                  "sock = new DatagramSocket(null);\n                    sock.setReuseAddress(true);\n                    sock.bind(new java.net.InetSocketAddress(TreadmillClient.UDP_PORT));")
    # 连接走管理器
    s = s.replace("""        client = new TreadmillClient(this);
        client.connect(d.ip);""",
"""        foundIp = d.ip;
        conn.connect(d.ip, true);""")
    s = s.replace("""        if (client != null && !connectedOnce) {
            client.disconnect();
            client = null;
        }
""", "")
    s = s.replace("""        getSharedPreferences("xq", MODE_PRIVATE).edit()
                .putString("ip", foundIp)
                .putBoolean("ever", true).apply();""",
"""        foundIp = conn.ip();
        getSharedPreferences("xq", MODE_PRIVATE).edit()
                .putString("ip", foundIp)
                .putBoolean("ever", true).apply();""")
    io.open(p, "w", encoding="utf-8").write(s)
    print("AddDeviceActivity migrated")
