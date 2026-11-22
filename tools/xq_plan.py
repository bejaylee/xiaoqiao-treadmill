#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小乔 SmartRun · 课程/计划跑（按原 App 逻辑复刻）

原 App 的做法（反编译证据）：
  1. 计划 = 多天课程，每天若干段，每段 = 速度 + 时长（分钟）
  2. 开始前把「当天全部分段」打成一帧 0x12 发给跑步机：
        index       = 上次跑到第几段
        lastSecond  = 该段内已跑的秒数
        每段 start/end = 累计秒（App 内部按分钟存，发送前 *60）
  3. 紧接着发开机命令 0x11 cmd=1 data=2
  4. 之后设备自己按段变速、自己计时，App 只显示进度
  5. 暂停/退出时把 index / 该段已跑秒数存起来，下次续跑

  跑步机的两个安全行为（实测）：
    · 起步一定先到 1.0 km/h（= App 里的 minSpeed），再由后续设定值接管
    · 跑带上没人时，计时与计划推进会被冻结（duration 停在 5 秒左右）

用法:
    python xq_plan.py 192.168.100.178 --demo          # 跑内置示例计划第 1 天
    python xq_plan.py 192.168.100.178 --plan my.json --day 2
    python xq_plan.py 192.168.100.178 --resume        # 续跑上次中断的位置
    python xq_plan.py --selftest                      # 离线自测组帧
"""

import argparse
import json
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import xq_client as xc

STATE_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "plan_state.json")

DEMO_PLAN = {
    "name": "入门 8 分钟",
    "days": [
        {"day": 1, "segments": [[3.0, 3], [5.0, 2], [4.0, 3]]},
        {"day": 2, "segments": [[3.5, 4], [5.5, 4]]},
    ],
}


# ---------------------------------------------------------------- 计划模型
def parse_text_plan(text):
    """与 App 端 Plan.java 同规则：每行一天，"速度:分钟"用逗号分隔，| 也可分天。"""
    import re
    days = []
    for line in re.split(r"[\n\r|｜]+", text):
        line = line.strip()
        if not line or line.startswith("#") or line.startswith("//"):
            continue
        line = re.sub(r"^(第\s*\d+\s*天|(?i:day)\s*\d+|(?i:d)\s*\d+)\s*[:：]\s*", "", line)
        segs = []
        for part in re.split(r"[,，;；]+", line):
            part = re.sub(r"(?i)km/h", "", part).strip()
            if not part:
                continue
            kv = re.split(r"[:：xX*]", part)
            if len(kv) != 2:
                return None
            try:
                segs.append([float(kv[0].strip()), int(kv[1].strip())])
            except ValueError:
                return None
        if segs:
            days.append({"day": len(days) + 1, "segments": segs})
    return {"name": "本地计划", "days": days} if days else None


def load_plan(path):
    with open(path, "r", encoding="utf-8") as f:
        return json.load(f)


def get_day(plan, day_no):
    for d in plan.get("days", []):
        if int(d.get("day", 0)) == int(day_no):
            return d
    raise SystemExit("计划里没有第 %s 天" % day_no)


def day_segments_seconds(day):
    """[[速度, 分钟], ...] -> [(速度, 起始秒, 结束秒)]"""
    items = [(float(s), float(m)) for s, m in day["segments"]]
    return xc.segments_from_minutes(items)


def segment_at(segs, elapsed):
    """elapsed 秒落在第几段（0-based）；超出末尾返回 len(segs)"""
    for i, (_spd, s0, s1) in enumerate(segs):
        if s0 <= elapsed < s1:
            return i
    return len(segs)


# ---------------------------------------------------------------- 断点状态
def save_state(**kw):
    with open(STATE_FILE, "w", encoding="utf-8") as f:
        json.dump(kw, f, ensure_ascii=False, indent=2)


def load_state():
    if not os.path.exists(STATE_FILE):
        return None
    with open(STATE_FILE, "r", encoding="utf-8") as f:
        return json.load(f)


def clear_state():
    if os.path.exists(STATE_FILE):
        os.remove(STATE_FILE)


# ---------------------------------------------------------------- 执行
def start_plan(c, segs, index=0, last_second=0, plan_id=1, uid=0, quiet=False):
    pkt = xc.cmd_plan(segs, index=index, mode_id=33, plan_id=plan_id,
                      uid=uid, last_second=last_second)
    if not quiet:
        print("计划帧 %d 字节: %s" % (len(pkt), pkt.hex(" ")))
        print("  共 %d 段，总长 %d 秒（%.1f 分钟）"
              % (len(segs), segs[-1][2], segs[-1][2] / 60.0))
    c.send(pkt)
    time.sleep(0.8)
    c.send(xc.cmd_power_on())
    if not quiet:
        print("已下发计划 + 开机命令\n")


def stop_belt(c, wait=6.0):
    print("\n正在停机…")
    t0 = time.time()
    while time.time() - t0 < wait:
        st = c.last_status
        if st is not None and st.speed == 0 and st.power in (0, 1, 3):
            break
        c.send(xc.cmd_speed(0))
        time.sleep(1.2)
    time.sleep(0.8)
    c.send(xc.cmd_power_off())
    time.sleep(0.6)


def run(ip, plan, day_no, plan_id=1, uid=0, resume=False, cap_extra=45, max_seconds=None):
    segs = day_segments_seconds(get_day(plan, day_no))
    total = segs[-1][2]

    index = 0
    last_second = 0
    if resume:
        st = load_state()
        if st and st.get("plan_id") == plan_id and st.get("day") == day_no:
            index = int(st.get("index", 0))
            last_second = int(st.get("elapsed_in_seg", 0))
            print("续跑：第 %d 段，该段已跑 %d 秒" % (index + 1, last_second))
        else:
            print("没有可续跑的状态，从头开始")

    c = xc.Client(ip, verbose=False)
    c.connect()
    time.sleep(0.5)

    start_plan(c, segs, index=index, last_second=last_second, plan_id=plan_id, uid=uid)

    print("开始执行（Ctrl-C 可中断，会自动停机）")
    print("提示：跑带上没人时设备会冻结计时，站上去才会推进\n")

    t0 = time.time()
    last_print = 0.0
    last_elapsed = -1
    frozen_since = None
    frozen_notified = False
    finished = False
    counted = last_second          # 设备侧已计秒数（续跑时从断点起算）
    base_duration = None

    try:
        limit = max_seconds if max_seconds else total + cap_extra
        while time.time() - t0 < limit:
            st = c.last_status
            now = time.time() - t0

            if st is not None:
                if base_duration is None and st.power in (1, 2):
                    base_duration = st.duration
                if base_duration is not None:
                    counted = max(counted, last_second + (st.duration - base_duration))

                seg_i = segment_at(segs, counted)
                speed_now, s0, s1 = segs[min(seg_i, len(segs) - 1)]

                if now - last_print >= 1.0:
                    pct = min(100.0, counted * 100.0 / total)
                    print("  [%5.1fs] 第 %d/%d 段  目标 %.1f  实际 %.1f km/h  "
                          "已计 %d/%d 秒  %d%%"
                          % (now, min(seg_i + 1, len(segs)), len(segs), speed_now,
                             st.speed / 10.0, counted, total, int(pct)))
                    last_print = now

                # 冻结检测：power=2 但 duration 不再增长
                if st.duration != last_elapsed:
                    frozen_since = now
                    frozen_notified = False
                elif st.power == 2 and not frozen_notified and now - frozen_since > 12.0:
                    print("  ⚠ 设备已暂停计时（跑带上无人），站上去后会自动继续")
                    frozen_notified = True
                last_elapsed = st.duration

                if counted >= total:
                    print("\n课程完成：设备已跑满 %d 秒" % total)
                    finished = True
                    break
            time.sleep(0.4)
    except KeyboardInterrupt:
        print("\n已中断")

    if not finished:
        st = c.last_status
        if st is not None:
            done = max(0, st.duration)
            seg_i = segment_at(segs, done)
            save_state(plan_id=plan_id, day=day_no, index=min(seg_i, len(segs) - 1),
                       elapsed_in_seg=max(0, done - segs[min(seg_i, len(segs) - 1)][1]),
                       total=total, updated=time.strftime("%Y-%m-%d %H:%M:%S"))
            print("已保存断点：第 %d 段，段内 %d 秒（下次 --resume 续跑）"
                  % (min(seg_i, len(segs) - 1) + 1, max(0, done - segs[min(seg_i, len(segs) - 1)][1])))

    stop_belt(c)
    if finished:
        save_state(plan_id=plan_id, day=day_no, day_over=True, index=0,
                   elapsed_in_seg=0, total=total,
                   updated=time.strftime("%Y-%m-%d %H:%M:%S"))
    c.close()


# ---------------------------------------------------------------- 自测
def selftest():
    segs = xc.segments_from_minutes([(2.0, 1), (4.0, 1)])
    assert segs == [(2.0, 0, 60), (4.0, 60, 120)], segs
    pkt = xc.cmd_plan(segs, index=0, mode_id=33, plan_id=1, uid=0, last_second=0)
    h = pkt.hex(" ")
    want = ("12 1c 00 21 00 01 00 00 00 00 00 00 00 00 "
            "00 14 00 00 3c 00 01 00 01 28 3c 00 78 00 02 00 ")
    ok = h.startswith(want)
    print("[%s] 计划帧结构" % ("OK " if ok else "BAD"))
    print("     " + h)
    if not ok:
        print("     期望前缀: " + want)

    # 续跑：第 2 段、段内 30 秒
    pkt2 = xc.cmd_plan(segs, index=1, mode_id=33, plan_id=7, uid=12345, last_second=30)
    b = bytes.fromhex(pkt2.hex(" ").replace(" ", ""))
    got = (b[2], b[3], b[4] << 8 | b[5], b[6:10].hex(), b[10] | b[11] << 8 | b[12] << 16 | b[13] << 24)
    exp = (1, 33, 7, "00003039", 30)
    ok2 = got == exp
    print("[%s] index/planId/uid/lastSecond: %s" % ("OK " if ok2 else "BAD", got))

    # 校验
    s = sum(b[2:len(b) - 4]) & 0xFFFF
    ok3 = s == ((b[-4] << 8) | b[-3])
    print("[%s] 帧校验 %04X" % ("OK " if ok3 else "BAD", s))

    print("\n自测%s" % ("全部通过" if ok and ok2 and ok3 else "存在不一致"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("ip", nargs="?", help="跑步机 IP")
    ap.add_argument("--plan", help="计划 JSON 文件")
    ap.add_argument("--text", help="文本计划，如 \"3:3,5:2 | 4:4\"")
    ap.add_argument("--text-file", help="文本计划文件（每行一天）")
    ap.add_argument("--list", action="store_true", help="只列出各天，不连接设备")
    ap.add_argument("--next-day", action="store_true", help="接着已完成那天的下一天")
    ap.add_argument("--day", type=int, default=1, help="第几天（默认 1）")
    ap.add_argument("--demo", action="store_true", help="使用内置示例计划")
    ap.add_argument("--resume", action="store_true", help="从上次断点续跑")
    ap.add_argument("--plan-id", type=int, default=1)
    ap.add_argument("--uid", type=int, default=0)
    ap.add_argument("--max-seconds", type=int, default=None, help="本次最多跑多少秒（冒烟测试用）")
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    if a.selftest:
        selftest()
        return
    if not a.ip and not a.list:
        ap.error("需要跑步机 IP（或用 --list / --selftest）")
    if a.text_file:
        plan = parse_text_plan(open(a.text_file, "r", encoding="utf-8").read())
    elif a.text:
        plan = parse_text_plan(a.text)
    elif a.demo or not a.plan:
        plan = DEMO_PLAN
    else:
        plan = load_plan(a.plan)
    if not plan or not plan.get("days"):
        raise SystemExit("计划为空或格式不对")

    if a.list:
        print("计划: %s" % plan.get("name", "未命名"))
        for d in plan["days"]:
            total = sum(int(round(m * 60)) for _, m in d["segments"])
            detail = "  ".join("%.1f km/h×%d分" % (s, m) for s, m in d["segments"])
            print("  第 %d 天  共 %d 分钟  %s" % (d["day"], total // 60, detail))
        st = load_state()
        if st:
            print("\n上次状态: 第 %s 天%s" % (st.get("day"),
                  "（已完成）" if st.get("day_over") else "（未完成，可 --resume）"))
        return

    day = a.day
    if a.next_day:
        st = load_state()
        if st and st.get("day_over"):
            day = int(st.get("day", 1)) + 1
        else:
            print("没有『已完成』的记录，仍跑第 %d 天" % day)

    total_days = len(plan["days"])
    if not any(int(d["day"]) == day for d in plan["days"]):
        raise SystemExit("计划只有 %d 天，没有第 %d 天" % (total_days, day))

    print("计划: %s  第 %d/%d 天" % (plan.get("name", "未命名"), day, total_days))
    run(a.ip, plan, day, plan_id=a.plan_id, uid=a.uid, resume=a.resume,
        max_seconds=a.max_seconds)


if __name__ == "__main__":
    main()
