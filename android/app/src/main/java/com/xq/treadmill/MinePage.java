package com.xq.treadmill;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** 「我的」页：设备、外观、日志、关于。 */
public class MinePage {

    public static View create(final Activity a, Ui u, final Palette p) {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(p.bg);
        sv.setFillViewport(true);
        LinearLayout root = u.column();
        u.pad(root, 20, 12, 20, 20);
        sv.addView(root);

        // 设备
        LinearLayout device = u.card();
        u.gap(device, 12);
        device.addView(u.text("设备", 17, p.onSurface, true));
        TextView state = u.text(ConnectionManager.get().isConnected()
                ? "已连接 · " + ConnectionManager.get().ip() : "未连接", 12, p.onSurfaceVariant);
        u.gap(state, 4);
        device.addView(state);
        TextView add = u.outlined("添加 / 切换设备");
        add.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(48)));
        u.gap(add, 12);
        add.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                a.startActivity(new Intent(a, AddDeviceActivity.class));
            }
        });
        device.addView(add);
        TextView provision = u.textButton("给跑步机配网");
        u.gap(provision, 4);
        provision.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                a.startActivity(new Intent(a, ProvisionActivity.class));
            }
        });
        device.addView(provision);
        root.addView(device);

        // 外观
        LinearLayout look = u.card();
        u.gap(look, 12);
        look.addView(u.text("外观", 17, p.onSurface, true));
        TextView hint = u.text("跟随系统、浅色或深色，切换后立即生效", 12, p.onSurfaceVariant);
        u.gap(hint, 4);
        look.addView(hint);
        LinearLayout row = u.row();
        u.gap(row, 10);
        String[] names = {"跟随系统", "浅色", "深色"};
        int cur = ThemeManager.getMode(a);
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            TextView c = u.chip(names[i], cur == i);
            c.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { ThemeManager.toggle(a, mode); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.weight = 1;
            if (i > 0) lp.leftMargin = u.dp(8);
            row.addView(c, lp);
        }
        look.addView(row);
        root.addView(look);

        // 语音播报
        LinearLayout voice = u.card();
        u.gap(voice, 12);
        voice.addView(u.text("语音播报", 17, p.onSurface, true));
        final VoiceCoach vc = VoiceCoach.get(a);
        final TextView voiceInfo = u.text(vc.isAvailable() ? vc.info() : vc.info(),
                12, p.onSurfaceVariant);
        voiceInfo.setLineSpacing(u.dp(2), 1f);
        u.gap(voiceInfo, 4);
        voice.addView(voiceInfo);
        TextView test = u.outlined("试听一句");
        test.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(48)));
        u.gap(test, 12);
        test.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                vc.speak(VoiceLines.SAMPLE);
                voiceInfo.setText(vc.isAvailable() ? vc.info() : "内置语音不可用");
                Toast.makeText(a, vc.isAvailable() ? "已触发语音播报" : "内置语音不可用",
                        Toast.LENGTH_SHORT).show();
            }
        });
        voice.addView(test);
        root.addView(voice);

        // 诊断
        LinearLayout diag = u.card();
        u.gap(diag, 12);
        diag.addView(u.text("诊断", 17, p.onSurface, true));
        TextView logs = u.outlined("查看运行日志");
        logs.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(48)));
        u.gap(logs, 12);
        logs.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showLogs(a, u, p); }
        });
        diag.addView(logs);
        root.addView(diag);

        // 关于
        LinearLayout about = u.card();
        u.gap(about, 12);
        about.addView(u.text("关于", 17, p.onSurface, true));
        TextView v = u.text("小乔跑步机  v1.0.1 (2)\n局域网直连 · 数据仅存本机", 12, p.onSurfaceVariant);
        v.setLineSpacing(u.dp(3), 1f);
        u.gap(v, 6);
        about.addView(v);
        root.addView(about);
        return sv;
    }

    private static void showLogs(Activity a, Ui u, Palette p) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = u.column();
        u.pad(box, 20, 18, 20, 18);
        box.setBackground(u.round(p.surface, 24));
        box.addView(u.headline("运行日志", 20));
        ScrollView sv2 = new ScrollView(a);
        TextView tv = u.text(Logs.dump().isEmpty() ? "（暂无）" : Logs.dump(), 11, p.onSurfaceVariant);
        tv.setLineSpacing(u.dp(3), 1f);
        sv2.addView(tv);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, u.dp(300));
        u.gap(sv2, 10);
        box.addView(sv2, slp);
        TextView close = u.filled("关闭");
        close.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(46)));
        u.gap(close, 10);
        close.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        box.addView(close);
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(u.round(p.surface, 24));
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }
}
