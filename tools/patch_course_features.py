# -*- coding: utf-8 -*-
import io

p = "CourseActivity.java"
s = io.open(p, encoding="utf-8").read()

if "Metronome" in s:
    print("already patched")
    raise SystemExit

# A. 字段
s = s.replace(
    "    private int activeSeg = -1;",
    "    private int activeSeg = -1;\n"
    "    private int lastAnnounced = -1;\n"
    "    private int lastCounted;\n"
    "    private String currentName = \"\";\n"
    "    private List<PlanRunner.Seg> currentSegs;\n"
    "    private List<CourseLibrary.Step> currentSteps;\n"
    "    private VoiceCoach voice;\n"
    "    private Metronome metro;\n"
    "    private TextView btnVoice, btnMetro, tvBpm;")

# B. onCreate 初始化
s = s.replace(
    "        setContentView(buildUi());\n        connect();",
    "        setContentView(buildUi());\n"
    "        voice = VoiceCoach.get(this);\n"
    "        metro = new Metronome();\n"
    "        connect();")

# C. onDestroy
s = s.replace(
    "        if (runner != null) runner.stop();\n        if (client != null) client.disconnect();",
    "        if (runner != null) runner.stop();\n"
    "        if (metro != null) metro.release();\n"
    "        if (voice != null) voice.stop();\n"
    "        if (client != null) client.disconnect();")

# D. 陪跑卡片
anchor = "        root.addView(btnStop);"
card = """        root.addView(btnStop);

        // 陪跑：语音播报 + 步频节拍器
        LinearLayout assist = u.card();
        u.gap(assist, 14);

        LinearLayout voiceRow = u.row();
        voiceRow.addView(u.label("语音播报"), u.lpWeight(1));
        btnVoice = u.chip("开启", true);
        voiceRow.addView(btnVoice);
        assist.addView(voiceRow);

        LinearLayout metroRow = u.row();
        u.gap(metroRow, 14);
        metroRow.addView(u.label("步频节拍器"), u.lpWeight(1));
        TextView minus = u.chip("-5", false);
        tvBpm = u.text("170", 15, p.onSurface, true);
        u.pad(tvBpm, 10, 0, 10, 0);
        TextView plus = u.chip("+5", false);
        btnMetro = u.chip("开始", false);
        metroRow.addView(minus);
        metroRow.addView(tvBpm);
        metroRow.addView(plus);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = u.dp(10);
        metroRow.addView(btnMetro, mlp);
        assist.addView(metroRow);

        TextView assistHint = u.text("走路 110-130，慢跑 160-170，快跑 175-180 步/分",
                11, p.onSurfaceVariant);
        u.gap(assistHint, 8);
        assist.addView(assistHint);
        root.addView(assist);

        btnVoice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !voice.isEnabled();
                voice.setEnabled(on);
                btnVoice.setText(on ? "开启" : "关闭");
                btnVoice.setTextColor(on ? p.onPrimaryContainer : p.onSurfaceVariant);
            }
        });
        btnMetro.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (metro.isRunning()) {
                    metro.stop();
                    btnMetro.setText("开始");
                } else {
                    metro.start();
                    btnMetro.setText("停止");
                }
            }
        });
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                metro.setBpm(metro.getBpm() - 5);
                tvBpm.setText(String.valueOf(metro.getBpm()));
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                metro.setBpm(metro.getBpm() + 5);
                tvBpm.setText(String.valueOf(metro.getBpm()));
            }
        });"""
if anchor in s and "语音播报" not in s:
    s = s.replace(anchor, card, 1)
else:
    print("WARN assist anchor")

# E. startCourse 记录 course steps
s = s.replace(
    """        startSegments(c.name, c.segments(), btn);""",
    """        currentSteps = c.steps;
        startSegments(c.name, c.segments(), btn);""")

# 自定义课程：清掉 steps
s = s.replace(
    """                startSegments("自定义", r.days.get(0), (TextView) v);""",
    """                currentSteps = null;
                startSegments("自定义", r.days.get(0), (TextView) v);""")

# F. startSegments 记录上下文 + 开场播报
s = s.replace(
    """        activeSeg = 0;
        runner = new PlanRunner(client, listener);
        runner.start(segs, 1, 0);
        btnStop.setVisibility(View.VISIBLE);
        log("开始：" + name + "（" + segs.size() + " 段）");
        render();""",
    """        activeSeg = 0;
        lastAnnounced = -1;
        lastCounted = 0;
        currentName = name;
        currentSegs = segs;
        int totalSec = 0;
        for (PlanRunner.Seg sg : segs) totalSec += sg.seconds;
        voice.speak("开始" + name + "，共 " + (totalSec / 60) + " 分钟，" + segs.size() + " 个分段");
        runner = new PlanRunner(client, listener);
        runner.start(segs, 1, 0);
        btnStop.setVisibility(View.VISIBLE);
        log("开始：" + name + "（" + segs.size() + " 段）");
        render();""")

# G. 进度：记录已计秒 + 阶段播报
s = s.replace(
    """            if (segIndex != activeSeg) {
                activeSeg = segIndex;""",
    """            lastCounted = countedSec;
            if (segIndex != lastAnnounced) {
                lastAnnounced = segIndex;
                String action = "";
                if (currentSteps != null && segIndex < currentSteps.size()) {
                    action = currentSteps.get(segIndex).action;
                }
                voice.speak("第 " + (segIndex + 1) + " 段"
                        + (action.isEmpty() ? "" : "，" + action)
                        + "，" + String.format(java.util.Locale.US, "%.1f", targetKmh) + " 公里每小时");
            }
            if (segIndex != activeSeg) {
                activeSeg = segIndex;""")

# H. 完成：播报 + 存记录
s = s.replace(
    """                    tvProgress.setText(reason != null && reason.startsWith("完成")
                            ? "课程完成" : "已停止");
                    btnStop.setVisibility(View.GONE);
                    activeSeg = -1;
                    if (tab == 0) render();
                    log("结束：" + reason);""",
    """                    boolean done = reason != null && reason.startsWith("完成");
                    tvProgress.setText(done ? "课程完成" : "已停止");
                    btnStop.setVisibility(View.GONE);
                    activeSeg = -1;
                    if (tab == 0) render();

                    if (currentSegs != null) {
                        saveRunRecord(currentName, currentSegs, lastCounted);
                    }
                    if (done) {
                        voice.speak("训练完成，用时 " + fmt(lastCounted) + "，共 " + currentName);
                    } else {
                        voice.speak("训练已停止");
                    }
                    log("结束：" + reason);""")

# I. 辅助方法
helper = """
    private void saveRunRecord(String name, List<PlanRunner.Seg> segs, int countedSec) {
        if (countedSec < 60) return;
        double km = 0;
        int acc = 0;
        for (PlanRunner.Seg sg : segs) {
            int use = Math.min(sg.seconds, Math.max(0, countedSec - acc));
            km += sg.speed * use / 3600.0;
            acc += sg.seconds;
            if (acc >= countedSec) break;
        }
        RecordStore.Rec r = new RecordStore.Rec();
        r.ts = System.currentTimeMillis();
        r.name = name;
        r.seconds = countedSec;
        r.km = km;
        r.kcal = (int) Math.round(km * 72);
        r.avgSpeed = countedSec > 0 ? km * 3600.0 / countedSec : 0;
        r.segments = segs.size();
        RecordStore.add(this, r);
        log("已记录： " + r.duration() + " / " + String.format(java.util.Locale.US, "%.2f", km) + " km");
    }
"""
idx = s.rfind("}")
s = s[:idx] + helper + s[idx:]

io.open(p, "w", encoding="utf-8").write(s)
print("CourseActivity patched")
