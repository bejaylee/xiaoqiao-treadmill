package com.xq.treadmill;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 扫描动画：中心圆点 + 三层向外扩散的波纹。
 * 不依赖任何三方库，纯 Canvas + ValueAnimator。
 */
public class RadarView extends View {

    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int color;
    private float progress;
    private ValueAnimator anim;
    private final float density;

    public RadarView(Context c, int color) {
        super(c);
        this.color = color;
        this.density = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2 * density);
        dot.setStyle(Paint.Style.FILL);
    }

    public void start() {
        if (anim != null && anim.isRunning()) return;
        anim = ValueAnimator.ofFloat(0f, 1f);
        anim.setDuration(1800);
        anim.setRepeatCount(ValueAnimator.INFINITE);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                progress = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        anim.start();
    }

    public void stop() {
        if (anim != null) {
            anim.cancel();
            anim = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float maxR = Math.min(cx, cy) - 4 * density;
        float minR = 18 * density;

        for (int i = 0; i < 3; i++) {
            float p = (progress + i / 3f) % 1f;
            float r = minR + p * (maxR - minR);
            int alpha = (int) (150 * (1f - p));
            ring.setColor(Palette.alpha(color, Math.max(0, alpha)));
            canvas.drawCircle(cx, cy, r, ring);
        }

        dot.setColor(Palette.alpha(color, 40));
        canvas.drawCircle(cx, cy, 26 * density, dot);
        dot.setColor(color);
        canvas.drawCircle(cx, cy, 8 * density, dot);
    }
}
