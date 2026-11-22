# -*- coding: utf-8 -*-
"""节拍器：跟随速度 / 手动 两种模式接进课程页。"""
import io

p = "CourseActivity.java"
s = io.open(p, encoding="utf-8").read()

if "chipAuto" in s:
    print("already patched")
    raise SystemExit

# 字段
s = s.replace(
    "    private TextView btnVoice, btnMetro, tvBpm;",
    "    private TextView btnVoice, btnMetro, tvBpm, chipAuto, chipManual;")

old = '''        LinearLayout metroRow = u.row();
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
        root.addView(assist);'''

new = '''        LinearLayout metroRow = u.row();
        u.gap(metroRow, 14);
        metroRow.addView(u.label("步频节拍器"), u.lpWeight(1));
        btnMetro = u.chip("开始", false);
        metroRow.addView(btnMetro);
        assist.addView(metroRow);

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
        assist.addView(modeRow);

        LinearLayout bpmRow = u.row();
        u.gap(bpmRow, 10);
        bpmRow.setGravity(android.view.Gravity.CENTER);
        TextView minus = u.chip("-5", false);
        TextView plus = u.chip("+5", false);
        tvBpm = u.text("--", 22, p.onSurface, true);
        tvBpm.setGravity(android.view.Gravity.CENTER);
        bpmRow.addView(minus);
        bpmRow.addView(tvBpm, u.lpWeight(1));
        bpmRow.addView(plus);
        assist.addView(bpmRow);

        TextView assistHint = u.text("跟随速度时会按当前配速自动调整；点 +/- 即改为手动",
                11, p.onSurfaceVariant);
        u.gap(assistHint, 8);
        assist.addView(assistHint);
        root.addView(assist);

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
                if (metro.getBpm() <= 0) metro.setBpm(150);
                else metro.setBpm(metro.getBpm());
                updateMetroUi();
            }
        });'''

if old not in s:
    print("WARN metro block not found")
else:
    s = s.replace(old, new, 1)

# 按钮事件：开始/停止后刷新显示
s = s.replace(
    '''                if (metro.isRunning()) {
                    metro.stop();
                    btnMetro.setText("开始");
                } else {
                    metro.start();
                    btnMetro.setText("停止");
                }''',
    '''                if (metro.isRunning()) {
                    metro.stop();
                } else {
                    metro.start();
                }
                updateMetroUi();''')

# +/- 改走 setBpm（会切手动）并刷新
s = s.replace(
    '''                metro.setBpm(metro.getBpm() - 5);
                tvBpm.setText(String.valueOf(metro.getBpm()));''',
    '''                metro.setBpm(metro.getBpm() - 5);
                updateMetroUi();''')
s = s.replace(
    '''                metro.setBpm(metro.getBpm() + 5);
                tvBpm.setText(String.valueOf(metro.getBpm()));''',
    '''                metro.setBpm(metro.getBpm() + 5);
                updateMetroUi();''')

# 进度回调里喂速度
s = s.replace(
    """            lastCounted = countedSec;
            if (segIndex != lastAnnounced) {""",
    """            lastCounted = countedSec;
            metro.setSpeed(targetKmh);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    updateMetroUi();
                }
            });
            if (segIndex != lastAnnounced) {""")

# 初始化显示
s = s.replace(
    "        voice = VoiceCoach.get(this);\n        metro = new Metronome();\n        connect();",
    "        voice = VoiceCoach.get(this);\n        metro = new Metronome();\n        connect();")

# 辅助方法
helper = '''
    private void updateMetroUi() {
        if (tvBpm == null) return;
        int bpm = metro.getBpm();
        tvBpm.setText(bpm > 0 ? String.valueOf(bpm) : "--");
        btnMetro.setText(metro.isRunning() ? "停止" : "开始");
        boolean auto = metro.isAuto();
        chipAuto.setTextColor(auto ? p.onPrimaryContainer : p.onSurfaceVariant);
        chipAuto.setBackground(auto ? u.round(p.primaryContainer, 16)
                : u.round(android.graphics.Color.TRANSPARENT, 16, p.outline, 1));
        chipManual.setTextColor(!auto ? p.onPrimaryContainer : p.onSurfaceVariant);
        chipManual.setBackground(!auto ? u.round(p.primaryContainer, 16)
                : u.round(android.graphics.Color.TRANSPARENT, 16, p.outline, 1));
    }
'''
idx = s.rfind("}")
s = s[:idx] + helper + s[idx:]

io.open(p, "w", encoding="utf-8").write(s)
print("CourseActivity metro patched")
