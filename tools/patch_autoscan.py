# -*- coding: utf-8 -*-
import io

p = "MainActivity.java"
s = io.open(p, encoding="utf-8").read()

if "startAutoScan" in s:
    print("already patched")
    raise SystemExit

s = s.replace(
    "    private boolean connecting;\n    private double speed = 0;",
    "    private boolean connecting;\n"
    "    private boolean manualDisconnect;\n"
    "    private volatile boolean autoScan;\n"
    "    private double speed = 0;")

s = s.replace(
    "    @Override\n"
    "    protected void onDestroy() {\n"
    "        super.onDestroy();\n"
    "        if (client != null) client.disconnect();\n"
    "    }",
    "    @Override\n"
    "    protected void onResume() {\n"
    "        super.onResume();\n"
    "        maybeAutoConnect();\n"
    "    }\n"
    "\n"
    "    @Override\n"
    "    protected void onPause() {\n"
    "        super.onPause();\n"
    "        stopAutoScan();\n"
    "    }\n"
    "\n"
    "    @Override\n"
    "    protected void onDestroy() {\n"
    "        super.onDestroy();\n"
    "        stopAutoScan();\n"
    "        if (client != null) client.disconnect();\n"
    "    }")

old_conn = (
    "    private void connectTo(String ip) {\n"
    "        if (connecting || isConnected()) return;\n"
    "        currentIp = ip;\n"
    "        connecting = true;\n"
    "        client = new TreadmillClient(this);\n"
    "        client.connect(ip);\n"
    "    }\n"
    "\n"
    "    private void disconnect() {\n"
    "        if (client != null) client.disconnect();\n"
    "        client = null;\n"
    "        updateConnectionUi(false);\n"
    "    }")

new_conn = '''    private void connectTo(String ip) {
        connectTo(ip, false);
    }

    /** force=true 时接管正在进行的连接（扫描到的设备比旧 IP 更可信） */
    private void connectTo(String ip, boolean force) {
        if (isConnected()) return;
        if (connecting && !force) return;
        if (force && client != null) {
            client.disconnect();
            client = null;
            connecting = false;
        }
        currentIp = ip;
        connecting = true;
        tvStatus.setText("正在连接 " + ip + "…");
        client = new TreadmillClient(this);
        client.connect(ip);
    }

    /** 已配过网的设备：启动或回到前台就自己找，不用手动点扫描 */
    private void maybeAutoConnect() {
        if (isConnected() || connecting || autoScan) return;
        boolean ever = getSharedPreferences("xq", MODE_PRIVATE).getBoolean("ever", false);
        String ip = getSharedPreferences("xq", MODE_PRIVATE).getString("ip", "");
        if (!ever && (ip == null || ip.isEmpty())) return;
        startAutoScan();
    }

    private void startAutoScan() {
        if (autoScan) return;
        autoScan = true;
        if (!isConnected()) tvStatus.setText("正在寻找跑步机…");
        Logs.add("自动发现：监听 UDP " + TreadmillClient.UDP_PORT);

        new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket sock = null;
                long start = System.currentTimeMillis();
                try {
                    sock = new DatagramSocket(TreadmillClient.UDP_PORT);
                    sock.setSoTimeout(1000);
                    byte[] buf = new byte[64];
                    while (autoScan && !isConnected() && System.currentTimeMillis() - start < 12000) {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        try {
                            sock.receive(pkt);
                        } catch (java.net.SocketTimeoutException te) {
                            continue;
                        }
                        final TreadmillClient.Device d = TreadmillClient.parseDiscovery(pkt);
                        if (d == null) continue;
                        Logs.add("自动发现设备 " + d.ip);
                        autoScan = false;
                        ui.post(new Runnable() {
                            @Override
                            public void run() {
                                connectTo(d.ip, true);
                            }
                        });
                        return;
                    }
                } catch (Exception e) {
                    Logs.add("自动发现失败: " + e.getMessage());
                } finally {
                    autoScan = false;
                    if (sock != null) sock.close();
                }
            }
        }, "xq-auto-scan").start();
    }

    private void stopAutoScan() {
        autoScan = false;
    }

    private void disconnect() {
        manualDisconnect = true;
        stopAutoScan();
        if (client != null) client.disconnect();
        client = null;
        updateConnectionUi(false);
    }'''

if old_conn in s:
    s = s.replace(old_conn, new_conn)
else:
    print("WARN connect block not found")

s = s.replace(
    "    public void onConnected() {\n        connecting = false;",
    "    public void onConnected() {\n        connecting = false;\n        autoScan = false;\n        manualDisconnect = false;")

old_disc = '''    public void onDisconnected(final String reason) {
        connecting = false;
        ui.post(new Runnable() {
            @Override
            public void run() {
                boolean hadDevice = currentIp != null && !currentIp.isEmpty();
                updateConnectionUi(false);
                if (hadDevice && tvEmptyDesc != null) {
                    tvEmptyDesc.setText("上次连接的设备没响应，可能路由器换了 WiFi");
                }
                Logs.add("断开：" + reason);
            }
        });
    }'''

new_disc = '''    public void onDisconnected(final String reason) {
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
                if (!manual) {
                    ui.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            maybeAutoConnect();
                        }
                    }, 3000);
                }
            }
        });
    }'''

if old_disc in s:
    s = s.replace(old_disc, new_disc)
else:
    print("WARN disconnect block not found")

io.open(p, "w", encoding="utf-8").write(s)
print("patched")
