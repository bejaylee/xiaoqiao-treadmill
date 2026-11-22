package com.xq.treadmill;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Material 3 风格底部导航：4 个等宽 tab，选中项图标带 primaryContainer 胶囊。
 * 纯 View 实现，不依赖 AndroidX / Material Components。
 */
public class BottomNav {

    public interface OnTabSelected {
        void onTab(int index);
    }

    public static View create(Context c, Ui u, Palette p, String[] labels, String[] icons,
                              int selected, final OnTabSelected cb) {
        LinearLayout bar = u.row();
        bar.setBackgroundColor(p.surface);
        bar.setPadding(0, u.dp(8), 0, u.dp(10));
        // 顶部分隔线用一层 1dp 背景模拟
        View divider = new View(c);
        divider.setBackgroundColor(p.outline);

        LinearLayout holder = u.column();
        holder.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, u.dp(0.5f))));
        holder.addView(bar);

        for (int i = 0; i < labels.length; i++) {
            final int idx = i;
            LinearLayout item = u.column();
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, u.dp(2), 0, 0);

            TextView icon = u.text(icons[i], 20,
                    i == selected ? p.onPrimaryContainer : p.onSurfaceVariant, true);
            icon.setGravity(Gravity.CENTER);
            if (i == selected) icon.setBackground(u.round(p.primaryContainer, 16));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(u.dp(64), u.dp(32));
            item.addView(icon, ilp);

            TextView label = u.text(labels[i], 11,
                    i == selected ? p.primary : p.onSurfaceVariant, i == selected);
            label.setGravity(Gravity.CENTER);
            u.gap(label, 2);
            item.addView(label);

            item.setClickable(true);
            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    cb.onTab(idx);
                }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.weight = 1;
            bar.addView(item, lp);
        }
        return holder;
    }
}
