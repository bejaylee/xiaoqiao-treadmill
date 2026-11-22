package com.xq.treadmill;

import android.graphics.Color;

/**
 * Material 3 基线配色（浅色 / 深色）。
 *
 * 说明：本工程不依赖 AndroidX / Material Components（手工 aapt2 构建），
 * 所以按 M3 的 token 命名自己实现一套，保证两套主题语义一致。
 */
public class Palette {

    public int bg;              // 页面背景
    public int surface;         // 卡片
    public int surfaceVariant;  // 次级容器 / 输入框
    public int onSurface;       // 主文字
    public int onSurfaceVariant;// 次文字
    public int primary;
    public int onPrimary;
    public int primaryContainer;
    public int onPrimaryContainer;
    public int outline;         // 描边 / 分隔线
    public int error;
    public int onError;
    public int success;

    private Palette() {
    }

    public static Palette light() {
        Palette p = new Palette();
        p.bg = 0xFFF7F3FA;
        p.surface = 0xFFFFFFFF;
        p.surfaceVariant = 0xFFEFE9F5;
        p.onSurface = 0xFF1D1B20;
        p.onSurfaceVariant = 0xFF5B5666;
        p.primary = 0xFF6750A4;
        p.onPrimary = 0xFFFFFFFF;
        p.primaryContainer = 0xFFE9DDFF;
        p.onPrimaryContainer = 0xFF22005D;
        p.outline = 0xFFCBC4D0;
        p.error = 0xFFB3261E;
        p.onError = 0xFFFFFFFF;
        p.success = 0xFF2E7D32;
        return p;
    }

    public static Palette dark() {
        Palette p = new Palette();
        p.bg = 0xFF131118;
        p.surface = 0xFF1D1B22;
        p.surfaceVariant = 0xFF2A2731;
        p.onSurface = 0xFFE7E1EA;
        p.onSurfaceVariant = 0xFFB5AFBC;
        p.primary = 0xFFD0BCFF;
        p.onPrimary = 0xFF381E72;
        p.primaryContainer = 0xFF4F378B;
        p.onPrimaryContainer = 0xFFEADDFF;
        p.outline = 0xFF4A4553;
        p.error = 0xFFF2B8B5;
        p.onError = 0xFF601410;
        p.success = 0xFF7FD68A;
        return p;
    }

    /** 在给定背景上叠加一层白色（浅色主题）或黑色（深色主题），用于 ripple / 按压态 */
    public static int overlay(int color, boolean dark, float alpha) {
        int base = dark ? 0xFF000000 : 0xFFFFFFFF;
        return blend(color, base, alpha);
    }

    public static int blend(int c1, int c2, float ratio) {
        float r = 1f - ratio;
        int a = (int) (Color.alpha(c1) * r + Color.alpha(c2) * ratio);
        int r1 = (int) (Color.red(c1) * r + Color.red(c2) * ratio);
        int g1 = (int) (Color.green(c1) * r + Color.green(c2) * ratio);
        int b1 = (int) (Color.blue(c1) * r + Color.blue(c2) * ratio);
        return Color.argb(a, r1, g1, b1);
    }

    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | (a << 24);
    }
}
