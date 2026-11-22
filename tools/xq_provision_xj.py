#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小乔 SmartRun 配网 · 小江 SmartLink（com.xiaojiang.smartlink，libsmartlink.so）

从 so 反汇编还原：
  · socket(AF_INET, SOCK_DGRAM, UDP)，sendto 到 "239.X.Y.Z" : 10000
  · 数据流：[crc8][len][pwdLen][密码][SSID]，按两字节一组切开
  · 每个包：IP 的第 2 段 = 0x40 | (序号 & 0x3F)，第 3、4 段 = 这两个字节
            载荷固定 5 个 0 字节，两包之间 50ms，一轮结束停 200ms
  · CRC8：多项式 0x8C（反射），初值 0 —— 与 ESP-Touch 用的是同一个 CRC

注意：这是"实验版"。缓冲区头部三个字段（crc/len/pwdLen）是从反汇编推断的，
若设备不响应，说明头部含义还需再核。

用法：
    python xq_provision_xj.py                 # 自动读当前 WiFi
    python xq_provision_xj.py --seconds 120
"""

import argparse
import socket
import subprocess
import sys
import time

import xq_provision as esp

PORT = 10000
INTERVAL_MS = 50
ROUND_GAP_MS = 200

_CRC_TAB = []
for _i in range(256):
    _c = _i
    for _ in range(8):
        _c = (_c >> 1) ^ 0x8C if _c & 1 else _c >> 1
    _CRC_TAB.append(_c & 0xFF)


def crc8(data):
    v = 0
    for b in data:
        v = _CRC_TAB[(v ^ (b & 0xFF)) & 0xFF]
    return v


def build_stream(ssid, password):
    ssid_b = ssid.encode("utf-8")
    pwd_b = password.encode("utf-8")
    body = bytes([len(pwd_b) + len(ssid_b) + 1]) + bytes([len(pwd_b)]) + pwd_b + ssid_b
    c = crc8(body)
    stream = bytes([c]) + body
    if len(stream) % 2:
        stream += b"\x00"
    return stream


def send_round(sock, stream, bind_ip):
    n = len(stream) // 2
    for sl in range(n):
        b0 = stream[sl * 2]
        b1 = stream[sl * 2 + 1]
        oct3 = sl & 0x3F
        if (b0 ^ b1) & 1:          # 反汇编：0x40 & ((b0^b1) << 6)
            oct3 |= 0x40
        ip = "239.%d.%d.%d" % (oct3, b0, b1)
        try:
            sock.sendto(b"\x00" * 5, (ip, PORT))
        except OSError:
            pass
        time.sleep(INTERVAL_MS / 1000.0)
    time.sleep(ROUND_GAP_MS / 1000.0)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ssid")
    ap.add_argument("--password")
    ap.add_argument("--seconds", type=int, default=120)
    a = ap.parse_args()

    ssid, bssid, band = esp.get_wifi_info()
    ssid = a.ssid or ssid
    pwd = a.password
    if not pwd:
        prof = esp._decode(subprocess.run(
            ["netsh", "wlan", "show", "profile", "name=%s" % ssid, "key=clear"],
            capture_output=True).stdout)
        for line in prof.splitlines():
            if "关键内容" in line or "Key Content" in line:
                pwd = line.split(":", 1)[1].strip()
    if not ssid or not pwd:
        print("缺 SSID 或密码")
        return

    stream = build_stream(ssid, pwd)
    print("SSID=%s 密码长=%d 数据流 %d 字节 → %d 个组播包"
          % (ssid, len(pwd), len(stream), len(stream) // 2))
    print("流: %s" % stream.hex(" "))
    print("目标 239.64.x.y:10000，间隔 %dms" % INTERVAL_MS)

    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
    except OSError:
        pass
    bind_ip = esp.get_local_ip()
    try:
        s.bind((bind_ip, 0))
        print("已绑定网卡 %s" % bind_ip)
    except OSError as e:
        print("绑定失败: %s" % e)

    t0 = time.time()
    rnd = 0
    try:
        while time.time() - t0 < a.seconds:
            rnd += 1
            send_round(s, stream, bind_ip)
            print("  第 %d 轮完成 %.0fs" % (rnd, time.time() - t0))
    except KeyboardInterrupt:
        pass
    finally:
        s.close()
    print("结束")


if __name__ == "__main__":
    main()
