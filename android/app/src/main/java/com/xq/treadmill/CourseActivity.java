package com.xq.treadmill;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/**
 * 课程页：推荐课程 + 自定义（自然语言）。
 *
 * 预设课程是设备端执行（0x12 一帧下发），与之前验证过的机制一致。
 */
public class CourseActivity extends Activity implements ConnectionManager.Listener {

    private static final String PREF_CUSTOM = "custom_text";

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Ui u;
    private Palette p;

    private TextView tvConn, btnBack, tvProgress, btnStop;
    private ScrollView scroller;
    private LinearLayout tabRow, body;

    private ConnectionManager conn;
    private PlanRunner runner;

    private int tab = 0;                 // 0 = 精选课程, 1 = 自定义
    private String expandedId = null;
    private int activeSeg = -1;
    private int lastAnnounced = -1;
    private boolean metroStartedHere;
    private volatile int lastCounted;
    private String currentName = "";
    private String currentCourseId;
    private List<PlanRunner.Seg> currentSegs;
    private List<CourseLibrary.Step> currentSteps;
    private VoiceCoach voice;
    private Metronome metro;
    private TextView btnVoice, btnMetro, tvBpm, chipAuto, chipManual;

    private EditText etCustom;
    private TextView tvPreview;

    @Override
    protected void onCreate(Bundle b) {
        ThemeManager.apply(this);
        super.onCreate(b);
        u = new Ui(this);
        p = u.p;
        setContentView(buildUi());
        voice = VoiceCoach.get(this);
        metro = Metronome.get();
        conn = ConnectionManager.get();
        conn.register(this);
        if (!conn.isConnected()) conn.autoFind(this);
        tvConn.setText(conn.isConnected() ? "已连接" : "正在寻找跑步机…");
        // 从「课程」tab 点进来时直接展开对应课程 / 切到自定义
        String id = getIntent().getStringExtra(CoursePage.EXTRA_COURSE_ID);
        if (id != null && !id.isEmpty()) expandedId = id;
        if (getIntent().getBooleanExtra(CoursePage.EXTRA_CUSTOM, false)) tab = 1;
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacksAndMessages(null);      // S4
        if (runner != null) runner.stop();
        // M3：只停本页自己启动的节拍器，不动主页的
        if (metroStartedHere && metro != null) metro.stop();
        if (voice != null) voice.stop();
        if (conn != null) conn.unregister(this);
    }

    private View buildUi() {
        ScrollView sv = new ScrollView(this);
        scroller = sv;
        sv.setBackgroundColor(p.bg);
        sv.setFillViewport(true);
        LinearLayout root = u.column();
        u.pad(root, 20, 8, 20, 24);
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
        LinearLayout col = u.column();
        col.addView(u.headline("课程", 24));
        tvConn = u.text("连接中…", 12, p.onSurfaceVariant);
        u.gap(tvConn, 2);
        col.addView(tvConn);
        LinearLayout.LayoutParams hlp = u.lpWeight(1);
        hlp.leftMargin = u.dp(12);
        head.addView(col, hlp);
        root.addView(head);

        tvProgress = u.text("", 13, p.primary);
        u.gap(tvProgress, 10);
        root.addView(tvProgress);

        btnStop = u.danger("停止课程");
        btnStop.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(46)));
        u.gap(btnStop, 10);
        btnStop.setVisibility(View.GONE);
        btnStop.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (runner != null) runner.stop();
            }
        });
        root.addView(btnStop);

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
        btnMetro = u.chip("启用", false);
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
        });

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
                    metroStartedHere = false;
                } else {
                    metro.start();
                    metroStartedHere = true;
                }
                updateMetroUi();
            }
        });
        minus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                metro.setBpm(metro.getBpm() - 5);
                updateMetroUi();
            }
        });
        plus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                metro.setBpm(metro.getBpm() + 5);
                updateMetroUi();
            }
        });

        tabRow = u.row();
        u.gap(tabRow, 14);
        root.addView(tabRow);

        body = u.column();
        u.gap(body, 12);
        root.addView(body);

        return sv;
    }

    // ------------------------------------------------------------ 渲染
    private void render() {
        renderTabs();
        body.removeAllViews();
        if (tab == 0) renderCourses();
        else renderCustom();
    }

    private void renderTabs() {
        tabRow.removeAllViews();
        String[] names = {"推荐课程 " + CourseLibrary.ALL.size(), "自定义"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            TextView c = u.chip(names[i], tab == i);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (tab == idx) return;
                    tab = idx;
                    render();
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.weight = 1;
            if (i > 0) lp.leftMargin = u.dp(8);
            tabRow.addView(c, lp);
        }
    }

    private void renderCourses() {
        int index = 0;
        for (final CourseLibrary.Course c : CourseGroups.flat()) {
            LinearLayout card = u.card();
            card.setClickable(true);

            LinearLayout head = u.row();
            LinearLayout col = u.column();
            TextView name = u.text(c.name, 17, p.onSurface, true);
            col.addView(name);
            TextView meta = u.text(String.format(java.util.Locale.US,
                    "%s · %d 段 · 峰值 %.1f · %s 千卡",
                    fmt(c.totalSeconds()), c.steps.size(), c.peakSpeed(), c.kcal), 12, p.onSurfaceVariant);
            u.gap(meta, 3);
            col.addView(meta);
            head.addView(col, u.lpWeight(1));
            head.addView(u.badge(c.level, p.surfaceVariant, p.onSurfaceVariant));
            card.addView(head);

            if (c.id.equals(expandedId)) {
                // 阶段概览（一行文字，避免 chip 溢出）
                StringBuilder ps = new StringBuilder();
                for (String[] ph : c.phases()) {
                    if (ps.length() > 0) ps.append(" · ");
                    ps.append(ph[0]).append(" ").append(fmt(Integer.parseInt(ph[1])));
                }
                TextView phaseLine = u.text(ps.toString(), 12, p.onSurfaceVariant);
                u.gap(phaseLine, 10);
                card.addView(phaseLine);

                // 分段
                LinearLayout segs = u.column();
                u.gap(segs, 10);
                for (int i = 0; i < c.steps.size(); i++) {
                    CourseLibrary.Step s = c.steps.get(i);
                    boolean act = i == activeSeg;
                    LinearLayout row = u.row();
                    row.setBackground(u.round(act ? p.primaryContainer : p.surfaceVariant, 12));
                    u.pad(row, 12, 9, 12, 9);
                    TextView num = u.text(String.valueOf(i + 1), 12,
                            act ? p.onPrimary : p.onSurfaceVariant, true);
                    num.setGravity(Gravity.CENTER);
                    num.setBackground(u.round(act ? p.primary : p.surface, 9));
                    row.addView(num, u.lp(u.dp(22), u.dp(22)));
                    TextView act1 = u.text(s.action, 13,
                            act ? p.onPrimaryContainer : p.onSurfaceVariant);
                    LinearLayout.LayoutParams a1 = u.lpWeight(1);
                    a1.leftMargin = u.dp(10);
                    row.addView(act1, a1);
                    TextView sp = u.text(String.format(java.util.Locale.US, "%.1f", s.speed), 14,
                            act ? p.onPrimaryContainer : p.onSurface, true);
                    row.addView(sp);
                    TextView du = u.text("  " + fmt(s.seconds), 12,
                            act ? p.onPrimaryContainer : p.onSurfaceVariant);
                    row.addView(du);
                    if (i > 0) {
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                        lp.topMargin = u.dp(6);
                        segs.addView(row, lp);
                    } else {
                        segs.addView(row);
                    }
                }
                card.addView(segs);

                TextView start = u.filled("开始这节课程");
                start.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(50)));
                u.gap(start, 12);
                start.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        startCourse(c, (TextView) v);
                    }
                });
                card.addView(start);
            }

            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (runner != null && runner.isRunning()) {
                        log("课程进行中，先停止");
                        return;
                    }
                    expandedId = c.id.equals(expandedId) ? null : c.id;
                    activeSeg = -1;
                    render();
                }
            });
            if (index++ > 0) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = u.dp(12);
                body.addView(card, lp);
            } else {
                body.addView(card);
            }
        }
    }

    private void renderCustom() {
        LinearLayout card = u.card();
        card.addView(u.label("用一句话描述"));
        TextView hint = u.text("例如：热身 5 分钟，然后 6 公里快走 20 分钟，最后放松 5 分钟",
                12, p.onSurfaceVariant);
        u.gap(hint, 4);
        card.addView(hint);

        etCustom = u.inputMultiline("热身 5 分钟，然后 6 公里快走 20 分钟");
        etCustom.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        u.gap(etCustom, 10);
        SharedPreferences sp = getSharedPreferences("course", MODE_PRIVATE);
        String saved = sp.getString(PREF_CUSTOM, "");
        if (!saved.isEmpty()) etCustom.setText(saved);
        card.addView(etCustom);

        TextView btnPreview = u.outlined("生成预览");
        btnPreview.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(46)));
        u.gap(btnPreview, 10);
        btnPreview.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updatePreview();
                getSharedPreferences("course", MODE_PRIVATE).edit()
                        .putString(PREF_CUSTOM, etCustom.getText().toString()).apply();
            }
        });
        card.addView(btnPreview);

        tvPreview = u.text("", 13, p.onSurfaceVariant);
        u.gap(tvPreview, 10);
        card.addView(tvPreview);

        TextView start = u.filled("开始自定义课程");
        start.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(50)));
        u.gap(start, 12);
        start.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NaturalPlan.Result r = NaturalPlan.parse(etCustom.getText().toString());
                if (!r.ok()) {
                    log("没看懂这段描述");
                    return;
                }
                getSharedPreferences("course", MODE_PRIVATE).edit()
                        .putString(PREF_CUSTOM, etCustom.getText().toString()).apply();
                currentSteps = null;
                currentCourseId = null;
                startSegments("自定义", r.days.get(0), (TextView) v);
            }
        });
        card.addView(start);
        body.addView(card);
        updatePreview();
    }

    private void updatePreview() {
        NaturalPlan.Result r = NaturalPlan.parse(etCustom.getText().toString());
        if (!r.ok()) {
            tvPreview.setText("例：热身 5 分钟，6 公里快走 20 分钟");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int i = 0;
        for (PlanRunner.Seg s : r.days.get(0)) {
            if (i > 0) sb.append("\n");
            sb.append(String.format(java.util.Locale.US, "%d. %.1f km/h · %s",
                    ++i, s.speed, fmt(s.seconds)));
            if (i >= 8) break;
        }
        sb.append("\n共 ").append(fmt(r.totalSeconds()));
        tvPreview.setText(sb.toString());
    }

    // ------------------------------------------------------------ 执行
    private void startCourse(CourseLibrary.Course c, TextView btn) {
        if (runner != null && runner.isRunning()) {
            log("课程进行中，请先停止");
            return;
        }
        currentSteps = c.steps;
        currentCourseId = c.id;
        startSegments(c.name, c.segments(), btn);
    }

    private void startSegments(String name, List<PlanRunner.Seg> segs, TextView btn) {
        if (!conn.isConnected()) {
            log("还没连上跑步机，请回主页连接");
            return;
        }
        if (runner != null && runner.isRunning()) {
            log("已有课程在跑");
            return;
        }
        activeSeg = 0;
        lastAnnounced = -1;
        lastCounted = 0;
        currentName = name;
        currentSegs = segs;
        int totalSec = 0;
        for (PlanRunner.Seg sg : segs) totalSec += sg.seconds;
        getSharedPreferences("xq", MODE_PRIVATE).edit()
                .putBoolean("free_session_killed", true).apply();
        voice.speak(VoiceLines.startFor(currentCourseId));
        // 课程期间保持亮屏，避免息屏断连与「停止课程」点不到
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runner = new PlanRunner(conn.client(), listener);
        runner.start(segs, 1, 0);
        btnStop.setVisibility(View.VISIBLE);
        log("开始：" + name + "（" + segs.size() + " 段）");
        render();
        // 开跑后滚回顶部：进度和「停止课程」必须立刻可见，不能藏在列表中间
        scroller.post(new Runnable() {
            @Override
            public void run() {
                scroller.smoothScrollTo(0, 0);
            }
        });
    }

    private final PlanRunner.Listener listener = new PlanRunner.Listener() {
        @Override
        public void onProgress(int segIndex, int segTotal, double targetKmh,
                               int countedSec, int totalSec, int percent, boolean frozen) {
            final int remain = Math.max(0, totalSec - countedSec);
            final String text = String.format(java.util.Locale.US,
                    "第 %d/%d 段 · %.1f km/h · 剩余 %s · %d%%%s",
                    segIndex + 1, segTotal, targetKmh, fmt(remain), percent,
                    frozen ? " · 无人已暂停" : "");
            ui.post(new Runnable() {
                @Override
                public void run() {
                    tvProgress.setText(text);
                }
            });
            lastCounted = countedSec;
            metro.setSpeed(targetKmh);
            ui.post(new Runnable() {
                @Override
                public void run() {
                    updateMetroUi();
                }
            });
            if (segIndex != lastAnnounced) {
                lastAnnounced = segIndex;
                String line = VoiceLines.segFor(currentCourseId, segIndex);
                if (line == null) line = VoiceLines.segCustom(targetKmh);
                voice.speak(line);
            }
            if (segIndex != activeSeg) {
                activeSeg = segIndex;
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (tab == 0) render();
                    }
                });
            }
        }

        @Override
        public void onFinish(final String reason) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    boolean done = reason != null && reason.startsWith("完成");
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    tvProgress.setText(done ? "课程完成" : "已停止");
                    btnStop.setVisibility(View.GONE);
                    activeSeg = -1;
                    if (tab == 0) render();

                    if (currentSegs != null) {
                        saveRunRecord(currentName, currentSegs, lastCounted);
                    }
                    voice.speak(done ? VoiceLines.FINISH : VoiceLines.STOP);
                    log("结束：" + reason);
                }
            });
        }
    };

    // ------------------------------------------------------------ 连接
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

    private void log(String s) {
        Logs.add(s);
    }

    private static String fmt(int seconds) {
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }

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

    private int uiBpm = -1;
    private boolean uiRunning, uiAuto;

    private void updateMetroUi() {
        if (tvBpm == null) return;
        int bpm = metro.getBpm();
        boolean running = metro.isRunning();
        boolean auto = metro.isAuto();
        if (bpm == uiBpm && running == uiRunning && auto == uiAuto) return;
        uiBpm = bpm;
        uiRunning = running;
        uiAuto = auto;
        tvBpm.setText(bpm > 0 ? String.valueOf(bpm) : "--");
        btnMetro.setText(running ? "停用" : "启用");
        chipAuto.setTextColor(auto ? p.onPrimaryContainer : p.onSurfaceVariant);
        chipAuto.setBackground(auto ? u.round(p.primaryContainer, 16)
                : u.round(android.graphics.Color.TRANSPARENT, 16, p.outline, 1));
        chipManual.setTextColor(!auto ? p.onPrimaryContainer : p.onSurfaceVariant);
        chipManual.setBackground(!auto ? u.round(p.primaryContainer, 16)
                : u.round(android.graphics.Color.TRANSPARENT, 16, p.outline, 1));
    }
}
