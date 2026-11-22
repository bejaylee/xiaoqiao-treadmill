package com.xq.treadmill;

import android.app.Activity;
import android.content.Intent;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 「课程」页：按 K1→K4 难度分组展示，组内按时长从短到长。
 * 点某门课进入课程详情页（CourseActivity）展开分段并开跑。
 */
public class CoursePage {

    public static final String EXTRA_COURSE_ID = "course_id";
    public static final String EXTRA_CUSTOM = "custom_tab";

    public static View create(final Activity a, Ui u, Palette p) {
        ScrollView sv = new ScrollView(a);
        sv.setBackgroundColor(p.bg);
        sv.setFillViewport(true);
        LinearLayout root = u.column();
        u.pad(root, 20, 12, 20, 20);
        sv.addView(root);

        TextView lead = u.text(CourseLibrary.ALL.size()
                + " 门课程 · 按难度 K1→K4 分组，同级按时长从短到长", 12, p.onSurfaceVariant);
        root.addView(lead);

        for (CourseGroups.Group g : CourseGroups.groups()) {
            root.addView(groupHeader(a, u, p, g));
            for (final CourseLibrary.Course c : g.courses) {
                LinearLayout card = u.card();
                u.gap(card, 12);
                LinearLayout head = u.row();
                LinearLayout col = u.column();
                col.addView(u.text(c.name, 17, p.onSurface, true));
                TextView meta = u.text(String.format(java.util.Locale.US,
                        "%s · %d 段 · 峰值 %.1f · %s 千卡",
                        fmt(c.totalSeconds()), c.steps.size(), c.peakSpeed(), c.kcal),
                        12, p.onSurfaceVariant);
                u.gap(meta, 3);
                col.addView(meta);
                head.addView(col, u.lpWeight(1));
                head.addView(u.badge(g.code, p.surfaceVariant, p.onSurfaceVariant));
                card.addView(head);
                card.setClickable(true);
                card.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Intent it = new Intent(a, CourseActivity.class);
                        it.putExtra(EXTRA_COURSE_ID, c.id);
                        a.startActivity(it);
                    }
                });
                root.addView(card);
            }
        }

        TextView custom = u.outlined("自定义一句话课程");
        custom.setLayoutParams(u.lp(ViewGroup.LayoutParams.MATCH_PARENT, u.dp(52)));
        u.gap(custom, 16);
        custom.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent it = new Intent(a, CourseActivity.class);
                it.putExtra(EXTRA_CUSTOM, true);
                a.startActivity(it);
            }
        });
        root.addView(custom);
        return sv;
    }

    private static View groupHeader(Activity a, Ui u, Palette p, CourseGroups.Group g) {
        LinearLayout row = u.row();
        row.setGravity(Gravity.CENTER_VERTICAL);
        u.gap(row, 16);

        View dot = new View(a);
        dot.setBackground(u.round(g.color, Ui.RADIUS));
        row.addView(dot, u.lp(u.dp(10), u.dp(10)));

        TextView title = u.text(g.code + " · " + g.label, 15, p.onSurface, true);
        LinearLayout.LayoutParams tlp = u.lpWeight(1);
        tlp.leftMargin = u.dp(8);
        row.addView(title, tlp);

        int min = Integer.MAX_VALUE, max = 0;
        for (CourseLibrary.Course c : g.courses) {
            int m = c.minutes();
            min = Math.min(min, m);
            max = Math.max(max, m);
        }
        String span = min == max ? min + " 分钟" : min + "–" + max + " 分钟";
        row.addView(u.text(g.courses.size() + " 门 · " + span, 11, p.onSurfaceVariant));
        return row;
    }

    private static String fmt(int seconds) {
        return String.format(java.util.Locale.US, "%d:%02d", seconds / 60, seconds % 60);
    }
}
