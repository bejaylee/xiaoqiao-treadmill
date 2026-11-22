#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""计划跑真人验证（语音引导）：3.0 km/h × 1 分钟 → 4.5 km/h × 1 分钟"""

import sys
import time
import threading

sys.path.insert(0, ".")

import xq_client as xc
import xq_plan as xp

try:
    from xq_calibrate_voice import say as _say
except Exception:
    _say = None

try:
    import winsound
except Exception:
    winsound = None

IP = sys.argv[1] if len(sys.argv) > 1 else "192.168.100.178"
SEGS = xc.segments_from_minutes([(3.0, 1), (4.5, 1)])
TOTAL = SEGS[-1][2]


def speak(text):
    print("[语音] " + text, flush=True)
    if _say:
        threading.Thread(target=_say, args=(text,), daemon=True).start()


def beep(freq=880, ms=180, n=1):
    if winsound:
        for _ in range(n):
            try:
                winsound.Beep(freq, ms)
            except Exception:
                pass


def main():
    c = xc.Client(IP, verbose=False)
    c.connect()
    time.sleep(0.5)

    speak("计划跑验证开始。第一段，三公里每小时，请开始走。")
    beep(880, 200, 2)

    xp.start_plan(c, SEGS, index=0, last_second=0, plan_id=101, uid=0, quiet=True)
    print("计划已下发：3.0 km/h × 60s → 4.5 km/h × 60s，总 120 秒", flush=True)

    t0 = time.time()
    base = None
    last_seg = -1
    last_dur = -1
    frozen_notified = False
    last_change = time.time()
    done = False

    try:
        while time.time() - t0 < TOTAL + 90:
            st = c.last_status
            if st is not None:
                if base is None and st.power >= 1:
                    base = st.duration
                counted = max(0, st.duration - base) if base is not None else 0
                seg = xp.segment_at(SEGS, counted)
                print("[%5.1fs] 实际 %.1f  已计 %d/%d  第 %d 段  power=%d 人=%d  %d%%"
                      % (time.time() - t0, st.speed / 10.0, counted, TOTAL,
                         min(seg + 1, len(SEGS)), st.power, st.person_on,
                         min(100, counted * 100 // TOTAL)), flush=True)

                if seg != last_seg:
                    if seg == 1:
                        speak("进入第二段，四点五公里每小时。")
                        beep(1040, 220, 2)
                    last_seg = seg

                # 设备每 2 秒才更新一次 duration，所以用"12 秒没变化"判定，避免误报
                if st.duration != last_dur:
                    last_change = time.time()
                    frozen_notified = False
                elif (st.power == 2 and not frozen_notified
                      and time.time() - last_change > 12.0):
                    speak("设备检测不到人，计时已暂停。请站到跑带中间。")
                    frozen_notified = True
                last_dur = st.duration

                if counted >= TOTAL:
                    speak("计划完成，正在停机。")
                    done = True
                    break
            time.sleep(0.4)
    except KeyboardInterrupt:
        pass

    if not done:
        speak("时间到，正在停机。")

    xp.stop_belt(c)
    time.sleep(1.0)
    st = c.last_status
    if st is not None:
        print("\n结束状态:", st, flush=True)
    c.close()

    speak("测试结束，可以走下跑带了。")
    beep(660, 200, 3)


if __name__ == "__main__":
    main()
