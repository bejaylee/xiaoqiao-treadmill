# -*- coding: utf-8 -*-
"""审查报告剩余项批量修复。所有替换尽量幂等。"""
import io

def edit(path, pairs):
    s = io.open(path, encoding="utf-8").read()
    changed = False
    for a, b in pairs:
        if a in s:
            s = s.replace(a, b, 1)
            changed = True
    if changed:
        io.open(path, "w", encoding="utf-8").write(s)
    return changed

# ---------------- S7 / 低#4：Manifest ----------------
mp = "C:/Users/bejay/WorkBuddy/spaces/2026_11_21_14-29-01/xiaoqiao/android/app/src/main/AndroidManifest.xml"
s = io.open(mp, encoding="utf-8").read()
if 'android:name="android.permission.ACCESS_FINE_LOCATION"' in s:
    s = s.replace("""    <!-- 仅用于读取当前 WiFi 名称做预填，拒绝也不影响配网（手动填写即可） -->
    <uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
""", "")
s = s.replace("@android:style/Theme.Material.Light\"", "@android:style/Theme.Material.Light.NoActionBar\"")
io.open(mp, "w", encoding="utf-8").write(s)
print("manifest: S7 + NoActionBar")

# ---------------- S7 / H1 / H5：配网页 ----------------
p = "ProvisionActivity.java"
s = io.open(p, encoding="utf-8").read()
if "FLAG_KEEP_SCREEN_ON" not in s:
    s = s.replace("import android.view.View;", "import android.view.View;\nimport android.view.WindowManager;")
    s = s.replace("""        setContentView(buildUi());
        prefill();""",
"""        setContentView(buildUi());
        // H5：配网期间保持屏幕常亮，避免系统挂起广播线程
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);""")
# 方案 B：去掉 SSID/BSSID 自动读取（无定位权限时本来也读不到）
old_prefill = """    private void prefill() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            WifiInfo info = wm.getConnectionInfo();
            String ssid = info.getSSID();
            if (ssid != null && ssid.startsWith("\\"") && ssid.endsWith("\\"")) {
                ssid = ssid.substring(1, ssid.length() - 1);
            }
            if (ssid != null && !ssid.contains("unknown")) etSsid.setText(ssid);
            String bssid = info.getBSSID();
            if (bssid != null && bssid.contains(":")) etBssid.setText(bssid);
        } catch (Exception ignored) {
        }
    }

"""
s = s.replace(old_prefill, "")
# H1：优先用 WiFi 接口地址
old_ip = """    private String localIp() {
        try {
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface nif = ifs.nextElement();
                if (!nif.isUp() || nif.isLoopback()) continue;
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
    }"""
new_ip = """    private String localIp() {
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
    }"""
s = s.replace(old_ip, new_ip)
# 结束时清掉常亮
s = s.replace("""        if (prov != null) prov.stop();
    }""", """        if (prov != null) prov.stop();
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }""")
io.open(p, "w", encoding="utf-8").write(s)
print("ProvisionActivity: S7/H1/H5")

# ---------------- H5：Esptouch 重入 + SO_REUSEADDR ----------------
p = "EsptouchProvisioner.java"
s = io.open(p, encoding="utf-8").read()
s = s.replace("""                      final String ip, final int totalSeconds) {
        running = true;""", """                      final String ip, final int totalSeconds) {
        if (running) return;                  // H5：重入保护
        running = true;""")
s = s.replace("""                    rx = new DatagramSocket(LISTEN_PORT);
                    rx.setSoTimeout(800);""", """                    rx = new DatagramSocket(null);
                    rx.setReuseAddress(true);   // H5：快速重进页面避免端口占用
                    rx.bind(new java.net.InetSocketAddress(LISTEN_PORT));
                    rx.setSoTimeout(800);""")
io.open(p, "w", encoding="utf-8").write(s)
print("EsptouchProvisioner: H5")

# ---------------- M11：3分半保真 ----------------
p = "NaturalPlan.java"
s = io.open(p, encoding="utf-8").read()
s = s.replace("""            int minInt = (int) Math.round(minutes);
            if (minInt < 1) minInt = 1;
            if (minInt > 60) minInt = 60;
            if (speed < 1) speed = 1;
            if (speed > 12) speed = 12;
            out.add(PlanRunner.Seg.ofSeconds(speed, minInt * 60));""",
"""            // M11：按秒保真，3分半就是 210 秒，不再四舍五入成 4 分钟
            int seconds = (int) Math.round(minutes * 60);
            if (seconds < 60) seconds = 60;
            if (seconds > 3600) seconds = 3600;
            if (speed < 1) speed = 1;
            if (speed > 12) speed = 12;
            out.add(PlanRunner.Seg.ofSeconds(speed, seconds));""")
io.open(p, "w", encoding="utf-8").write(s)
print("NaturalPlan: M11")

# ---------------- M12：Ui.gap 参数命名 ----------------
p = "Ui.java"
s = io.open(p, encoding="utf-8").read()
s = s.replace("""    public void gap(View v, float dp) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();""",
"""    public void gap(View v, float marginDp) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();""")
s = s.replace("        lp.topMargin = dp(dp);\n        v.setLayoutParams(lp);",
              "        lp.topMargin = dp(marginDp);\n        v.setLayoutParams(lp);")
io.open(p, "w", encoding="utf-8").write(s)
print("Ui: M12")

# ---------------- M5/M6：分享卡 ----------------
p = "ShareCard.java"
s = io.open(p, encoding="utf-8").read()
s = s.replace("""        p.setTextSize(74);
        c.drawText(r.name, 90, 280, p);""",
"""        p.setTextSize(74);
        String title = r.name == null ? "" : r.name;
        while (p.measureText(title) > W - 180 && p.getTextSize() > 40) {
            p.setTextSize(p.getTextSize() - 4);
        }
        c.drawText(title, 90, 280, p);
        p.setTextSize(74);""")
s = s.replace("""        // 分段时间轴（最多 8 段）
        p.setColor(0xCCFFFFFF);
        p.setTextSize(32);
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        c.drawText("本次节奏", 90, 900, p);

""", "")   # M5：删掉没有内容的死标题
s = s.replace("""            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            if (os != null) os.close();""",
"""            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) {
                // M6：pending 记录要清掉，否则相册里留垃圾
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ctx.getContentResolver().delete(uri, null, null);
                }
                return null;
            }
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.close();""")
s = s.replace("""        } catch (Exception e) {
            return null;
        }
    }

    public static void share""",
"""        } catch (Exception e) {
            return null;
        }
    }

    public static void share""")
io.open(p, "w", encoding="utf-8").write(s)
print("ShareCard: M5/M6")

# ---------------- M7 / S4：History、课程页、添加页 ----------------
p = "HistoryActivity.java"
s = io.open(p, encoding="utf-8").read()
s = s.replace("""        } else if (requestCode == 3001) {
            Toast.makeText(this, "没有存储权限，无法保存图片", Toast.LENGTH_LONG).show();
        }""",
"""        } else if (requestCode == 3001) {
            pendingRec = null;
            Toast.makeText(this, "没有存储权限，无法保存图片", Toast.LENGTH_LONG).show();
        }""")
s = s.replace("""    @Override
    protected void onCreate(Bundle b) {""", """    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
    }

    @Override
    protected void onCreate(Bundle b) {""")
io.open(p, "w", encoding="utf-8").write(s)

for p in ("CourseActivity.java", "AddDeviceActivity.java"):
    s = io.open(p, encoding="utf-8").read()
    if "removeCallbacksAndMessages" not in s:
        s = s.replace("""    protected void onDestroy() {
        super.onDestroy();""", """    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);      // S4""", 1)
        io.open(p, "w", encoding="utf-8").write(s)
print("History/Course/Add: M7/S4")

# ---------------- 低#10：短跑丢弃给提示 ----------------
p = "MainActivity.java"
s = io.open(p, encoding="utf-8").read()
s = s.replace("""        if (dur < 60) return;
        RecordStore.Rec r = new RecordStore.Rec();""",
"""        if (dur < 60) {
            android.widget.Toast.makeText(this, "不足 1 分钟，不计入运动记录",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        RecordStore.Rec r = new RecordStore.Rec();""")
io.open(p, "w", encoding="utf-8").write(s)
print("MainActivity: short-run toast")
