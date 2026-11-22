#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实机标定脚本：跑一遍 开机→低速→停机 的完整流程，逐帧记录原始 34 字节状态帧。

用途：用实测数据反推
  · mileage 的单位与语义（增量 or 累计）
  · power 半字节的取值表（待机/运行/停止）
  · speed 回显是否等于下发值
  · duration / calorie / step 的变化节奏

用法：
    python xq_live_test.py 192.168.100.178
    python xq_live_test.py 192.168.100.178 --top 2.0     # 最高速度，默认 2.0

安全：全程无人站立，速度默认只到 2.0 km/h，结束必发 减速到 0 + 关机。
"""

import argparse
import sys
import time

import xq_client as xc

LOG = []


def dump(tag, raw):
    ts = time.time()
    rec = {
        "t": ts,
        "tag": tag,
        "raw": raw.hex(" "),
        "st": xc.Status.parse(raw),
    }
    LOG.append(rec)
    return rec


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ip")
    ap.add_argument("--top", type=float, default=2.0)
    ap.add_argument("--hold", type=float, default=20.0, help="保持速度观察的秒数")
    a = ap.parse_args()

    frames = []
    orig = xc.Client._on_frame

    def hook(self, p):
        if p and p[0] == 0x01:
            r = dump("frame", p)
            frames.append(r)
        orig(self, p)

    xc.Client._on_frame = hook

    c = xc.Client(a.ip)
    c.connect()
    t0 = time.time()

    def mark(msg):
        print("[%6.1fs] %s" % (time.time() - t0, msg))

    try:
        mark("基线采集 3s（待机）")
        time.sleep(3)
        base_n = len(frames)
        print("  基线帧数 %d" % base_n)
        if frames:
            print("  基线样本: %s" % frames[-1]["raw"])
            print("  power=%d error=%d speed=%d" % (
                frames[-1]["st"].power, frames[-1]["st"].error, frames[-1]["st"].speed))

        mark("发 开机 (11 04 01 02)")
        c.send(xc.cmd_power_on())
        time.sleep(4)
        after_on = len(frames)
        if after_on > base_n:
            s = frames[-1]["st"]
            print("  开机后 power=%d speed=%d duration=%d" % (s.power, s.speed, s.duration))

        mark("发 速度 %.1f km/h" % a.top)
        c.send(xc.cmd_speed(a.top))
        time.sleep(3)
        idx = len(frames)
        s = frames[-1]["st"] if frames else None
        if s:
            print("  加速后 speed=%d (期望 %d) power=%d" % (s.speed, int(a.top * 10), s.power))

        mark("保持 %.0fs，观察速度/时长/里程/卡路里" % a.hold)
        start_hold = time.time()
        samples = []
        while time.time() - start_hold < a.hold:
            time.sleep(2)
            if frames:
                s = frames[-1]["st"]
                samples.append((round(time.time() - start_hold, 1),
                                s.speed, s.duration, s.mileage, s.calorie, s.step, s.power))
        print("   t     speed duration mileage cal step pwr")
        for row in samples:
            print("  %5.1f  %5d %8d %7d %4d %4d %3d" % row)

        mark("发 速度 0（减速停跑带）")
        c.send(xc.cmd_speed(0))
        time.sleep(6)
        s = frames[-1]["st"]
        print("  减速后 speed=%d power=%d" % (s.speed, s.power))

        mark("发 关机 (11 04 01 01)")
        c.send(xc.cmd_power_off())
        time.sleep(3)
        s = frames[-1]["st"]
        print("  关机后 speed=%d power=%d duration=%d" % (s.speed, s.power, s.duration))
    finally:
        try:
            c.send(xc.cmd_speed(0))
            time.sleep(1.2)
            c.send(xc.cmd_power_off())
        except Exception:
            pass
        c.close()

    # 落盘原始帧
    fn = "live_capture.txt"
    with open(fn, "w", encoding="utf-8") as f:
        for r in LOG:
            f.write("%.3f %s\n" % (r["t"], r["raw"]))
    print("\n共捕获 %d 帧，原始数据已写入 %s" % (len(frames), fn))

    # 里程增量分析
    if len(frames) > 2:
        print("\n--- mileage 字段（字节 2..5 小端）逐帧 ---")
        prev = None
        deltas = []
        for r in frames[-40:]:
            m = r["st"].mileage
            d = "-" if prev is None else str(m - prev)
            if prev is not None:
                deltas.append(m - prev)
            prev = m
            print("   %s  mileage=%-6d delta=%s" % (r["raw"][:11], m, d))
        if deltas:
            nz = [d for d in deltas if d]
            print("   非零增量个数 %d，取值集合 %s" % (len(nz), sorted(set(nz))[:10]))


if __name__ == "__main__":
    main()
