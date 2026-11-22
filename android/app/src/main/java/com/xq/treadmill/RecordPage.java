package com.xq.treadmill;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 「记录」页：日历 / 列表 + 分享。作为主界面底部 tab 的一个页面存在。
 */
public class RecordPage {

    private final Activity a;
    private final Ui u;
    private final Palette p;
    private final View view;
    private final LinearLayout root;
    private final LinearLayout content;
    private final TextView tvSummary, tabCal, tabList;

    private int tab = 0;
    private final Calendar month = Calendar.getInstance();
    private final Calendar today = Calendar.getInstance();
    private int selectedDay;
    private List<RecordStore.Rec> all = new ArrayList<RecordStore.Rec>();
    private final Map<String, List<RecordStore.Rec>> byDay = new HashMap<String, List<RecordStore.Rec>>();
    private final SimpleDateFormat keyFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private RecordStore.Rec pendingRec;
    private boolean pendingShare;

    public RecordPage(Activity a, Ui u, Palette p) {
        this.a = a;
        this.u = u;
        this.p = p;
        selectedDay = today.get(Calendar.DAY_OF_MONTH);

        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(p.bg);
        sv.setFillViewport(true);
        root = u.column();
        u.pad(root, 20, 12, 20, 20);
        sv.addView(root);
        view = sv;

        LinearLayout tabs = u.row();
        tabCal = u.chip("日历", true);
        tabList = u.chip("列表", false);
        LinearLayout.LayoutParams t1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        t1.weight = 1;
        LinearLayout.LayoutParams t2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        t2.weight = 1;
        t2.leftMargin = u.dp(8);
        tabs.addView(tabCal, t1);
        tabs.addView(tabList, t2);
        root.addView(tabs);

        tvSummary = u.text("", 12, p.onSurfaceVariant);
        u.gap(tvSummary, 8);
        root.addView(tvSummary);

        content = u.column();
        u.gap(content, 12);
        root.addView(content);

        tabCal.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (tab != 0) { tab = 0; render(); } }
        });
        tabList.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { if (tab != 1) { tab = 1; render(); } }
        });
    }

    public View getView() {
        return view;
    }

    public void refresh() {
        all = RecordStore.all(a);
        byDay.clear();
        for (RecordStore.Rec r : all) {
            String k = keyFmt.format(new Date(r.ts));
            List<RecordStore.Rec> list = byDay.get(k);
            if (list == null) { list = new ArrayList<RecordStore.Rec>(); byDay.put(k, list); }
            list.add(r);
        }
        tvSummary.setText(all.isEmpty() ? "还没有记录"
                : String.format(Locale.US, "累计 %d 次 · %.1f 公里 · %d 千卡",
                all.size(), RecordStore.totalKm(all), RecordStore.totalKcal(all)));
        render();
    }

    private void render() {
        styleTabs();
        content.removeAllViews();
        if (tab == 0) renderCalendar(); else renderList();
    }

    private void styleTabs() {
        tabCal.setTextColor(tab == 0 ? p.onPrimaryContainer : p.onSurfaceVariant);
        tabCal.setBackground(tab == 0 ? u.round(p.primaryContainer, 16)
                : u.round(Color.TRANSPARENT, 16, p.outline, 1));
        tabList.setTextColor(tab == 1 ? p.onPrimaryContainer : p.onSurfaceVariant);
        tabList.setBackground(tab == 1 ? u.round(p.primaryContainer, 16)
                : u.round(Color.TRANSPARENT, 16, p.outline, 1));
    }

    private void renderCalendar() {
        LinearLayout card = u.card();
        u.pad(card, 12, 12, 12, 14);

        LinearLayout nav = u.row();
        TextView prev = u.circle("‹", 36, Color.TRANSPARENT, p.onSurfaceVariant,
                Palette.alpha(p.onSurfaceVariant, 26));
        TextView next = u.circle("›", 36, Color.TRANSPARENT, p.onSurfaceVariant,
                Palette.alpha(p.onSurfaceVariant, 26));
        LinearLayout titleCol = u.column();
        titleCol.setGravity(Gravity.CENTER);
        titleCol.addView(u.text(String.format(Locale.CHINA, "%d 年 %d 月",
                month.get(Calendar.YEAR), month.get(Calendar.MONTH) + 1), 15, p.onSurface, true));
        titleCol.addView(u.text(monthSummary(), 11, p.onSurfaceVariant));
        nav.addView(prev);
        nav.addView(titleCol, u.lpWeight(1));
        nav.addView(next);
        card.addView(nav);

        prev.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { month.add(Calendar.MONTH, -1); selectedDay = defaultDay(); render(); }
        });
        next.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { month.add(Calendar.MONTH, 1); selectedDay = defaultDay(); render(); }
        });

        LinearLayout week = u.row();
        u.gap(week, 8);
        String[] names = {"一", "二", "三", "四", "五", "六", "日"};
        for (String n : names) {
            TextView t = u.text(n, 11, p.onSurfaceVariant);
            t.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.weight = 1;
            week.addView(t, lp);
        }
        card.addView(week);

        Calendar c = (Calendar) month.clone();
        c.set(Calendar.DAY_OF_MONTH, 1);
        int offset = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int days = c.getActualMaximum(Calendar.DAY_OF_MONTH);
        LinearLayout row = null;
        for (int i = 0; i < offset + days; i++) {
            if (i % 7 == 0) { row = u.row(); u.gap(row, 6); card.addView(row); }
            if (i < offset) {
                TextView empty = u.text("", 12, p.onSurfaceVariant);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, u.dp(44));
                lp.weight = 1;
                row.addView(empty, lp);
                continue;
            }
            final int day = i - offset + 1;
            row.addView(dayCell(day), cellLp());
        }
        int remainder = (offset + days) % 7;
        if (remainder != 0 && row != null) {
            for (int i = remainder; i < 7; i++) {
                TextView empty = u.text("", 12, p.onSurfaceVariant);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, u.dp(44));
                lp.weight = 1;
                row.addView(empty, lp);
            }
        }
        content.addView(card);

        if (selectedDay > 0) {
            String key = String.format(Locale.US, "%04d-%02d-%02d",
                    month.get(Calendar.YEAR), month.get(Calendar.MONTH) + 1, selectedDay);
            List<RecordStore.Rec> list = byDay.get(key);
            TextView label = u.text(String.format(Locale.US, "%d 月 %d 日",
                    month.get(Calendar.MONTH) + 1, selectedDay), 15, p.onSurface, true);
            u.gap(label, 6);
            content.addView(label);
            if (list == null || list.isEmpty()) {
                TextView none = u.text("这天没有记录", 12, p.onSurfaceVariant);
                u.gap(none, 4);
                content.addView(none);
            } else {
                for (RecordStore.Rec r : list) content.addView(recordCard(r));
            }
        }
    }

    private int defaultDay() {
        Calendar now = Calendar.getInstance();
        if (month.get(Calendar.YEAR) == now.get(Calendar.YEAR)
                && month.get(Calendar.MONTH) == now.get(Calendar.MONTH)) {
            return now.get(Calendar.DAY_OF_MONTH);
        }
        return 1;
    }

    private LinearLayout.LayoutParams cellLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, u.dp(46));
        lp.weight = 1;
        return lp;
    }

    private View dayCell(final int day) {
        String key = String.format(Locale.US, "%04d-%02d-%02d",
                month.get(Calendar.YEAR), month.get(Calendar.MONTH) + 1, day);
        List<RecordStore.Rec> list = byDay.get(key);
        boolean has = list != null && !list.isEmpty();
        boolean selected = day == selectedDay;
        boolean isToday = month.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                && month.get(Calendar.MONTH) == today.get(Calendar.MONTH)
                && day == today.get(Calendar.DAY_OF_MONTH);

        LinearLayout cell = u.column();
        cell.setGravity(Gravity.CENTER);
        if (selected) cell.setBackground(u.round(p.primary, 14));
        else if (has) cell.setBackground(u.round(Palette.alpha(p.primary, heatLevel(list)), 14));
        else if (isToday) cell.setBackground(u.round(Color.TRANSPARENT, 14, p.outline, 1));

        TextView num = u.text(String.valueOf(day), 14,
                selected ? p.onPrimary : p.onSurface, selected || isToday);
        num.setGravity(Gravity.CENTER);
        cell.addView(num);

        View dot = new View(a);
        if (has) dot.setBackground(u.round(selected ? p.onPrimary : p.primary, 2));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(u.dp(4), u.dp(4));
        dlp.topMargin = u.dp(3);
        cell.addView(dot, dlp);

        cell.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { selectedDay = day; render(); }
        });
        return cell;
    }

    private int heatLevel(List<RecordStore.Rec> list) {
        int kcal = 0;
        for (RecordStore.Rec r : list) kcal += r.kcal;
        if (kcal >= 400) return 150;
        if (kcal >= 200) return 90;
        return 40;
    }

    private String monthSummary() {
        int count = 0;
        double km = 0;
        for (Map.Entry<String, List<RecordStore.Rec>> e : byDay.entrySet()) {
            String[] parts = e.getKey().split("-");
            if (Integer.parseInt(parts[0]) == month.get(Calendar.YEAR)
                    && Integer.parseInt(parts[1]) - 1 == month.get(Calendar.MONTH)) {
                for (RecordStore.Rec r : e.getValue()) { count++; km += r.km; }
            }
        }
        return count == 0 ? "本月还没有训练"
                : String.format(Locale.US, "本月 %d 次 · %.1f 公里", count, km);
    }

    private void renderList() {
        if (all.isEmpty()) {
            LinearLayout card = u.card();
            card.addView(u.text("还没有运动记录", 17, p.onSurface, true));
            TextView d = u.text("完成一次运动后会自动记录，可以在这里分享。", 12, p.onSurfaceVariant);
            u.gap(d, 6);
            card.addView(d);
            content.addView(card);
            return;
        }
        for (RecordStore.Rec r : all) content.addView(recordCard(r));
    }

    private View recordCard(final RecordStore.Rec r) {
        LinearLayout card = u.card();
        u.gap(card, 12);
        LinearLayout top = u.row();
        top.addView(u.text(r.name, 17, p.onSurface, true), u.lpWeight(1));
        top.addView(u.text(r.dateText(), 12, p.onSurfaceVariant));
        card.addView(top);
        LinearLayout stats = u.row();
        u.gap(stats, 10);
        stats.addView(metric("时长", r.duration()));
        stats.addView(metric("距离", String.format(Locale.US, "%.2f km", r.km)));
        stats.addView(metric("消耗", r.kcal + " 千卡"));
        card.addView(stats);
        TextView share = u.tonal("分享这张记录");
        share.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(44)));
        u.gap(share, 12);
        share.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { showShareDialog(r); }
        });
        card.addView(share);
        return card;
    }

    private LinearLayout metric(String k, String v) {
        LinearLayout box = u.column();
        box.setLayoutParams(u.lpWeight(1));
        box.addView(u.text(v, 16, p.onSurface, true));
        TextView t = u.text(k, 11, p.onSurfaceVariant);
        u.gap(t, 2);
        box.addView(t);
        return box;
    }

    private void showShareDialog(final RecordStore.Rec r) {
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = u.column();
        u.pad(box, 20, 18, 20, 18);
        box.setBackground(u.round(p.surface, 24));
        box.addView(u.headline("分享运动记录", 20));
        TextView sub = u.text("微信 / 朋友圈 / QQ / QQ空间 都在系统分享面板里。", 12, p.onSurfaceVariant);
        u.gap(sub, 6);
        box.addView(sub);

        TextView share = u.filled("分享");
        share.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(48)));
        u.gap(share, 16);
        share.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); doShare(r, true); }
        });
        box.addView(share);

        TextView save = u.outlined("保存图片到相册");
        save.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(48)));
        u.gap(save, 10);
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); doShare(r, false); }
        });
        box.addView(save);

        TextView cancel = u.textButton("取消");
        u.gap(cancel, 6);
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { d.dismiss(); }
        });
        box.addView(cancel);
        d.setContentView(box);
        if (d.getWindow() != null) {
            d.getWindow().setBackgroundDrawable(u.round(p.surface, 24));
            d.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        d.show();
    }

    private void doShare(final RecordStore.Rec r, final boolean share) {
        boolean needAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && a.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED;
        if (needAsk) {
            pendingRec = r;
            pendingShare = share;
            a.requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 3001);
            return;
        }
        Toast.makeText(a, share ? "正在生成分享图…" : "正在保存…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override public void run() {
                final Bitmap bmp = ShareCard.render(r);
                final Uri uri = ShareCard.saveToGallery(a, bmp, "xq_run_" + r.ts + ".png");
                a.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        if (uri == null) {
                            Toast.makeText(a, "保存失败，请检查存储权限", Toast.LENGTH_LONG).show();
                            return;
                        }
                        if (share) {
                            ShareCard.share(a, uri, String.format(Locale.US,
                                    "我刚完成「%s」：%s，%.2f 公里，%d 千卡。",
                                    r.name, r.duration(), r.km, r.kcal));
                        } else {
                            Toast.makeText(a, "已保存到相册：Pictures/XiaoQiao", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "xq-share").start();
    }

    public void onRequestPermissionsResult(int requestCode, int[] grantResults) {
        if (requestCode == 3001 && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED && pendingRec != null) {
            RecordStore.Rec r = pendingRec;
            boolean share = pendingShare;
            pendingRec = null;
            doShare(r, share);
        } else if (requestCode == 3001) {
            pendingRec = null;
            Toast.makeText(a, "没有存储权限，无法保存图片", Toast.LENGTH_LONG).show();
        }
    }
}
