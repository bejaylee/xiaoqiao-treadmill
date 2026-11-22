# -*- coding: utf-8 -*-
"""生成小乔跑跑应用图标：adaptive icon（前景/背景分层）+ 各密度传统 PNG。

设计：Material 紫渐变底 + 白色抽象「跑者 + 跑道」线条。
自适应图标规范：前景/背景画布 108dp，安全区为中心 72dp（重要内容不越界）。
"""
import os
from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

# 密度 -> 传统图标尺寸(dp 48) / adaptive 画布(dp 108)
DENSITIES = {
    "mdpi": (1.0, 48, 108),
    "hdpi": (1.5, 72, 162),
    "xhdpi": (2.0, 96, 216),
    "xxhdpi": (3.0, 144, 324),
    "xxxhdpi": (4.0, 192, 432),
}

PURPLE = (103, 80, 164)      # 6750A4
PURPLE_DARK = (81, 60, 140)
WHITE = (255, 255, 255)


def gradient(size, top, bottom):
    """竖向线性渐变"""
    img = Image.new("RGB", (1, size))
    px = img.load()
    for y in range(size):
        t = y / max(1, size - 1)
        px[0, y] = tuple(int(top[i] * (1 - t) + bottom[i] * t) for i in range(3))
    return img.resize((size, size))


def draw_runner(draw, cx, cy, s, color, width):
    """抽象跑者：头 + 倾斜身体 + 前后摆动的腿与手臂"""
    # 头
    r = s * 0.075
    draw.ellipse([cx - s*0.17 - r, cy - s*0.30 - r, cx - s*0.17 + r, cy - s*0.30 + r],
                 fill=color)
    # 身体（斜线）
    draw.line([(cx - s*0.13, cy - s*0.20), (cx + s*0.06, cy + s*0.05)],
              fill=color, width=width, joint="curve")
    # 前腿
    draw.line([(cx + s*0.06, cy + s*0.05), (cx + s*0.25, cy + s*0.02)],
              fill=color, width=width)
    draw.line([(cx + s*0.25, cy + s*0.02), (cx + s*0.33, cy + s*0.18)],
              fill=color, width=width)
    # 后腿
    draw.line([(cx + s*0.06, cy + s*0.05), (cx - s*0.12, cy + s*0.20)],
              fill=color, width=width)
    draw.line([(cx - s*0.12, cy + s*0.20), (cx - s*0.28, cy + s*0.12)],
              fill=color, width=width)
    # 手臂
    draw.line([(cx - s*0.08, cy - s*0.12), (cx + s*0.16, cy - s*0.02)],
              fill=color, width=max(2, int(width*0.8)))
    draw.line([(cx - s*0.08, cy - s*0.10), (cx - s*0.26, cy - s*0.14)],
              fill=color, width=max(2, int(width*0.8)))


def draw_track(draw, cx, cy, s, color, width):
    """跑道：两条弧线，暗示速度"""
    for dy in (0.30, 0.38):
        draw.arc([cx - s*0.42, cy - s*0.05, cx + s*0.42, cy + s*0.55],
                 start=200, end=340, fill=color, width=width)


def make_adaptive_fg(size):
    """前景层：透明底 + 白色跑者（严格在中心 72dp 安全区内）"""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    cx = cy = size / 2
    s = size * 0.44            # 内容控制在 ~47% 画布内，安全
    draw_runner(d, cx, cy, s, WHITE, max(3, int(size * 0.030)))
    draw_track(d, cx, cy, s, (255, 255, 255, 170), max(2, int(size * 0.018)))
    return img


def make_adaptive_bg(size):
    """背景层：紫渐变铺满 108dp 画布（系统裁切成各种形状）"""
    return gradient(size, PURPLE, PURPLE_DARK).convert("RGBA")


def make_legacy(size, round_icon=False):
    """传统图标：渐变底 + 白色圆角描边 + 跑者；老系统直接显示"""
    img = gradient(size, PURPLE, PURPLE_DARK).convert("RGBA")
    d = ImageDraw.Draw(img)
    # 内圈细描边，增加层次
    pad = int(size * 0.10)
    if round_icon:
        d.ellipse([pad, pad, size-pad, size-pad], outline=(255, 255, 255, 60),
                  width=max(1, int(size*0.012)))
    else:
        d.rounded_rectangle([pad, pad, size-pad, size-pad], radius=int(size*0.14),
                            outline=(255, 255, 255, 60), width=max(1, int(size*0.012)))
    cx = cy = size / 2
    s = size * 0.56
    draw_runner(d, cx, cy, s, WHITE, max(2, int(size*0.045)))
    draw_track(d, cx, cy, s, (255, 255, 255, 190), max(1, int(size*0.026)))
    return img


def main():
    for dpi, (_, legacy_dp, adaptive_dp) in DENSITIES.items():
        folder = os.path.join(RES, "mipmap-" + dpi)
        os.makedirs(folder, exist_ok=True)

        legacy_px = int(legacy_dp)
        make_legacy(legacy_px).save(os.path.join(folder, "ic_launcher.png"))
        make_legacy(legacy_px, round_icon=True).save(os.path.join(folder, "ic_launcher_round.png"))

        adaptive_px = int(adaptive_dp)
        make_adaptive_fg(adaptive_px).save(os.path.join(folder, "ic_launcher_foreground.png"))
        make_adaptive_bg(adaptive_px).save(os.path.join(folder, "ic_launcher_background.png"))
        print(dpi, "legacy", legacy_px, "adaptive", adaptive_px)

    # adaptive icon 描述文件（anydpi）
    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    xml = '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@mipmap/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
'''
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w", encoding="utf-8") as f:
            f.write(xml)
    print("adaptive xml written")


if __name__ == "__main__":
    main()
