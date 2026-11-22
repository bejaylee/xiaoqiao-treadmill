# -*- coding: utf-8 -*-
import io

p = "MainActivity.java"
s = io.open(p, encoding="utf-8").read()
if "btnRun" in s:
    print("already patched")
    raise SystemExit

# 字段
s = s.replace("    private LinearLayout emptyCard, deviceRow;",
              "    private LinearLayout emptyCard;")
s = s.replace("    private TextView tvDevice, tvEmptyDesc, btnSwitch;",
              "    private TextView tvEmptyDesc;")
s = s.replace("    private TextView btnMinus, btnPlus, btnStart, btnStop;",
              "    private TextView btnMinus, btnPlus, btnRun;")
s = s.replace("    private boolean runActive;",
              "    private boolean runActive;\n    private boolean stopRequested;")

# 删除设备行整块
dev_block = """        // 设备行
        deviceRow = u.row();
        deviceRow.setBackground(u.round(p.surfaceVariant, 14));
        u.pad(deviceRow, 14, 10, 10, 10);
        deviceRow.addView(u.text("●", 10, p.success));
        tvDevice = u.text("", 13, p.onSurfaceVariant);
        LinearLayout.LayoutParams dlp = u.lpWeight(1);
        dlp.leftMargin = u.dp(8);
        deviceRow.addView(tvDevice, dlp);
        btnSwitch = u.textButton("切换");
        btnSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { disconnect(); }
        });
        deviceRow.addView(btnSwitch);
        root.addView(deviceRow);

"""
if dev_block not in s:
    raise SystemExit("device block not found")
s = s.replace(dev_block, "", 1)

# 单按钮替换
btn_block = """        // 大号开始 / 停止
        btnStart = bigButton("开始", p.primary, p.onPrimary, 80, 22, true);
        u.gap(btnStart, 18);
        btnStart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { startRun(); }
        });
        speedCard.addView(btnStart);

        btnStop = bigButton("停止", p.error, p.onError, 56, 17, false);
        u.gap(btnStop, 10);
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { stopRun(); }
        });
        speedCard.addView(btnStop);
"""
if btn_block not in s:
    raise SystemExit("button block not found")
new_btn = """        // 单个主行动按钮：未开始显示「开始」，运行中显示「停止」
        btnRun = bigButton("开始", p.primary, p.onPrimary, 80, 22, true);
        u.gap(btnRun, 18);
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isDeviceRunning()) stopRun(); else startRun();
            }
        });
        speedCard.addView(btnRun);
"""
s = s.replace(btn_block, new_btn, 1)

# startRun / stopRun 状态
s = s.replace("""                runStartTs = System.currentTimeMillis();
                runActive = true;
            }""", """                runStartTs = System.currentTimeMillis();
                runActive = true;
                stopRequested = false;
            }
        updateRunButton();""")

s = s.replace("""    private void stopRun() {
        if (!conn.isConnected()) return;
        saveFreeRun();""", """    private void stopRun() {
        if (!conn.isConnected()) return;
        stopRequested = true;
        runActive = false;
        saveFreeRun();""")
s = s.replace("""        conn.safeStop();
        speed = 0;
        renderSpeed();
        Logs.add("停止：减速到 0 后断电");""", """        conn.safeStop();
        speed = 0;
        renderSpeed();
        updateRunButton();
        Logs.add("停止：减速到 0 后断电");""")

# updateConnectionUi 去掉设备行与 btnStop
s = s.replace("""        emptyCard.setVisibility(on ? View.GONE : View.VISIBLE);
        deviceRow.setVisibility(on ? View.VISIBLE : View.GONE);""",
              """        emptyCard.setVisibility(on ? View.GONE : View.VISIBLE);""")
s = s.replace("""        if (on) {
            tvDevice.setText("已连接 · " + currentIp);
            tvSpeedSub.setText(speed > 0 ? "目标 " + speed + " km/h" : "待机");
        } else {
            tvSpeedSub.setText("未连接");
        }
        float alpha = on ? 1f : 0.4f;
        btnStart.setAlpha(alpha);
        btnStop.setAlpha(alpha);
        btnMinus.setAlpha(alpha);
        btnPlus.setAlpha(alpha);
        btnStart.setEnabled(on);
        btnStop.setEnabled(on);
        tvSpeed.setAlpha(on ? 1f : 0.55f);""",
"""        if (on) {
            tvSpeedSub.setText(speed > 0 ? "目标 " + speed + " km/h" : "待机");
        } else {
            tvSpeedSub.setText("未连接");
        }
        float alpha = on ? 1f : 0.4f;
        btnRun.setAlpha(alpha);
        btnMinus.setAlpha(alpha);
        btnPlus.setAlpha(alpha);
        btnRun.setEnabled(on);
        tvSpeed.setAlpha(on ? 1f : 0.55f);
        updateRunButton();""")

# onStatus 里同步按钮
s = s.replace("""                if (metro != null && metro.isAuto()) {
                    metro.setSpeed(s.power == 2 ? s.kmh() : 0);
                    updateMetroUi();
                }""", """                if (s.power != 2) stopRequested = false;
                updateRunButton();
                if (metro != null && metro.isAuto()) {
                    metro.setSpeed(s.power == 2 ? s.kmh() : 0);
                    updateMetroUi();
                }""")

# 新增状态与按钮刷新方法（插在 updateMetroUi 之前）
helper = """    /** 运行中判定：本页启动的自由跑，或设备状态帧回报 power=2 */
    private boolean isDeviceRunning() {
        if (stopRequested) return false;
        if (runActive) return true;
        TreadmillClient.Status st = conn.lastStatus();
        return st != null && st.power == 2;
    }

    private void updateRunButton() {
        if (btnRun == null) return;
        boolean running = isDeviceRunning();
        btnRun.setText(running ? "停止" : "开始");
        btnRun.setTextColor(running ? p.onError : p.onPrimary);
        btnRun.setBackground(u.ripple(running ? p.error : p.primary, Ui.RADIUS,
                Palette.alpha(running ? p.onError : p.onPrimary, 40)));
    }

"""
pos = s.find("    private void updateMetroUi() {")
if pos < 0:
    raise SystemExit("updateMetroUi not found")
s = s[:pos] + helper + s[pos:]

io.open(p, "w", encoding="utf-8").write(s)
print("MainActivity patched")
