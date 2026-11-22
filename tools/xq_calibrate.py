#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
实机标定脚本：里程单位 / 卡路里 / 步数 / 心率 / 时长 / 速度回显

时间表（脚本会自动倒计时并提示，你只需照着做）：

    [-60s]  准备：确认跑带上没有杂物，安全锁夹好
    [  0s]  开始采集（空载基线 15 秒）—— 站在旁边别动
    [ 15s]  ① 上跑带，双脚站定，双手扶扶手（25 秒）
    [ 40s]  ② 开始走路，速度固定 3.0 km/h，走 150 秒
            期间请**握住扶手电极至少 30 秒**（测心率）
    [190s]  ③ 停下来，站定别动（25 秒，跑带自动减速到 0）
    [215s]  ④ 走下跑带
    [230s]  关机，采集结束

用法：
    python xq_calibrate.py 192.168.100.178
    python xq_calibrate.py 192.168.100.178 --prep 120 --speed 3.0

安全：全程速度 3.0 km/h（慢走），结束必发减速到 0 + 关机。
"""

import argparse
import csv
import sys
import time

import xq_client as xc

WALK_SPEED = 3.0


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ip")
    ap.add_argument("--prep", type=int, default=60, help="准备倒计时秒数")
    ap.add_argument("--speed", type=float, default=WALK_SPEED)
    ap.add_argument("--baseline", type=int, default=15)
    ap.add_argument("--mount", type=int, default=25)
    ap.add_argument("--walk", type=int, default=150)
    ap.add_argument("--dismount", type=int, default=25)
    a = ap.parse_args()

    records = []
    phase = {"name": "prep"}
    t_start = [0.0]

    orig = xc.Client._on_frame

    def hook(self, p):
        if p and p[0] == 0x01:
            st = xc.Status.parse(p)
            if st:
                records.append({
                    "t": round(time.time() - t_start[0], 2),
                    "phase": phase["name"],
                    "raw": p.hex(" "),
                    "speed": st.speed,
                    "duration": st.duration,
                    "mileage": st.mileage,
                    "calorie": st.calorie,
                    "step": st.step,
                    "hr": st.heart_rate,
                    "power": st.power,
                    "error": st.error,
                    "mode": st.mode,
                    "idx": st.mode_index,
                    "person": st.person_on,
                    "finish": st.finish,
                    "pct": st.percentage,
                })
        orig(self, p)

    xc.Client._on_frame = hook

    print("=" * 66)
    print(" 跑步机标定：里程 / 卡路里 / 步数 / 心率 / 时长")
    print("=" * 66)
    print(" 待会儿的时间表（按顺序做，不用按键）：")
    print("   +%ds  上跑带站定（双手扶扶手）" % (a.prep + a.baseline))
    print("   +%ds  开始走路（%.1f km/h）" % (a.prep + a.baseline + a.mount, a.speed))
    print("         └ 走路期间握住扶手电极 ≥30 秒")
    print("   +%ds  停下来站定" % (a.prep + a.baseline + a.mount + a.walk))
    print("   +%ds  走下跑带" % (a.prep + a.baseline + a.mount + a.walk + a.dismount))
    print("=" * 66)

    c = xc.Client(a.ip)
    try:
        c.connect()
    except Exception as e:
        print("连接失败: %s" % e)
        print("先跑 python xq_client.py scan 确认设备 IP")
        return

    try:
        for i in range(a.prep, 0, -1):
            sys.stdout.write("\r 准备中… %3d 秒后开始采集（现在别碰跑带）" % i)
            sys.stdout.flush()
            time.sleep(1)
        print("\r 开始采集！" + " " * 40)

        t_start[0] = time.time()
        phase["name"] = "baseline"
        print("[%5.0fs] 空载基线 %ds —— 站旁边别动" % (0, a.baseline))
        time.sleep(a.baseline)

        phase["name"] = "mount"
        print("[%5.0fs] >>> 现在上跑带，双脚站定，双手扶扶手（%ds）"
              % (time.time() - t_start[0], a.mount))
        time.sleep(a.mount)

        phase["name"] = "walk"
        print("[%5.0fs] >>> 开始走！速度 %.1f km/h，走 %ds，期间握电极 30 秒"
              % (time.time() - t_start[0], a.speed, a.walk))
        c.send(xc.cmd_power_on())
        time.sleep(2)
        c.send(xc.cmd_speed(a.speed))

        walk_end = time.time() + a.walk
        next_keep = time.time() + 20
        while time.time() < walk_end:
            time.sleep(1)
            if time.time() >= next_keep:
                c.send(xc.cmd_speed(a.speed))     # 保活，防止设备自动降速
                next_keep += 20
        print("[%5.0fs] >>> 停下来，站定别动（%ds），跑带会自动减速"
              % (time.time() - t_start[0], a.dismount))

        phase["name"] = "dismount"
        c.send(xc.cmd_speed(0))
        time.sleep(a.dismount)
        print("[%5.0fs] >>> 现在可以走下跑带" % (time.time() - t_start[0]))

    finally:
        try:
            c.send(xc.cmd_speed(0))
            time.sleep(1.2)
            c.send(xc.cmd_power_off())
        except Exception:
            pass
        time.sleep(2)
        c.close()

    if not records:
        print("\n没有采到任何状态帧")
        return

    # ---------- 落盘 ----------
    fn = "calibration.csv"
    with open(fn, "w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=list(records[0].keys()))
        w.writeheader()
        w.writerows(records)
    print("\n已保存 %d 帧 → %s" % (len(records), fn))

    # ---------- 汇总 ----------
    def seg(name):
        return [r for r in records if r["phase"] == name]

    print("\n" + "=" * 66)
    print(" 结果")
    print("=" * 66)

    for name, label in (("baseline", "空载基线"), ("mount", "站上跑带"),
                        ("walk", "行走中"), ("dismount", "下机减速")):
        s = seg(name)
        if not s:
            continue
        print("\n[%s] %d 帧" % (label, len(s)))
        print("  速度   %d → %d （0.1km/h）" % (s[0]["speed"], s[-1]["speed"]))
        print("  里程   %d → %d   增量 %d" % (s[0]["mileage"], s[-1]["mileage"],
                                             s[-1]["mileage"] - s[0]["mileage"]))
        print("  卡路里 %d → %d   增量 %d" % (s[0]["calorie"], s[-1]["calorie"],
                                             s[-1]["calorie"] - s[0]["calorie"]))
        print("  步数   %d → %d   增量 %d" % (s[0]["step"], s[-1]["step"],
                                             s[-1]["step"] - s[0]["step"]))
        print("  时长   %d → %d 秒" % (s[0]["duration"], s[-1]["duration"]))
        print("  心率   max=%d  person=%s" % (max(r["hr"] for r in s),
                                              sorted(set(r["person"] for r in s))))
        print("  电源位 %s" % sorted(set(r["power"] for r in s)))

    # 里程单位反推
    walk = seg("walk")
    if len(walk) > 10:
        dur = walk[-1]["duration"] - walk[0]["duration"]
        dist_km = a.speed * dur / 3600.0
        dm = walk[-1]["mileage"] - walk[0]["mileage"]
        print("\n--- 里程单位反推 ---")
        print("  行走时长 %d 秒，速度 %.1f km/h → 理论距离 %.4f km" % (dur, a.speed, dist_km))
        print("  里程原始增量 %d" % dm)
        if dm and dist_km:
            print("  → 每个原始单位 = %.3f 米" % (dist_km * 1000.0 / dm))
            print("  → 若单位是 1m，期望增量 %d；0.1m 期望 %d；0.01km 期望 %.0f"
                  % (dist_km * 1000, dist_km * 10000, dist_km * 1))
    print()


if __name__ == "__main__":
    main()
