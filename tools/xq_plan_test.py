#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
课程/计划跑 真机验证：下发 2 段课程，看跑步机是否自动按段落变速。

计划：第 1 分钟 2.0 km/h，第 2 分钟 4.0 km/h（边界在 60 秒）

时间表（语音引导，无需按键）：
    t=0    语音"请站上跑带，扶好扶手"  + 三声短响
    t=25   语音"下发课程" → 发送 0x12 计划帧 + 开机
    t=85   语音"应该进入第二段了"（提示观察）
    t=165  语音"测试结束" → 减速 + 关机
最后打印整段速度轨迹，判断设备是否按计划变速。
"""

import sys
import time

import xq_client as xc

try:
    import winsound
except ImportError:
    winsound = None

VOICE = None
try:
    import win32com.client as _w
    VOICE = _w.Dispatch("SAPI.SpVoice")
    VOICE.Rate = 0
except Exception:
    VOICE = None

SEGS = [(2.0, 0, 1), (4.0, 1, 2)]


def beep(f, ms, n=1):
    if not winsound:
        return
    for i in range(n):
        try:
            winsound.Beep(f, ms)
        except Exception:
            pass
        if i < n - 1:
            time.sleep(0.16)


def say(t):
    if VOICE is None:
        return
    try:
        VOICE.Speak(t, 1)
    except Exception:
        pass


def main():
    ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.100.178"

    samples = []
    orig = xc.Client._on_frame

    def hook(self, p):
        if p and p[0] == 0x01:
            st = xc.Status.parse(p)
            if st:
                samples.append((round(time.time() - T0[0], 1), st.speed, st.power, st.person_on))
        orig(self, p)

    xc.Client._on_frame = hook
    T0 = [time.time()]

    say("课程模式测试。请站上跑带，双手扶好扶手。")
    beep(880, 120, 3)

    c = xc.Client(ip)
    try:
        c.connect()
    except Exception as e:
        say("连接失败。")
        print("连接失败:", e)
        return

    T0[0] = time.time()
    try:
        time.sleep(25)
        say("现在下发课程。第一分钟每小时两公里，第二分钟每小时四公里。")
        plan = xc.cmd_plan(SEGS, index=0, mode_id=33, plan_id=1, uid=0, last_second=0)
        print("[下发计划] %s" % plan.hex(" "))
        c.send(plan)
        time.sleep(1.0)
        c.send(xc.cmd_power_on())
        print("[开机]")
        # 只发一次开机，不再发速度——看设备是否按计划自己动

        time.sleep(60)
        say("现在应该进入第二段了，速度会变成每小时四公里。")

        time.sleep(80)
        say("测试结束，正在停机。")
        c.send(xc.cmd_speed(0))
        time.sleep(2)
    finally:
        try:
            c.send(xc.cmd_speed(0))
            time.sleep(1.2)
            c.send(xc.cmd_power_off())
        except Exception:
            pass
        time.sleep(1.5)
        c.close()

    beep(990, 400)
    print("\n=== 速度轨迹（t, speed, power, person）===")
    prev = None
    for t, sp, pw, pe in samples:
        if sp != prev:
            print("  t=%6.1f  speed=%3d (%.1f km/h)  power=%d person=%d" % (t, sp, sp / 10, pw, pe))
            prev = sp
    print("\n共 %d 帧" % len(samples))
    mx = max((s[1] for s in samples), default=0)
    print("最高速度 %d (%.1f km/h)" % (mx, mx / 10))
    if mx >= 35:
        print(">>> 出现了 4.0 km/h —— 课程第二段生效！")
    elif mx >= 25:
        print(">>> 只到第一段的 2.x —— 第二段没触发")
    else:
        print(">>> 没有自动变速，课程模式可能还需要别的握手")


if __name__ == "__main__":
    main()
