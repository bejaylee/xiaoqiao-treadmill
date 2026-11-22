#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
课程模式二次验证：确认 start/end 的时间单位是秒。

计划：0-20 秒 2.0 km/h，20-40 秒 3.5 km/h
判定：若第 20 秒速度从 2.0 跳到 3.5、第 40 秒自动停 → 单位确认是**秒**
"""

import sys
import time

import xq_client as xc

try:
    import winsound
except ImportError:
    winsound = None

try:
    import win32com.client as _w
    VOICE = _w.Dispatch("SAPI.SpVoice")
    VOICE.Rate = 0
except Exception:
    VOICE = None


def say(t):
    if VOICE:
        try:
            VOICE.Speak(t, 1)
        except Exception:
            pass


def beep(f, ms, n=1):
    if not winsound:
        return
    for i in range(n):
        try:
            winsound.Beep(f, ms)
        except Exception:
            pass
        time.sleep(0.15)


SEGS = [(2.0, 0, 20), (3.5, 20, 40)]


def main():
    ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.100.178"
    samples = []
    orig = xc.Client._on_frame
    T0 = [time.time()]

    def hook(self, p):
        if p and p[0] == 0x01:
            st = xc.Status.parse(p)
            if st:
                samples.append((round(time.time() - T0[0], 1), st.speed, st.power, st.percentage, st.mode))
        orig(self, p)

    xc.Client._on_frame = hook

    say("课程模式第二次验证，跑带会自己转动。请离开跑带。")
    beep(600, 200, 2)

    c = xc.Client(ip)
    try:
        c.connect()
    except Exception as e:
        print("连接失败:", e)
        return

    T0[0] = time.time()
    try:
        time.sleep(3)
        plan = xc.cmd_plan(SEGS, index=0, mode_id=33, plan_id=1, uid=0, last_second=0)
        print("[计划] %s" % plan.hex(" "))
        c.send(plan)
        time.sleep(0.8)
        c.send(xc.cmd_power_on())
        print("[开机，观察 55 秒]")
        time.sleep(55)
    finally:
        try:
            c.send(xc.cmd_speed(0))
            time.sleep(1.0)
            c.send(xc.cmd_power_off())
        except Exception:
            pass
        time.sleep(1.2)
        c.close()

    beep(990, 400)
    print("\n=== 轨迹 ===")
    prev = None
    for t, sp, pw, pct, mode in samples:
        if (sp, mode) != prev:
            print("  t=%5.1f  speed=%3d(%.1f)  power=%d  进度=%3d%%  mode=%d" % (t, sp, sp / 10, pw, pct, mode))
            prev = (sp, mode)
    mx = max((s[1] for s in samples), default=0)
    print("\n最高速度 %.1f km/h" % (mx / 10))
    if mx >= 34:
        print(">>> 确认：第二段 3.5 km/h 生效，单位是秒")


if __name__ == "__main__":
    main()
