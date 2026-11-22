package com.xq.treadmill;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.OutputStream;

/**
 * 运动记录分享卡：把一次运动画成一张图，存进相册后走系统分享。
 *
 * 微信 / 朋友圈 / QQ / QQ空间 都由系统分享面板接管：
 * 微信的分享入口点进去后可以自己选「发送给朋友」或「分享到朋友圈」。
 */
public class ShareCard {

    private static final int W = 1080;
    private static final int H = 1440;

    public static Bitmap render(RecordStore.Rec r) {
        Bitmap bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // 背景：深紫渐变
        Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        bg.setShader(new LinearGradient(0, 0, W, H,
                Color.parseColor("#5A3FA6"), Color.parseColor("#241640"), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, W, H, bg);

        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);

        // 顶部品牌
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextSize(40);
        c.drawText("小乔跑步机", 90, 120, p);

        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        p.setTextSize(32);
        p.setColor(0xCCFFFFFF);
        String date = r.dateText();
        float dw = p.measureText(date);
        c.drawText(date, W - 90 - dw, 118, p);

        // 课程名
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextSize(74);
        String title = r.name == null ? "" : r.name;
        while (p.measureText(title) > W - 180 && p.getTextSize() > 40) {
            p.setTextSize(p.getTextSize() - 4);
        }
        c.drawText(title, 90, 280, p);
        p.setTextSize(74);

        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        p.setTextSize(34);
        p.setColor(0xB3FFFFFF);
        String meta = r.segments > 0
                ? r.segments + " 个分段 · 平均 " + String.format(java.util.Locale.US, "%.1f", r.avgSpeed) + " km/h"
                : "自由跑 · 平均 " + String.format(java.util.Locale.US, "%.1f", r.avgSpeed) + " km/h";
        c.drawText(meta, 90, 340, p);

        // 三个主数据
        stat(c, 90, 430, "时长", r.duration(), "距离", String.format(java.util.Locale.US, "%.2f", r.km) + " km");
        stat2(c, 90, 620, "消耗", r.kcal + " 千卡");

        // 分隔线
        Paint line = new Paint();
        line.setColor(0x33FFFFFF);
        line.setStrokeWidth(2);
        c.drawLine(90, 830, W - 90, 830, line);

        // 文案 + 分段节奏条（把下半幅填满，不伪造具体配速）
        p.setColor(0xCCFFFFFF);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextSize(46);
        c.drawText("每一步，都算数", 90, 940, p);

        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        p.setTextSize(28);
        p.setColor(0x99FFFFFF);
        c.drawText(r.segments > 0
                ? r.segments + " 个分段 · 设备自动变速"
                : "自由跑 · 按自己的节奏", 90, 1100, p);

        if (r.segments > 0) {
            int n = Math.max(1, Math.min(r.segments, 16));
            float gap = 10f;
            float barW = (W - 180f - gap * (n - 1)) / n;
            Paint seg = new Paint(Paint.ANTI_ALIAS_FLAG);
            for (int i = 0; i < n; i++) {
                int alpha = 45 + (int) (130f * i / Math.max(1, n - 1));
                seg.setColor(Color.argb(alpha, 255, 255, 255));
                float left = 90 + i * (barW + gap);
                c.drawRoundRect(new RectF(left, 1010, left + barW, 1040), 8, 8, seg);
            }
        }

        // 装饰：底部大字
        p.setColor(0x1FFFFFFF);
        p.setTextSize(200);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        c.drawText("RUN", 80, 1290, p);

        p.setColor(0x99FFFFFF);
        p.setTextSize(28);
        c.drawText("记录来自 小乔跑步机", 90, 1360, p);

        return bmp;
    }

    private static void stat(Canvas c, float x, float y, String k1, String v1, String k2, String v2) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xB3FFFFFF);
        p.setTextSize(30);
        p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        c.drawText(k1, x, y, p);
        c.drawText(k2, x + 460, y, p);

        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextSize(78);
        c.drawText(v1, x, y + 90, p);
        c.drawText(v2, x + 460, y + 90, p);
    }

    private static void stat2(Canvas c, float x, float y, String k, String v) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xB3FFFFFF);
        p.setTextSize(30);
        c.drawText(k, x, y, p);
        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        p.setTextSize(78);
        c.drawText(v, x, y + 90, p);
    }

    /** 存进系统相册，返回可分享的 content:// */
    public static Uri saveToGallery(Context ctx, Bitmap bmp, String name) {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                v.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/XiaoQiao");
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
            }
            Uri uri = ctx.getContentResolver()
                    .insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            OutputStream os = ctx.getContentResolver().openOutputStream(uri);
            if (os == null) {
                // M6：pending 记录要清掉，否则相册里留垃圾
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ctx.getContentResolver().delete(uri, null, null);
                }
                return null;
            }
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
            os.close();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues done = new ContentValues();
                done.put(MediaStore.Images.Media.IS_PENDING, 0);
                ctx.getContentResolver().update(uri, done, null, null);
            }
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    public static void share(Context ctx, Uri uri, String text) {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/png");
        i.putExtra(Intent.EXTRA_STREAM, uri);
        i.putExtra(Intent.EXTRA_TEXT, text);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(Intent.createChooser(i, "分享运动记录"));
    }
}
