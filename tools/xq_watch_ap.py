#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
跑步机配网态探测器：盯着 WiFi 热点列表，看按下按键后有没有新热点冒出来。

用法：
    python xq_watch_ap.py            # 监视 120 秒
    python xq_watch_ap.py 180

运行后按提示依次尝试面板按键（-、+、开始、停止…），每次按住 5 秒。
如果出现新热点（尤其名字里有 XJiang / XQ / ESP / Smart / 一串字母数字），
说明设备开了自己的 AP —— 那条路比 SmartConfig 更好走，直接连上去就能控制。
"""

import re
import subprocess
import sys
import time


def _decode(b):
    for enc in ("utf-8", "gbk"):
        try:
            s = b.decode(enc)
            if "\ufffd" not in s:
                return s
        except UnicodeDecodeError:
            continue
    return b.decode("gbk", "ignore")


def scan():
    out = _decode(subprocess.run(["netsh", "wlan", "show", "networks",
                                  "mode=bssid"], capture_output=True).stdout)
    nets = {}
    name = None
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("SSID") and "BSSID" not in s and ":" in s:
            name = s.split(":", 1)[1].strip()
        elif s.startswith("BSSID") and name is not None:
            mac = s.split(":", 1)[1].strip()
            nets.setdefault(name, []).append(mac)
            name = None
    return nets


def main():
    seconds = int(sys.argv[1]) if len(sys.argv) > 1 else 120
    print("先扫一遍基线…")
    base = scan()
    print("当前可见 %d 个热点：%s" % (len(base), ", ".join(sorted(n or "(隐藏)" for n in base))))
    print("")
    print("现在去做：待机画面下，按住面板 \"-\" 键 5 秒，听到“哔”后松手。")
    print("如果 20 秒内没看到新热点，再依次试：\"+\" 键 5 秒 / \"开始\"键 5 秒 / \"停止\"键 5 秒")
    print("每次按完等 10 秒看结果。")
    print("-" * 64)

    t0 = time.time()
    seen_new = set()
    while time.time() - t0 < seconds:
        time.sleep(5)
        cur = scan()
        new = [n for n in cur if n not in base and n]
        for n in new:
            if n not in seen_new:
                seen_new.add(n)
                macs = ", ".join(cur[n])
                print("\n>>> 新热点出现！ %-24s  BSSID: %s" % (n, macs))
                print(">>> 这很可能就是跑步机的配置热点，记下名字告诉我就行")
        alive = sum(1 for n in cur if n)
        sys.stdout.write("\r  [%3ds] 可见 %d 个 / 新发现 %d 个   " % (time.time() - t0, alive, len(seen_new)))
        sys.stdout.flush()

    print("")
    if seen_new:
        print("\n共发现 %d 个新热点：%s" % (len(seen_new), ", ".join(sorted(seen_new))))
    else:
        print("\n没有出现任何新热点。说明按键没有让设备开出 AP，")
        print("它走的是 SmartConfig 那条路（或按键方式不对）。")


if __name__ == "__main__":
    main()
