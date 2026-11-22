#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
课程/计划跑（客户端调度版）

不依赖设备内部计时，由本程序按秒表下发速度——更准、可任意分段、可随时暂停。

课程格式：[(速度 km/h, 持续秒数), ...]
    python xq_course.py 192.168.100.178 "2.0:20,3.5:20"
    python xq_course.py 192.168.100.178 "3:180,5:120,4:180"

行为：
  · 每段到点自动切速度，段间平滑（设备自己会做 1.0 缓冲）
  · 全程每 2 秒打印一次实际速度/时长/里程
  · 结束自动减速 + 关机
  · 任何异常都会先降速再退出
"""

import sys
import time

import xq_client as xc


def parse_spec(spec):
    segs = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        spd, dur = part.split(":")
        segs.append((float(spd), int(dur)))
    return segs


def main():
    ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.100.178"
    spec = sys.argv[2] if len(sys.argv) > 2 else "2.0:20,3.5:20"
    segs = parse_spec(spec)

    total = sum(d for _, d in segs)
    print("课程：%d 段，共 %d 秒" % (len(segs), total))
    for i, (s, d) in enumerate(segs):
        print("  第%d段  %.1f km/h  %d 秒" % (i + 1, s, d))
    print()

    state = {"speed": 0, "dur": 0, "mileage": 0, "cal": 0, "step": 0, "hr": 0, "pct": 0}
    orig = xc.Client._on_frame

    def hook(self, p):
        if p and p[0] == 0x01:
            st = xc.Status.parse(p)
            if st:
                state.update(speed=st.speed, dur=st.duration, mileage=st.mileage,
                             cal=st.calorie, step=st.step, hr=st.heart_rate, pct=st.percentage)
        orig(self, p)

    xc.Client._on_frame = hook

    c = xc.Client(ip)
    c.connect()
    t0 = time.time()

    try:
        c.send(xc.cmd_power_on())
        time.sleep(1.5)

        for idx, (spd, dur) in enumerate(segs):
            print("[%6.1fs] 第%d段开始：%.1f km/h × %d 秒" % (time.time() - t0, idx + 1, spd, dur))
            c.send(xc.cmd_speed(spd))
            seg_end = time.time() + dur
            next_log = time.time() + 2
            next_keep = time.time() + 4
            while time.time() < seg_end:
                time.sleep(0.5)
                # 设备怪癖：第一条速度命令只把跑带起到 1.0，需重复下发才到位
                if time.time() >= next_keep:
                    c.send(xc.cmd_speed(spd))
                    next_keep += 4
                if time.time() >= next_log:
                    print("   [%6.1fs] 实际 %4.1f km/h | %02d:%02d | 里程 %.2f km | %d kcal | %d 步 | 心率 %d"
                          % (time.time() - t0, state["speed"] / 10.0,
                             state["dur"] // 60, state["dur"] % 60,
                             state["mileage"] * 0.01, state["cal"], state["step"], state["hr"]))
                    next_log += 2
            print("[%6.1fs] 第%d段结束" % (time.time() - t0, idx + 1))

        print("\n课程完成，正在减速停机…")
    except KeyboardInterrupt:
        print("\n手动中断")
    finally:
        try:
            c.send(xc.cmd_speed(0))
            time.sleep(2.0)
            c.send(xc.cmd_power_off())
        except Exception:
            pass
        time.sleep(1.5)
        c.close()

    print("总用时 %.0f 秒 | 里程 %.2f km | %d kcal | %d 步"
          % (time.time() - t0, state["mileage"] * 0.01, state["cal"], state["step"]))


if __name__ == "__main__":
    main()
