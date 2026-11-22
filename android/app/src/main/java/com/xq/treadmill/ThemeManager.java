package com.xq.treadmill;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.view.View;
import android.view.Window;

/**
 * 全局外观：浅色 / 深色 / 跟随系统。
 *
 * 不依赖 AppCompat，因此不用 DayNight 主题，改为：
 *   1. 偏好里存 0 跟随系统 / 1 浅色 / 2 深色
 *   2. 每个 Activity 在 onCreate 最开头 resolve() 出实际明暗
 *   3. 所有颜色走 Palette，窗口背景与状态栏各自上色
 */
public class ThemeManager {

    public static final int FOLLOW_SYSTEM = 0;
    public static final int LIGHT = 1;
    public static final int DARK = 2;

    private static final String PREFS = "ui";
    private static final String KEY_MODE = "theme_mode";

    public static int getMode(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MODE, FOLLOW_SYSTEM);
    }

    public static void setMode(Context c, int mode) {
        SharedPreferences.Editor e = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        e.putInt(KEY_MODE, mode);
        e.apply();
    }

    /** 当前实际是否为深色 */
    public static boolean isDark(Context c) {
        int mode = getMode(c);
        if (mode == LIGHT) return false;
        if (mode == DARK) return true;
        int ui = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return ui == Configuration.UI_MODE_NIGHT_YES;
    }

    public static Palette palette(Context c) {
        return isDark(c) ? Palette.dark() : Palette.light();
    }

    /** 在 setContentView 之前调用 */
    public static void apply(Activity a) {
        Palette p = palette(a);
        Window w = a.getWindow();
        w.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(p.bg));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            w.setStatusBarColor(p.bg);
            w.setNavigationBarColor(p.bg);
        }
        setLightBars(w, !isDark(a));
    }

    /** 切换后重建界面 */
    public static void toggle(Activity a, int mode) {
        setMode(a, mode);
        a.recreate();
    }

    public static void setLightBars(Window w, boolean light) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        View decor = w.getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (light) {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        } else {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (light) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            } else {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        decor.setSystemUiVisibility(flags);
    }

}
