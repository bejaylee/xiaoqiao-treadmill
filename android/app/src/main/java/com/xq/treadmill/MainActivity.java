package com.xq.treadmill;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主容器：顶部标题栏 + 内容区 + 底部 4 tab 导航。
 *
 * 运动 / 课程 / 记录 / 我的 四个页面各司其职，避免单页堆控件。
 * 二级页（课程运行、添加设备、配网）仍用顶部返回。
 */
public class MainActivity extends Activity implements ConnectionManager.Listener {

    private static final int REQ_ADD = 1001;
    private static final String[] TAB_NAMES = {"运动", "课程", "记录", "我的"};
    private static final String[] TAB_ICONS = {"◎", "▤", "▦", "⚙"};

    private final Handler ui = new Handler(Looper.getMainLooper());

    private Ui u;
    private Palette p;

    private TextView tvTitle, tvConnBadge;
    private FrameLayout pageHost;
    private LinearLayout navHost;
    private static int lastTab = 0;
    private int tab = 0;
    private final View[] pages = new View[4];
    private RecordPage recordPage;

    // ---- 运动页控件 ----
    private LinearLayout emptyCard;
    private TextView tvEmptyDesc;
    private TextView tvSpeed, tvSpeedSub;
    private TextView btnMinus, btnPlus, btnRun;
    private TextView tvDur, tvDist, tvKcal, tvSteps, tvHr;
    private TextView[] timeChips;
    private TextView btnMetro, tvBpm, chipAuto, chipManual;
    private int uiBpm = -1;
    private boolean uiRunning, uiAuto;

    private ConnectionManager conn;
    private String currentIp = "";
    private double speed = 0;
    private double lastSpeed = 4.0;

    private Metronome metro;
    private boolean runActive;
    private boolean stopRequested;
    private int baseDuration, baseMileage, baseCalorie;
    private long runStartTs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.apply(this);
        super.onCreate(savedInstanceState);
        u = new Ui(this);
        p = u.p;
        metro = Metronome.get(this);
        conn = ConnectionManager.get();
        conn.register(this);
        currentIp = conn.ip();

        setContentView(buildRoot());
        pages[0] = buildSportPage();
        pages[1] = CoursePage.create(this, u, p);
        recordPage = new RecordPage(this, u, p);
        pages[2] = recordPage.getView();
        pages[3] = MinePage.create(this, u, p);

        updateConnectionUi(conn.isConnected());
        if (!conn.isConnected()) {
            boolean ever = getSharedPreferences("xq", MODE_PRIVATE).getBoolean("ever", false);
            String saved = getSharedPreferences("xq", MODE_PRIVATE).getString("ip", "");
            if (!ever && (saved == null || saved.isEmpty())) Logs.add("首次使用");
        }
        conn.autoFind(this);
        switchTab(lastTab);
    }

    private final CourseSession.Listener courseListener = new CourseSession.Listener() {
        @Override
        public void onProgress(int segIndex, int segTotal, double targetKmh,
                               int countedSec, int totalSec, int percent, boolean frozen) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (tab == 0 && !CourseSession.get().isRunning()) return;
                    tvSpeedSub.setText("课程进行中 · 第 " + (segIndex + 1) + " 段");
                    btnRun.setEnabled(false);
                    btnRun.setAlpha(0.4f);
                }
            });
        }

        @Override
        public void onFinish(String reason) {
            ui.post(new Runnable() {
                @Override
                public void run() {
                    btnRun.setEnabled(conn.isConnected());
                    btnRun.setAlpha(conn.isConnected() ? 1f : 0.4f);
                    updateRunButton();
                    updateConnectionUi(conn.isConnected());
                }
            });
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        android.content.SharedPreferences sp = getSharedPreferences("xq", MODE_PRIVATE);
        if (sp.getBoolean("free_session_killed", false)) {
            sp.edit().putBoolean("free_session_killed", false).apply();
            runActive = false;
        }
        if (metro != null) updateMetroUi();
        // 课程进行中：提示用户点课程页查看沉浸进度，别误开自由跑
        CourseSession cs = CourseSession.get();
        cs.attach(this);
        if (cs.isRunning()) {
            tvSpeedSub.setText("课程进行中 · 第 " + (cs.activeSeg + 1) + " 段");
            btnRun.setEnabled(false);
            btnRun.setAlpha(0.4f);
        } else if (!conn.isConnected()) {
            conn.autoFind(this);
        }
        if (tab == 2) recordPage.refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        ui.removeCallbacksAndMessages(null);
        saveFreeRun();
        if (conn != null) {
            conn.unregister(this);
            if (isFinishing()) conn.stopAutoFind();
        }
        if (isFinishing()) {
            if (metro != null) metro.release();
            VoiceCoach.shutdownIfAny();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_ADD && resultCode == RESULT_OK) {
            String ip = getSharedPreferences("xq", MODE_PRIVATE).getString("ip", "");
            if (ip != null && !ip.isEmpty()) conn.connect(ip, true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (recordPage != null) recordPage.onRequestPermissionsResult(requestCode, grantResults);
    }

    // ------------------------------------------------------------ 容器
    private View buildRoot() {
        LinearLayout root = u.column();
        root.setBackgroundColor(p.bg);

        LinearLayout bar = u.row();
        u.pad(bar, 20, 10, 20, 8);
        LinearLayout titleCol = u.column();
        tvTitle = u.headline("运动", 24);
        titleCol.addView(tvTitle);
        TextView sub = u.text("小乔跑跑", 12, p.onSurfaceVariant);
        u.gap(sub, 2);
        titleCol.addView(sub);
        bar.addView(titleCol, u.lpWeight(1));
        tvConnBadge = u.badge("未连接", p.surfaceVariant, p.onSurfaceVariant);
        bar.addView(tvConnBadge);
        TextView themeBtn = u.circle("◐", 44, Color.TRANSPARENT, p.onSurfaceVariant,
                Palette.alpha(p.onSurfaceVariant, 30));
        themeBtn.setContentDescription("切换外观");
        themeBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ThemeManager.toggle(MainActivity.this, (ThemeManager.getMode(MainActivity.this) + 1) % 3);
            }
        });
        bar.addView(themeBtn);
        root.addView(bar);

        pageHost = new FrameLayout(this);
        root.addView(pageHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        navHost = u.column();
        root.addView(navHost);
        return root;
    }

    private void switchTab(final int idx) {
        tab = idx;
        tvTitle.setText(TAB_NAMES[idx]);
        pageHost.removeAllViews();
        if (idx == 3) pages[3] = MinePage.create(this, u, p);   // 连接状态可能变了，重建
        if (idx == 1 && pages[1] == null) pages[1] = CoursePage.create(this, u, p);
        View v = pages[idx];
        if (v != null) {
            if (v.getParent() != null) ((ViewGroup) v.getParent()).removeView(v);
            pageHost.addView(v, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        if (idx == 2) recordPage.refresh();
        lastTab = idx;
        renderNav();
    }

    private void renderNav() {
        navHost.removeAllViews();
        navHost.addView(BottomNav.create(this, u, p, TAB_NAMES, TAB_ICONS, tab,
                new BottomNav.OnTabSelected() {
                    @Override
                    public void onTab(int index) {
                        switchTab(index);
                    }
                }));
    }

    // ------------------------------------------------------------ 运动页
    private View buildSportPage() {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(p.bg);
        sv.setFillViewport(true);
        LinearLayout root = u.column();
        u.pad(root, 20, 8, 20, 20);
        sv.addView(root);

        // 空状态
        emptyCard = u.card();
        u.gap(emptyCard, 14);
        LinearLayout emptyHead = u.row();
        TextView plus = u.text("+", 22, p.onPrimaryContainer, true);
        plus.setGravity(Gravity.CENTER);
        plus.setBackground(u.round(p.primaryContainer, 24));
        emptyHead.addView(plus, u.lp(u.dp(48), u.dp(48)));
        LinearLayout emptyCol = u.column();
        emptyCol.addView(u.text("还没有连接跑步机", 17, p.onSurface, true));
        tvEmptyDesc = u.text("添加一次之后，打开 App 会自动连接", 12, p.onSurfaceVariant);
        u.gap(tvEmptyDesc, 3);
        emptyCol.addView(tvEmptyDesc);
        LinearLayout.LayoutParams elp = u.lpWeight(1);
        elp.leftMargin = u.dp(12);
        emptyHead.addView(emptyCol, elp);
        emptyCard.addView(emptyHead);

        TextView add = bigButton("添加设备", p.primary, p.onPrimary, 60, 18, true);
        u.gap(add, 16);
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivityForResult(new Intent(MainActivity.this, AddDeviceActivity.class), REQ_ADD);
            }
        });
        emptyCard.addView(add);
        TextView provision = u.textButton("给跑步机配网");
        u.gap(provision, 6);
        provision.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, ProvisionActivity.class));
            }
        });
        emptyCard.addView(provision);
        root.addView(emptyCard);

        // 速度卡
        LinearLayout speedCard = u.card();
        u.gap(speedCard, 14);
        LinearLayout speedHead = u.row();
        speedHead.addView(u.label("目标速度"), u.lpWeight(1));
        tvSpeedSub = u.text("未连接", 12, p.onSurfaceVariant);
        speedHead.addView(tvSpeedSub);
        speedCard.addView(speedHead);

        LinearLayout speedRow = u.row();
        speedRow.setGravity(Gravity.CENTER);
        u.gap(speedRow, 10);
        btnMinus = u.circle("−", 64, p.surfaceVariant, p.onSurface, Palette.alpha(p.onSurface, 26));
        btnMinus.setContentDescription("减速");
        speedRow.addView(btnMinus);
        LinearLayout speedCol = u.column();
        speedCol.setGravity(Gravity.CENTER);
        tvSpeed = u.text("0.0", 48, p.onSurface, true);
        tvSpeed.setGravity(Gravity.CENTER);
        speedCol.addView(tvSpeed);
        TextView unit = u.text("km/h", 12, p.onSurfaceVariant, true);
        unit.setGravity(Gravity.CENTER);
        speedCol.addView(unit);
        speedRow.addView(speedCol, u.lpWeight(1));
        btnPlus = u.circle("+", 64, p.primaryContainer, p.onPrimaryContainer,
                Palette.alpha(p.onPrimaryContainer, 30));
        btnPlus.setContentDescription("加速");
        speedRow.addView(btnPlus);
        speedCard.addView(speedRow);

        // 单个主行动按钮：未开始显示「开始」，运行中显示「停止」
        btnRun = bigButton("开始", p.primary, p.onPrimary, 80, 22, true);
        u.gap(btnRun, 18);
        btnRun.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isDeviceRunning()) stopRun(); else startRun();
            }
        });
        speedCard.addView(btnRun);
        // 限时（并入速度卡）
        TextView timeLabel = u.label("本次限时");
        u.gap(timeLabel, 14);
        speedCard.addView(timeLabel);
        LinearLayout chipRow = u.row();
        u.gap(chipRow, 8);
        String[] mins = {"15", "30", "45", "60"};
        timeChips = new TextView[mins.length];
        for (int i = 0; i < mins.length; i++) {
            final int m = Integer.parseInt(mins[i]);
            final int idx = i;
            TextView c = u.chip(mins[i], false);
            c.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    markTimeChip(idx);
                    send(TreadmillClient.cmdTime(m), "限时 " + m + " 分钟");
                }
            });
            timeChips[i] = c;
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.weight = 1;
            if (i > 0) lp.leftMargin = u.dp(8);
            chipRow.addView(c, lp);
        }
        speedCard.addView(chipRow);

        root.addView(speedCard);

        // 实时数据
        LinearLayout metrics = u.card();
        u.gap(metrics, 12);
        metrics.addView(u.label("本次运动"));
        LinearLayout m1 = u.row();
        u.gap(m1, 10);
        tvDur = metricCell(m1, "00:00", "时长");
        tvDist = metricCell(m1, "0.00", "公里");
        tvKcal = metricCell(m1, "0", "千卡");
        metrics.addView(m1);
        LinearLayout m2 = u.row();
        u.gap(m2, 10);
        tvSteps = metricCell(m2, "0", "步数");
        tvHr = metricCell(m2, "--", "心率");
        metrics.addView(m2);
        root.addView(metrics);

        // 节拍器
        LinearLayout metroCard = u.card();
        u.gap(metroCard, 12);
        LinearLayout metroHead = u.row();
        metroHead.addView(u.label("步频节拍器"), u.lpWeight(1));
        btnMetro = u.chip("启用", false);
        metroHead.addView(btnMetro);
        metroCard.addView(metroHead);
        LinearLayout modeRow = u.row();
        u.gap(modeRow, 8);
        chipAuto = u.chip("跟随速度", true);
        chipManual = u.chip("手动", false);
        LinearLayout.LayoutParams mc1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        mc1.weight = 1;
        LinearLayout.LayoutParams mc2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        mc2.weight = 1;
        mc2.leftMargin = u.dp(8);
        modeRow.addView(chipAuto, mc1);
        modeRow.addView(chipManual, mc2);
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
                else { metro.setSpeed(speed > 0.2 ? speed : 0); metro.start(); }
                updateMetroUi();
            }
        });
        chipAuto.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { metro.setAuto(true); updateMetroUi(); }
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
            public void onClick(View v) { metro.setBpm(metro.getBpm() - 5); updateMetroUi(); }
        });
        mPlus.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { metro.setBpm(metro.getBpm() + 5); updateMetroUi(); }
        });

        bindRepeat(btnMinus, -0.5);
        bindRepeat(btnPlus, 0.5);
        renderSpeed();
        return sv;
    }

    /** 大号行动按钮：全宽、大圆角、大字号，带 ripple */
    private TextView bigButton(String text, int bg, int fg, int heightDp, float sp, boolean bold) {
        TextView t = u.text(text, sp, fg, bold);
        t.setGravity(Gravity.CENTER);
        t.setBackground(u.ripple(bg, heightDp / 2f, Palette.alpha(fg, 40)));
        t.setClickable(true);
        t.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(heightDp)));
        return t;
    }

    private TextView metricCell(LinearLayout parent, String value, String name) {
        LinearLayout box = u.column();
        box.setGravity(Gravity.CENTER);
        box.setBackground(u.round(p.surfaceVariant, 16));
        u.pad(box, 8, 12, 8, 12);
        TextView v = u.text(value, 20, p.onSurface, true);
        v.setGravity(Gravity.CENTER);
        TextView n = u.text(name, 11, p.onSurfaceVariant);
        n.setGravity(Gravity.CENTER);
        u.gap(n, 2);
        box.addView(v);
        box.addView(n);
        parent.addView(box, u.lpWeight(1));
        return v;
    }

    private void markTimeChip(int idx) {
        for (int i = 0; i < timeChips.length; i++) {
            TextView c = timeChips[i];
            if (i == idx) {
                c.setTextColor(p.onPrimaryContainer);
                c.setBackground(u.round(p.primaryContainer, 16));
            } else {
                c.setTextColor(p.onSurfaceVariant);
                c.setBackground(u.round(Color.TRANSPARENT, 16, p.outline, 1));
            }
        }
    }

    // ------------------------------------------------------------ 速度
    private void bindRepeat(final TextView btn, final double delta) {
        btn.setOnTouchListener(new View.OnTouchListener() {
            private final Handler h = new Handler(Looper.getMainLooper());
            private Runnable rep;
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    v.setPressed(true);
                    changeSpeed(delta);
                    rep = new Runnable() {
                        @Override public void run() { changeSpeed(delta); h.postDelayed(this, 120); }
                    };
                    h.postDelayed(rep, 420);
                    return true;
                }
                if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                    v.setPressed(false);
                    if (rep != null) h.removeCallbacks(rep);
                    v.performClick();
                    return true;
                }
                return false;
            }
        });
    }

    private void changeSpeed(double delta) {
        double v = speed + delta;
        v = Math.round(v * 2) / 2.0;
        if (v < 0) v = 0;
        if (v > 12) v = 12;
        if (v == speed) return;
        speed = v;
        if (speed > 0) lastSpeed = speed;
        renderSpeed();
        metro.setSpeed(speed);
        updateMetroUi();
        scheduleSend(250);
    }

    private void renderSpeed() {
        tvSpeed.setText(String.format(java.util.Locale.US, "%.1f", speed));
        if (!conn.isConnected()) tvSpeedSub.setText("未连接");
        else if (speed <= 0) tvSpeedSub.setText("待机");
        else tvSpeedSub.setText("目标 " + String.format(java.util.Locale.US, "%.1f", speed) + " km/h");
    }

    private final Runnable doSend = new Runnable() {
        @Override
        public void run() {
            if (!conn.isConnected()) return;
            try { conn.send(TreadmillClient.cmdSpeed(speed)); }
            catch (Exception e) { Logs.add("发送失败: " + e.getMessage()); return; }
            ui.removeCallbacks(resend);
            if (speed > 0) ui.postDelayed(resend, 5000);
        }
    };

    private final Runnable resend = new Runnable() {
        @Override
        public void run() {
            if (!conn.isConnected() || speed <= 0) return;
            try {
                conn.send(TreadmillClient.cmdSpeed(speed));
                Logs.add("补发 " + speed + " km/h（起步确认）");
            } catch (Exception ignored) { }
        }
    };

    private void scheduleSend(long delay) {
        ui.removeCallbacks(doSend);
        ui.postDelayed(doSend, delay);
    }

    private void startRun() {
        if (!conn.isConnected()) { Logs.add("未连接"); return; }
        if (speed <= 0) { speed = lastSpeed > 0 ? lastSpeed : 4.0; renderSpeed(); }
        try {
            conn.send(TreadmillClient.cmdPowerOn());
            Logs.add("开始：上电 + " + speed + " km/h");
            if (!runActive) {
                TreadmillClient.Status st = conn.lastStatus();
                baseDuration = st != null ? st.duration : 0;
                baseMileage = st != null ? st.mileage : 0;
                baseCalorie = st != null ? st.calorie : 0;
                runStartTs = System.currentTimeMillis();
                runActive = true;
                stopRequested = false;
            }
                updateRunButton();
        } catch (Exception e) { Logs.add("开始失败: " + e.getClass().getSimpleName() + " / " + e.getMessage()); return; }
        // 运动期间保持亮屏：息屏会让 WiFi 休眠、断连，也会让「停止」点不到
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        scheduleSend(700);
    }

    private void stopRun() {
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!conn.isConnected()) return;
        stopRequested = true;
        // runActive 交给 saveFreeRun() 自己消费，不能在这里提前置 false
        saveFreeRun();
        if (metro != null) { metro.stop(); updateMetroUi(); }
        conn.safeStop();
        speed = 0;
        renderSpeed();
        updateRunButton();
        Logs.add("停止：减速到 0 后断电");
    }

    private void send(byte[] cmd, String what) {
        if (!conn.isConnected()) { Logs.add("还没连接跑步机"); return; }
        try { conn.send(cmd); Logs.add("已发送：" + what); }
        catch (Exception e) { Logs.add("发送失败: " + e.getMessage()); }
    }

    private void disconnect() {
        conn.disconnect(true);
        updateConnectionUi(false);
    }

    private void updateConnectionUi(boolean on) {
        if (emptyCard == null) return;
        emptyCard.setVisibility(on ? View.GONE : View.VISIBLE);
        tvConnBadge.setText(on ? "已连接" : "未连接");
        tvConnBadge.setTextColor(on ? p.onPrimaryContainer : p.onSurfaceVariant);
        tvConnBadge.setBackground(u.round(on ? p.primaryContainer : p.surfaceVariant, 12));
        if (on) {
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
        updateRunButton();
    }

    /** 运行中判定：本页启动的自由跑，或设备状态帧回报 power=2 */
    private boolean isDeviceRunning() {
        if (!conn.isConnected() || stopRequested) return false;
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

    private void updateMetroUi() {
        if (tvBpm == null || metro == null) return;
        int bpm = metro.getBpm();
        boolean running = metro.isRunning();
        boolean auto = metro.isAuto();
        if (bpm == uiBpm && running == uiRunning && auto == uiAuto) return;
        uiBpm = bpm; uiRunning = running; uiAuto = auto;
        tvBpm.setText(bpm > 0 ? String.valueOf(bpm) : "--");
        btnMetro.setText(running ? "停用" : "启用");
        chipAuto.setTextColor(auto ? p.onPrimaryContainer : p.onSurfaceVariant);
        chipAuto.setBackground(auto ? u.round(p.primaryContainer, 16)
                : u.round(Color.TRANSPARENT, 16, p.outline, 1));
        chipManual.setTextColor(!auto ? p.onPrimaryContainer : p.onSurfaceVariant);
        chipManual.setBackground(!auto ? u.round(p.primaryContainer, 16)
                : u.round(Color.TRANSPARENT, 16, p.outline, 1));
    }

    private void saveFreeRun() {
        if (!runActive) return;
        runActive = false;
        TreadmillClient.Status st = conn.lastStatus();
        long wall = (System.currentTimeMillis() - runStartTs) / 1000;
        int dev = st != null ? Math.max(0, st.duration - baseDuration) : 0;
        // 设备断电/重连会让 duration 归零，用手机时钟兜底取较大值
        int dur = (int) Math.max(wall, dev);
        double km = st != null ? Math.max(0, (st.mileage - baseMileage) * 0.01) : 0;
        int kcal = st != null ? Math.max(0, st.calorie - baseCalorie) : 0;
        if (km <= 0 && dur > 0) km = Math.max(1.0, lastSpeed) * dur / 3600.0;
        if (kcal <= 0) kcal = (int) Math.round(km * 72);
        if (dur < 60) {
            Toast.makeText(this, "不足 1 分钟，不计入运动记录", Toast.LENGTH_SHORT).show();
            return;
        }
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

    // ------------------------------------------------------------ 连接回调
    @Override
    public void onConnected() {
        if (isDestroyed()) return;
        currentIp = conn.ip();
        updateConnectionUi(true);
        renderSpeed();
        getSharedPreferences("xq", MODE_PRIVATE).edit()
                .putString("ip", currentIp).putBoolean("ever", true).apply();
        Logs.add("已连接 " + currentIp);
    }

    @Override
    public void onDisconnected(String reason) {
        if (isDestroyed()) return;
        boolean had = currentIp != null && !currentIp.isEmpty();
        updateConnectionUi(false);
        if (had && !conn.isManualStop() && tvEmptyDesc != null) {
            tvEmptyDesc.setText("上次连接的设备没响应，可能路由器换了 WiFi");
        }
        Logs.add("断开：" + reason + "（运动中保持会话，等待重连）");
        // 掉线不结算自由跑：ConnectionManager 会自动重连，
        // 会话只在用户点停止或 Activity 销毁时结算，避免记录丢失。
    }

    @Override
    public void onStatus(final TreadmillClient.Status s) {
        if (isDestroyed()) return;
        ui.post(new Runnable() {
            @Override
            public void run() {
                tvDur.setText(String.format(java.util.Locale.US, "%02d:%02d", s.duration / 60, s.duration % 60));
                tvDist.setText(String.format(java.util.Locale.US, "%.2f", s.mileage * 0.01));
                tvKcal.setText(String.valueOf(s.calorie));
                tvSteps.setText(String.valueOf(s.step));
                tvHr.setText(s.heartRate > 0 ? String.valueOf(s.heartRate) : "--");
                if (conn.isConnected()) {
                    tvSpeedSub.setText(String.format(java.util.Locale.US, "实际 %.1f · %s",
                            s.kmh(), s.powerName()));
                }
                if (s.power != 2) stopRequested = false;
                updateRunButton();
                if (metro != null && metro.isAuto()) {
                    metro.setSpeed(s.power == 2 ? s.kmh() : 0);
                    updateMetroUi();
                }
            }
        });
    }

    @Override
    public void onFrame(byte[] payload) { }
}
