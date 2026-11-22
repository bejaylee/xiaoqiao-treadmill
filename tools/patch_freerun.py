# -*- coding: utf-8 -*-
"""自由跑：加节拍器控件 + 运动记录；顺带修线程可见性。"""
import io

# ---------------- MainActivity ----------------
p = "MainActivity.java"
s = io.open(p, encoding="utf-8").read()

if "Metronome" in s:
    print("MainActivity already patched")
else:
    # 字段
    s = s.replace(
        "    private double speed = 0;\n    private double lastSpeed = 4.0;",
        "    private double speed = 0;\n"
        "    private double lastSpeed = 4.0;\n"
        "\n"
        "    private Metronome metro;\n"
        "    private TextView btnMetro, tvBpm, chipAuto, chipManual;\n"
        "    private boolean runActive;\n"
        "    private int baseDuration, baseMileage, baseCalorie;\n"
        "    private long runStartTs;")

    # onCreate 初始化节拍器
    s = s.replace(
        "        setContentView(buildUi());\n        updateConnectionUi(false);",
        "        setContentView(buildUi());\n        metro = new Metronome();\n        updateConnectionUi(false);")

    # onDestroy：保存未结束的自由跑 + 释放节拍器
    s = s.replace(
        "    protected void onDestroy() {\n"
        "        super.onDestroy();\n"
        "        stopAutoScan();\n"
        "        if (client != null) client.disconnect();\n"
        "    }",
        "    protected void onDestroy() {\n"
        "        super.onDestroy();\n"
        "        saveFreeRun();\n"
        "        if (metro != null) metro.release();\n"
        "        stopAutoScan();\n"
        "        if (client != null) client.disconnect();\n"
        "    }")

    # 在「限时」卡片之后插入节拍器卡片
    anchor = "        root.addView(timeCard);"
    card = '''        root.addView(timeCard);

        // 步频节拍器（自由跑也能用）
        LinearLayout metroCard = u.card();
        u.gap(metroCard, 12);
        LinearLayout metroHead = u.row();
        metroHead.addView(u.label("步频节拍器"), u.lpWeight(1));
        btnMetro = u.chip("开始", false);
        metroHead.addView(btnMetro);
        metroCard.addView(metroHead);

        LinearLayout modeRow = u.row();
        u.gap(modeRow, 8);
        chipAuto = u.chip("跟随速度", true);
        chipManual = u.chip("手动", false);
        LinearLayout.LayoutParams m1 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT);
        m1.weight = 1;
        LinearLayout.LayoutParams m2 = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT);
        m2.weight = 1;
        m2.leftMargin = u.dp(8);
        modeRow.addView(chipAuto, m1);
        modeRow.addView(chipManual, m2);
        metroCard.addView(modeRow);

        LinearLayout bpmRow = u.row();
        u.gap(bpmRow, 10);
        bpmRow.setGravity(Gravity.CENTER);
        TextView mMinus = u.chip("-5", false);
        TextView mPlus = u.chip("+5", false);
        tvBpm = u.text("--", 22, p.onSurface, true);
        tvBpm.setGravity(Gravity.CENTER);
        bpmRow.addView(mMinus);
        bpmRow.addView(tvBpm, u.lpWeight(1));
        bpmRow.addView(mPlus);
        metroCard.addView(bpmRow);
        root.addView(metroCard);

        btnMetro.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (metro.isRunning()) metro.stop();
                else {
                    metro.setSpeed(speed > 0.2 ? speed : 0);
                    metro.start();
                }
                updateMetroUi();
            }
        });
        chipAuto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                metro.setAuto(true);
                updateMetroUi();
            }
        });
        chipManual.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                metro.setBpm(metro.getBpm() > 0 ? metro.getBpm() : 150);
                updateMetroUi();
            }
        });
        mMinus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                metro.setBpm(metro.getBpm() - 5);
                updateMetroUi();
            }
        });
        mPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                metro.setBpm(metro.getBpm() + 5);
                updateMetroUi();
            }
        });'''
    if anchor in s:
        s = s.replace(anchor, card, 1)
    else:
        print("WARN timeCard anchor")

    # 调速时同步节拍
    s = s.replace(
        "        renderSpeed();\n        scheduleSend(250);",
        "        renderSpeed();\n        metro.setSpeed(speed);\n        updateMetroUi();\n        scheduleSend(250);")

    # 开始自由跑：记录基线
    s = s.replace(
        "        try {\n"
        "            client.send(TreadmillClient.cmdPowerOn());\n"
        "            Logs.add(\"开始：上电 + \" + speed + \" km/h\");",
        "        try {\n"
        "            client.send(TreadmillClient.cmdPowerOn());\n"
        "            Logs.add(\"开始：上电 + \" + speed + \" km/h\");\n"
        "            if (!runActive) {\n"
        "                TreadmillClient.Status st = client.lastStatus;\n"
        "                baseDuration = st != null ? st.duration : 0;\n"
        "                baseMileage = st != null ? st.mileage : 0;\n"
        "                baseCalorie = st != null ? st.calorie : 0;\n"
        "                runStartTs = System.currentTimeMillis();\n"
        "                runActive = true;\n"
        "            }")

    # 停止自由跑：存记录 + 停节拍
    s = s.replace(
        "    private void stopRun() {\n"
        "        if (!isConnected()) return;\n"
        "        client.safeStop();\n"
        "        speed = 0;\n"
        "        renderSpeed();\n"
        "        Logs.add(\"停止：减速到 0 后断电\");\n"
        "    }",
        "    private void stopRun() {\n"
        "        if (!isConnected()) return;\n"
        "        saveFreeRun();\n"
        "        if (metro != null) {\n"
        "            metro.stop();\n"
        "            updateMetroUi();\n"
        "        }\n"
        "        client.safeStop();\n"
        "        speed = 0;\n"
        "        renderSpeed();\n"
        "        Logs.add(\"停止：减速到 0 后断电\");\n"
        "    }")

    # onStatus：喂实际速度给节拍器
    s = s.replace(
        "                if (isConnected()) {\n"
        "                    tvSpeedSub.setText(String.format(java.util.Locale.US, \"实际 %.1f · %s\",\n"
        "                            s.kmh(), s.powerName()));\n"
        "                }",
        "                if (isConnected()) {\n"
        "                    tvSpeedSub.setText(String.format(java.util.Locale.US, \"实际 %.1f · %s\",\n"
        "                            s.kmh(), s.powerName()));\n"
        "                }\n"
        "                if (metro != null && metro.isAuto() && s.power == 2) {\n"
        "                    metro.setSpeed(s.kmh());\n"
        "                    updateMetroUi();\n"
        "                }")

    # 掉线时把这次自由跑落盘
    s = s.replace(
        "                Logs.add(\"断开：\" + reason);\n"
        "                if (!manual) {",
        "                Logs.add(\"断开：\" + reason);\n"
        "                saveFreeRun();\n"
        "                if (!manual) {")

    # 辅助方法
    helper = '''
    private void updateMetroUi() {
        if (tvBpm == null || metro == null) return;
        int bpm = metro.getBpm();
        tvBpm.setText(bpm > 0 ? String.valueOf(bpm) : "--");
        btnMetro.setText(metro.isRunning() ? "停止" : "开始");
        boolean auto = metro.isAuto();
        chipAuto.setTextColor(auto ? p.onPrimaryContainer : p.onSurfaceVariant);
        chipAuto.setBackground(auto ? u.round(p.primaryContainer, 16)
                : u.round(Color.TRANSPARENT, 16, p.outline, 1));
        chipManual.setTextColor(!auto ? p.onPrimaryContainer : p.onSurfaceVariant);
        chipManual.setBackground(!auto ? u.round(p.primaryContainer, 16)
                : u.round(Color.TRANSPARENT, 16, p.outline, 1));
    }

    /** 自由跑结束：按设备累计值算这次的数据 */
    private void saveFreeRun() {
        if (!runActive) return;
        runActive = false;
        TreadmillClient.Status st = client != null ? client.lastStatus : null;
        int dur = st != null ? Math.max(0, st.duration - baseDuration)
                : (int) ((System.currentTimeMillis() - runStartTs) / 1000);
        double km = st != null ? Math.max(0, (st.mileage - baseMileage) * 0.01) : 0;
        int kcal = st != null ? Math.max(0, st.calorie - baseCalorie)
                : (int) Math.round(km * 72);
        if (dur < 60) return;
        RecordStore.Rec r = new RecordStore.Rec();
        r.ts = System.currentTimeMillis();
        r.name = "自由跑";
        r.seconds = dur;
        r.km = km;
        r.kcal = kcal;
        r.avgSpeed = dur > 0 ? km * 3600.0 / dur : 0;
        r.segments = 0;
        RecordStore.add(this, r);
        Logs.add("已记录自由跑：" + r.duration() + " / "
                + String.format(java.util.Locale.US, "%.2f", km) + " km");
    }
'''
    idx = s.rfind("}")
    s = s[:idx] + helper + s[idx:]

    io.open(p, "w", encoding="utf-8").write(s)
    print("MainActivity patched")

# ---------------- 线程可见性 ----------------
p2 = "CourseActivity.java"
s2 = io.open(p2, encoding="utf-8").read()
s2 = s2.replace("    private int lastCounted;", "    private volatile int lastCounted;")
io.open(p2, "w", encoding="utf-8").write(s2)

p3 = "Metronome.java"
s3 = io.open(p3, encoding="utf-8").read()
s3 = s3.replace("    private int bpm = 120;", "    private volatile int bpm = 120;")
s3 = s3.replace("    private double speedKmh;", "    private volatile double speedKmh;")
io.open(p3, "w", encoding="utf-8").write(s3)
print("volatile fixed")
