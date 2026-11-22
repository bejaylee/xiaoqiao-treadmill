#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
课程/计划跑 · 时间单位决定性验证

原 App 的 Encode12 里 start/end 发送前会 *60，所以线上单位是「秒」。
本脚本发 25 秒 + 25 秒两段，逐秒记录速度：
  - 第 25 秒左右从 2.0 跳到 4.0  → 秒 语义确认
  - 一直不变                      → 设备不按段执行，需改客户端调度

用法: python xq_plan_verify.py [ip]
"""
import sys
import time

sys.path.insert(0, ".")

import xq_client as xc

SEGS = [(2.0, 0, 25), (4.0, 25, 50)]   # (速度 km/h, 起始秒, 结束秒)
DURATION = 62


def main():
    ip = sys.argv[1] if len(sys.argv) > 1 else "192.168.100.178"
    c = xc.Client(ip)
    c.connect()
    print("已连接 %s:12304" % ip)

    plan = xc.cmd_plan(SEGS, index=0, mode_id=33, plan_id=1, uid=0, last_second=0)
    print("计划帧 %d 字节: %s" % (len(plan), plan.hex(" ")))
    c.send(plan)
    time.sleep(1.0)
    c.send(xc.cmd_power_on())
    print("已发计划 + 开机，开始采集 %d 秒\n" % DURATION)

    t0 = time.time()
    last = None
    timeline = []
    while time.time() - t0 < DURATION:
        st = c.last_status
        if st is not None:
            key = (st.speed, st.power, st.mode, st.mode_index)
            if key != last:
                el = time.time() - t0
                timeline.append((el, st.speed, st.power, st.mode, st.mode_index, st.percentage))
                print("  t=%5.1fs  speed=%3d(%.1f)  power=%d  mode=%d#%d  进度=%d%%"
                      % (el, st.speed, st.speed / 10.0, st.power, st.mode, st.mode_index, st.percentage))
                last = key
        time.sleep(0.25)

    print("\n停机中…")
    for _ in range(3):
        c.send(xc.cmd_speed(0))
        time.sleep(1.0)
    time.sleep(2)
    c.send(xc.cmd_power_off())
    time.sleep(1.0)
    c.close()

    print("\n=== 结论判据 ===")
    sp = [(t, s) for t, s, p, m, mi, pc in timeline]
    print("速度时间线:", ", ".join("%.1fs:%.1f" % (t, s / 10.0) for t, s in sp))
    hit = [t for t, s in sp if s >= 35]
    if hit:
        print("首次达到 4.0 的时刻: %.1fs（期望 ~25s）" % hit[0])
    else:
        print("全程未达到 4.0 —— 设备没有按段切换")


if __name__ == "__main__":
    main()
