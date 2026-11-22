package com.xq.treadmill;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 轻量级 Material 3 控件工厂。
 *
 * 没有引入 Material Components（手工构建不引 AAR），这里用框架控件 +
 * GradientDrawable/RippleDrawable 复刻 M3 的形态：
 *   · 卡片 24dp 圆角、1dp outline 描边
 *   · 按钮全圆角（pill）、48dp 高、带 ripple
 *   · 字阶按 M3：display 48 / headline 24 / title 16 / body 14 / label 12
 */
public class Ui {

    public final Context c;
    public final Palette p;
    public final boolean dark;
    private final float density;

    public Ui(Context c) {
        this.c = c;
        this.p = ThemeManager.palette(c);
        this.dark = ThemeManager.isDark(c);
        this.density = c.getResources().getDisplayMetrics().density;
    }

    // ------------------------------------------------------------ 尺寸
    public int dp(float v) {
        return Math.round(v * density);
    }

    public void pad(View v, float l, float t, float r, float b) {
        v.setPadding(dp(l), dp(t), dp(r), dp(b));
    }

    public void margin(View v, float l, float t, float r, float b) {
        ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
        if (lp == null) return;
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        v.setLayoutParams(lp);
    }

    public LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public LinearLayout.LayoutParams lpWeight(float weight) {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
    }

    // ------------------------------------------------------------ 形状
    /** 全局统一圆角矩形；不使用胶囊（按钮半高）或正圆 */
    public static final float RADIUS = 14f;

    public GradientDrawable round(int fill, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(RADIUS));   // 忽略传入半径，保证全 App 形状一致
        return d;
    }

    public GradientDrawable round(int fill, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable d = round(fill, radiusDp);
        d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    public RippleDrawable ripple(int fill, float radiusDp, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor),
                round(fill, radiusDp), null);
    }

    public RippleDrawable ripple(int fill, float radiusDp, int stroke, float strokeDp, int rippleColor) {
        return new RippleDrawable(ColorStateList.valueOf(rippleColor),
                round(fill, radiusDp, stroke, strokeDp), null);
    }

    // ------------------------------------------------------------ 文本
    public TextView text(String s, float sp, int color) {
        return text(s, sp, color, false);
    }

    public TextView text(String s, float sp, int color, boolean medium) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        if (medium) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    public TextView headline(String s, float sp) {
        return text(s, sp, p.onSurface, true);
    }

    public TextView label(String s) {
        return text(s, 12, p.onSurfaceVariant, true);
    }

    // ------------------------------------------------------------ 容器
    public LinearLayout column() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        // 默认占满宽度：否则带 weight 的子控件在 WRAP_CONTENT 父容器里会被算成 0 宽
        l.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    public LinearLayout row() {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return l;
    }

    /** M3 卡片：surface + 24dp 圆角 + 1dp outline */
    public LinearLayout card() {
        LinearLayout l = column();
        l.setBackground(round(p.surface, 24, p.outline, 1));
        pad(l, 18, 16, 18, 16);
        return l;
    }

    public View divider() {
        View v = new View(c);
        v.setBackgroundColor(p.outline);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        return v;
    }

    // ------------------------------------------------------------ 按钮
    private Button base(String s, float heightDp) {
        Button b = new Button(c);
        b.setText(s);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setAllCaps(false);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(dp(20), 0, dp(20), 0);
        b.setStateListAnimator(null);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(heightDp)));
        return b;
    }

    public Button filled(String s) {
        Button b = base(s, 52);
        b.setTextColor(p.onPrimary);
        b.setBackground(ripple(p.primary, 26, Palette.alpha(p.onPrimary, 40)));
        return b;
    }

    public Button tonal(String s) {
        Button b = base(s, 52);
        b.setTextColor(p.onPrimaryContainer);
        b.setBackground(ripple(p.primaryContainer, 26, Palette.alpha(p.onPrimaryContainer, 30)));
        return b;
    }

    public Button outlined(String s) {
        Button b = base(s, 52);
        b.setTextColor(p.primary);
        b.setBackground(ripple(p.surface, 26, p.outline, 1, Palette.alpha(p.primary, 30)));
        return b;
    }

    public Button danger(String s) {
        Button b = base(s, 52);
        b.setTextColor(p.onError);              // 深色主题下是深红，不是白字
        b.setBackground(ripple(p.error, 26, Palette.alpha(p.onError, 36)));
        return b;
    }

    /** 同一个按钮在「开始 / 停止」两种语义间切换配色 */
    public void styleFilled(TextView b, boolean danger) {
        if (danger) {
            b.setTextColor(p.onError);
            b.setBackground(ripple(p.error, 26, Palette.alpha(p.onError, 36)));
        } else {
            b.setTextColor(p.onPrimary);
            b.setBackground(ripple(p.primary, 26, Palette.alpha(p.onPrimary, 40)));
        }
    }

    public Button textButton(String s) {
        Button b = base(s, 44);
        b.setPadding(dp(12), 0, dp(12), 0);
        b.setTextColor(p.primary);
        b.setBackground(ripple(Color.TRANSPARENT, 22, Palette.alpha(p.primary, 26)));
        return b;
    }

    /** 圆形 +/− 按钮 */
    public TextView circle(String glyph, float sizeDp, int fill, int fg, int rippleColor) {
        TextView t = new TextView(c);
        t.setText(glyph);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeDp * 0.42f);
        t.setTextColor(fg);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        t.setBackground(ripple(fill, sizeDp / 2f, rippleColor));
        t.setClickable(true);
        t.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        t.setLayoutParams(lp);
        return t;
    }

    /** 可选中的 chip */
    public TextView chip(String s, boolean selected) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pad(t, 14, 11, 14, 11);
        if (selected) {
            t.setTextColor(p.onPrimaryContainer);
            t.setBackground(round(p.primaryContainer, 16));
        } else {
            t.setTextColor(p.onSurfaceVariant);
            t.setBackground(round(Color.TRANSPARENT, 16, p.outline, 1));
        }
        t.setClickable(true);
        return t;
    }

    public TextView badge(String s, int bg, int fg) {
        TextView t = text(s, 12, fg, true);
        pad(t, 10, 5, 10, 5);
        t.setBackground(round(bg, 12));
        return t;
    }

    /** 指标块：上面大字，下面标签 */

    public EditText input(String hint, int inputType) {
        EditText e = new EditText(c);
        e.setHint(hint);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        e.setTextColor(p.onSurface);
        e.setHintTextColor(p.onSurfaceVariant);
        e.setInputType(inputType);
        e.setBackground(round(p.surfaceVariant, 14));
        e.setPadding(dp(14), dp(12), dp(14), dp(12));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            e.setTextCursorDrawable(null);
        }
        return e;
    }

    public EditText inputMultiline(String hint) {
        EditText e = input(hint, android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        e.setGravity(Gravity.TOP | Gravity.START);
        e.setMinLines(3);
        return e;
    }

    /** 给还没 addView 的视图也能设置上边距 */
    public void gap(View v, float marginDp) {
        ViewGroup.LayoutParams raw = v.getLayoutParams();
        LinearLayout.LayoutParams lp;
        if (raw instanceof LinearLayout.LayoutParams) {
            lp = (LinearLayout.LayoutParams) raw;
        } else {
            lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        lp.topMargin = dp(marginDp);
        v.setLayoutParams(lp);
    }
}
