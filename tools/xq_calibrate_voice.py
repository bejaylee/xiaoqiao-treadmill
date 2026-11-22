#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
语音引导版标定：人站在跑步机边，全程听语音指令即可。

时间表（自动执行，无需任何按键）：
    t=0     语音"准备开始，请站在跑步机旁边"
    t=60    语音"现在请上跑带，双脚站稳，扶住扶手" + 三声短响
    t=90    语音"开始走路，跑带即将启动" + 一声长响  → 开机 + 3.0 km/h
    t=150   语音"请握住扶手电极测心率"
    t=240   语音"请停下来站定，跑带会自动减速" + 两声短响
    t=268   语音"可以走下跑带了" + 三声短响
    t=275   关机，保存 calibration.csv
"""

import csv
import sys
import threading
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


def beep(freq, ms, times=1, gap=0.18):
    if not winsound:
        return
    for i in range(times):
        try:
            winsound.Beep(freq, ms)
        except Exception:
            pass
        if i < times - 1:
            time.sleep(gap)


def say(text):
    """异步朗读，不阻塞节拍"""
    if VOICE is None:
        return
    try:
        VOICE.Speak(text, 1)      # 1 = SVSFlagsAsync
    except Exception:
        pass


PHASES = []


def main():
    ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.100.178"
    speed = float(sys.argv[2]) if len(sys.argv) > 2 else 3.0

    records = []
    phase = {"name": "prep"}
    t0 = [0.0]

    orig = xc.Client._on_frame

    def hook(self, p):
        if p and p[0] == 0x01:
            st = xc.Status.parse(p)
            if st:
                records.append({
                    "t": round(time.time() - t0[0], 2),
                    "phase": phase["name"],
                    "raw": p.hex(" "),
                    "speed": st.speed, "duration": st.duration,
                    "mileage": st.mileage, "calorie": st.calorie,
                    "step": st.step, "hr": st.heart_rate,
                    "power": st.power, "error": st.error,
                    "person": st.person_on, "finish": st.finish,
                })
        orig(self, p)

    xc.Client._on_frame = hook

    say("准备开始标定。请站在跑步机旁边，先不要上跑带。")
    beep(660, 150)
    time.sleep(1)

    c = xc.Client(ip)
    try:
        c.connect()
    except Exception as e:
        say("连接失败，请检查跑步机是否开机并连上无线网络。")
        print("连接失败:", e)
        return

    t0[0] = time.time()
    try:
        # ---------- 0 ~ 60 准备 ----------
        phase["name"] = "prep"
        time.sleep(58)
        say("现在请上跑带。双脚站在跑带中间，站稳，双手扶住扶手。")
        beep(880, 120, times=3)

        # ---------- 60 ~ 90 上机站定 ----------
        phase["name"] = "mount"
        time.sleep(30)

        # ---------- 90 开始走 ----------
        phase["name"] = "walk"
        beep(1200, 500)
        say("开始走路。跑带即将启动，速度每小时三公里。")
        c.send(xc.cmd_power_on())
        time.sleep(2)
        c.send(xc.cmd_speed(speed))

        walk_start = time.time()
        said_hr = False
        next_keep = time.time() + 20
        while time.time() - walk_start < 150:
            time.sleep(1)
            el = time.time() - walk_start
            if not said_hr and el > 55:
                say("请握住扶手电极，测心率。")
                said_hr = True
            if time.time() >= next_keep:
                try:
                    c.send(xc.cmd_speed(speed))
                except Exception:
                    pass
                next_keep += 20

        # ---------- 240 停下 ----------
        phase["name"] = "dismount"
        c.send(xc.cmd_speed(0))
        beep(520, 200, times=2)
        say("请停下来，双脚站定不要动。跑带会自动减速。")
        time.sleep(28)

        say("可以走下跑带了。")
        beep(880, 120, times=3)
        time.sleep(7)

    finally:
        try:
            c.send(xc.cmd_speed(0))
            time.sleep(1.2)
            c.send(xc.cmd_power_off())
        except Exception:
            pass
        time.sleep(2)
        c.close()

    say("标定结束，数据已保存。感谢配合。")
    beep(990, 400)

    # ---------- 落盘 + 汇总 ----------
    if not records:
        print("没有采到状态帧")
        return
    fn = "calibration.csv"
    with open(fn, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(records[0].keys()))
        w.writeheader()
        w.writerows(records)
    print("已保存 %d 帧 → %s" % (len(records), fn))

    def seg(n):
        return [r for r in records if r["phase"] == n]

    for n, label in (("mount", "站上跑带"), ("walk", "行走中"), ("dismount", "下机减速")):
        s = seg(n)
        if not s:
            continue
        print("[%s] %d 帧  speed %d→%d  mileage %d→%d  cal %d→%d  step %d→%d  dur %d→%d  hr_max=%d  person=%s" % (
            label, len(s), s[0]["speed"], s[-1]["speed"],
            s[0]["mileage"], s[-1]["mileage"], s[0]["calorie"], s[-1]["calorie"],
            s[0]["step"], s[-1]["step"], s[0]["duration"], s[-1]["duration"],
            max(r["hr"] for r in s), sorted(set(r["person"] for r in s))))

    walk = seg("walk")
    if len(walk) > 10:
        dur = walk[-1]["duration"] - walk[0]["duration"]
        dm = walk[-1]["mileage"] - walk[0]["mileage"]
        km = speed * dur / 3600.0
        print("\n行走 %d 秒, %.1f km/h → 理论 %.4f km, 里程原始增量 %d" % (dur, speed, km, dm))
        if dm:
            print("每个原始单位 = %.3f 米" % (km * 1000.0 / dm))


if __name__ == "__main__":
    main()
