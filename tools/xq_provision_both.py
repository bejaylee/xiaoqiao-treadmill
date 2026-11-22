#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小乔 SmartRun 配网 · 双协议连发（ESP-Touch + 飞燕 FyLink）

为什么要有这个：设备的 WiFi 模块可能属于两种方案之一，单发一种可能一直不中。
本脚本交替发送两套包，并在 18266（ESP 回执）和 19032（设备广播）同时监听，
一旦设备入网立刻停止并打印 IP。

用法（先按住面板“-”键 5 秒听到“哔”声，再运行）：
    python xq_provision_both.py
    python xq_provision_both.py --seconds 240
    python xq_provision_both.py --ssid "xxx" --password "yyy"

依赖: pycryptodome（飞燕分支需要）
"""

import argparse
import socket
import subprocess
import sys
import threading
import time

import xq_provision as esp
import xq_provision_fy as fy
import xq_provision_xj as xj


class Listener(threading.Thread):
    """同时监听 ESP 回执(18266) 与 设备广播(19032)"""

    def __init__(self):
        super().__init__(daemon=True)
        self.device = None       # (mac, ip)
        self.stop_flag = False

    def run(self):
        # 18266：ESP-Touch 回执
        s1 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s1.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s1.bind(("", 18266))
        except OSError as e:
            print("  [警告] 18266 绑定失败: %s" % e)
        s1.settimeout(0.5)

        # 19032：设备入网后的广播
        s2 = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s2.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s2.bind(("", 19032))
        except OSError as e:
            print("  [警告] 19032 绑定失败: %s" % e)
        s2.settimeout(0.5)

        while not self.stop_flag and self.device is None:
            for s, kind in ((s1, "esp"), (s2, "xq")):
                try:
                    data, addr = s.recvfrom(256)
                except socket.timeout:
                    continue
                except OSError:
                    continue
                if kind == "esp" and len(data) >= 11:
                    mac = ":".join("%02x" % b for b in data[1:7])
                    ip = ".".join(str(b) for b in data[7:11])
                    self.device = (mac, ip)
                elif kind == "xq" and len(data) >= 19 and data[:4] == b"XJXQ":
                    mac = ":".join("%02x" % b for b in data[8:14])
                    self.device = (mac, addr[0])
        s1.close()
        s2.close()


def esp_cycle(sock, guide, datum, target):
    """一轮 ESP-Touch：引导 2s + 数据 4s"""
    g_end = time.time() + esp.GUIDE_PHASE_MS / 1000.0
    while time.time() < g_end:
        for p in guide:
            sock.sendto(p, target)
            time.sleep(esp.GUIDE_INTERVAL_MS / 1000.0)
    d_end = time.time() + esp.DATA_PHASE_MS / 1000.0
    idx = 0
    while time.time() < d_end:
        for k in range(3):
            sock.sendto(datum[(idx + k) % len(datum)], target)
            time.sleep(esp.DATA_INTERVAL_MS / 1000.0)
        idx = (idx + 3) % len(datum)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ssid")
    ap.add_argument("--password")
    ap.add_argument("--bssid")
    ap.add_argument("--seconds", type=int, default=210)
    ap.add_argument("--esp-only", action="store_true", help="只发 ESP-Touch，密度更高")
    ap.add_argument("--with-xj", action="store_true", help="每轮夹带一次小江组播")
    a = ap.parse_args()

    ssid, bssid, band = esp.get_wifi_info()
    ssid = a.ssid or ssid
    bssid = a.bssid or bssid
    pwd = a.password
    if not pwd:
        prof = esp._decode(subprocess.run(
            ["netsh", "wlan", "show", "profile", "name=%s" % ssid, "key=clear"],
            capture_output=True).stdout)
        for line in prof.splitlines():
            if "关键内容" in line or "Key Content" in line:
                pwd = line.split(":", 1)[1].strip()
    if not ssid or not pwd:
        print("缺 SSID 或密码，请用 --ssid / --password 指定")
        return
    if not bssid:
        bssid = "00:00:00:00:00:00"

    ip = esp.get_local_ip()
    print("=" * 60)
    print("SSID   : %s" % ssid)
    print("BSSID  : %s" % bssid)
    print("频段   : %s" % (band or "未知"))
    print("本机IP : %s" % ip)
    print("密码   : 长度 %d（不显示）" % len(pwd))
    print("=" * 60)
    if band == "5G":
        print("!! 当前是 5GHz，设备只支持 2.4GHz，请先切到 2.4G !!")
        return
    print("开始连发。若还没进配网态，现在立刻：待机下按住面板“-”键 5 秒，听到“哔”声")
    print("")

    guide = esp.guide_packets()
    datum = esp.build_datum(ssid, pwd, bssid, ip)
    fy_pkt = fy.build_packet(ssid, pwd)
    xj_stream = xj.build_stream(ssid, pwd)
    xj_sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    xj_sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        xj_sock.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    except OSError:
        pass
    try:
        xj_sock.bind((ip, 0))
    except OSError:
        pass
    print("ESP 数据包 %d 个 / 飞燕报文 %d 字节" % (len(datum), len(fy_pkt)))

    lis = Listener()
    lis.start()

    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind((ip, 0))          # 显式绑定 WiFi 网卡，避免走错接口
        print("已绑定发送网卡: %s" % ip)
    except OSError as e:
        print("绑定 %s 失败: %s（改用默认路由）" % (ip, e))
    subnet = ip.rsplit(".", 1)[0] + ".255"
    targets = [("255.255.255.255", esp.TARGET_PORT), (subnet, esp.TARGET_PORT)]
    print("广播目标: %s" % ", ".join("%s:%d" % t for t in targets))
    sender = fy.FySender(fy_pkt)

    t0 = time.time()
    cycle = 0
    try:
        while time.time() - t0 < a.seconds and lis.device is None:
            cycle += 1
            tgt = targets[cycle % len(targets)]
            print("[%4.0fs] 第 %d 轮：ESP-Touch ×2 -> %s" % (time.time() - t0, cycle, tgt[0]))
            esp_cycle(sock, guide, datum, tgt)
            esp_cycle(sock, guide, datum, tgt)
            if lis.device:
                break
            if a.with_xj:
                print("[%4.0fs] 第 %d 轮：小江组播 ×1" % (time.time() - t0, cycle))
                xj.send_round(xj_sock, xj_stream, ip)
            if a.esp_only and not a.with_xj:
                continue
            print("[%4.0fs] 第 %d 轮：飞燕 FyLink ×1（约 19s）" % (time.time() - t0, cycle))
            sender.one_round()
    except KeyboardInterrupt:
        print("\n已中断")
    finally:
        sock.close()
        sender.close()
        xj_sock.close()
        lis.stop_flag = True

    print("")
    if lis.device:
        print(">>> 配网成功！ MAC=%s  IP=%s" % lis.device)
        print(">>> 接着运行:  python xq_client.py %s" % lis.device[1])
    else:
        print(">>> 仍未成功。建议：")
        print("    1) 确认按“-”键时是待机画面，且听到“哔”声")
        print("    2) 路由器若开了「AP 隔离 / 访客网络」，换主网络再试")
        print("    3) 把电脑靠近跑步机 1 米内再试")


if __name__ == "__main__":
    main()
