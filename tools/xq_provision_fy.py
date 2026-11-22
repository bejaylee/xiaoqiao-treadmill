#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小乔 SmartRun 跑步机 配网工具 · 飞燕 FyLink 方案（备选）

按 APK 内 com.xjiangiot.lib.fylink 逐行复刻：
  · 密码：AES/CFB/NoPadding，密钥 = hex("be6db4c9a7e43a60578a25036c5d955e")，IV = 16 字节 0
  · 打包：每个字节按 LSB 优先拆成 8 个 bit，再按 6 bit 一组压回去（"8->6"）
  · SSID：若全为可打印 ASCII(32..126) 则直接减 32，否则同密码做 6bit 打包
  · 报文： [数据长度][标志][ssid长][密码长][ssid][密码][校验高6位][校验低6位]
  · 发送：先 3 个 1248 字节包做前导，然后每字节 1 个包，长度 = ((i%8)+2)*128 + 字节值
          每 8 字节补 2 个 (992+块号) 标记包；目的端口从 50000 起逐个 +1
          每个包之间 sleep 500ms + 20ms（原样保留）

用法:
    python xq_provision_fy.py --ssid "Spider-Man" --password "xxxx" [--seconds 90]
    python xq_provision_fy.py --selftest
"""

import argparse
import re
import socket
import sys
import time

from Crypto.Cipher import AES

AES_KEY_HEX = "be6db4c9a7e43a60578a25036c5d955e"
PREAMBLE_LEN = 1248
PORT_START = 50000
SEND_DELAY_MS = 500
SEND_DELAY_AFTER_MS = 20


# ------------------------------------------------------------ 编码
def hex2bytes(s):
    s = s.strip()
    return bytes(int(s[i:i + 2], 16) for i in range(0, len(s) - 1, 2))


def bits6(data):
    """每字节 LSB 先出，凑成 bit 流后按 6 bit 一组输出（等价 di.d）"""
    bits = []
    for b in data:
        b &= 0xFF
        for i in range(8):
            bits.append((b >> i) & 1)
    n = (len(data) * 8 + 5) // 6
    out = bytearray(n)
    for k in range(n):
        base = k * 6
        v = 0
        for j in range(6):
            idx = base + j
            if idx < len(bits):
                v |= bits[idx] << j
        out[k] = v & 0xFF
    return bytes(out)


def is_ascii_printable(data):
    return all(32 <= b <= 126 for b in data)


def pack_ssid(ssid_bytes):
    """返回 (打包后字节, 是否需要位打包标志)"""
    if is_ascii_printable(ssid_bytes):
        return bytes((b - 32) & 0xFF for b in ssid_bytes), False
    return bits6(ssid_bytes), True


def aes_cfb(key, data):
    """Java: AES/CFB/NoPadding，CFB128，IV 全 0"""
    c = AES.new(key, AES.MODE_CFB, iv=bytes(16), segment_size=128)
    return c.encrypt(data)


def build_packet(ssid, password, key_hex=AES_KEY_HEX):
    key = hex2bytes(key_hex)
    pwd_ct = aes_cfb(key, password.encode("utf-8"))
    pwd_p = bits6(pwd_ct)
    ssid_p, flag_bit = pack_ssid(ssid.encode("utf-8"))

    data_len = len(ssid_p) + 6 + len(pwd_p)
    if data_len > 127:
        raise ValueError("SSID 与密码过长")

    buf = bytearray(128)
    buf[0] = data_len & 0xFF
    buf[1] = 0x0F | (0x20 if flag_bit else 0x00)
    buf[2] = len(ssid_p) & 0xFF
    buf[3] = len(pwd_p) & 0xFF

    idx = 4
    buf[idx:idx + len(ssid_p)] = ssid_p
    idx += len(ssid_p)
    buf[idx:idx + len(pwd_p)] = pwd_p
    idx += len(pwd_p)

    s = sum(buf[0:idx]) & 0xFFFF
    hi = (s >> 6) & 0x3F
    lo = s & 0x3F
    if hi == 0:
        hi = 1
    if lo == 0:
        lo = 1
    buf[idx] = hi
    buf[idx + 1] = lo
    return bytes(buf[0:idx + 2])


# ------------------------------------------------------------ 发送
class FySender(object):
    def __init__(self, packet):
        self.packet = packet
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        self.sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.buf = bytes(1500)
        self.port = PORT_START

    def send(self, length):
        try:
            self.sock.sendto(self.buf[:length], ("255.255.255.255", self.port))
        except Exception:
            pass
        self.port += 1
        time.sleep(SEND_DELAY_AFTER_MS / 1000.0)

    def one_round(self):
        time.sleep(SEND_DELAY_MS / 1000.0)
        for _ in range(3):
            self.send(PREAMBLE_LEN)

        seq = 2
        blk = 0
        for i, b in enumerate(self.packet):
            time.sleep(SEND_DELAY_MS / 1000.0)
            self.send(seq * 128 + (b & 0xFF))
            if i % 8 == 7:
                blk += 1
                time.sleep(SEND_DELAY_MS / 1000.0)
                self.send(992 + blk)
                time.sleep(SEND_DELAY_MS / 1000.0)
                self.send(992 + blk)
            seq += 1
            if seq == 10:
                seq = 2
        self.port = PORT_START

    def close(self):
        self.sock.close()


def selftest():
    pkt = build_packet("Spider-Man", "12345678")
    print("报文长度:", len(pkt), " hex:", pkt.hex(" "))
    # 结构自检
    assert pkt[0] == len(pkt), "长度字段应等于报文长度"
    assert pkt[1] in (0x0F, 0x2F)
    ssid_len, pwd_len = pkt[2], pkt[3]
    print("ssid块 %d 字节, 密码块 %d 字节, 标志=0x%02X" % (ssid_len, pwd_len, pkt[1]))
    # 还原 ssid
    ssid_p = pkt[4:4 + ssid_len]
    if pkt[1] == 0x0F:
        plain = bytes(b + 32 for b in ssid_p)
        print("还原 SSID:", plain.decode("utf-8", "ignore"))
        assert plain.decode() == "Spider-Man"
    s = sum(pkt[:4 + ssid_len + pwd_len]) & 0xFFFF
    print("校验: 计算 hi=0x%02X lo=0x%02X / 报文 hi=0x%02X lo=0x%02X"
          % ((s >> 6) & 0x3F, s & 0x3F, pkt[-2], pkt[-1]))
    assert ((s >> 6) & 0x3F) == pkt[-2] and (s & 0x3F) == pkt[-1]
    # 中文 SSID 走位打包
    pkt2 = build_packet("小乔跑步机", "12345678")
    print("中文SSID 报文长度:", len(pkt2), " 标志=0x%02X" % pkt2[1])
    assert pkt2[1] == 0x2F
    print("自测通过")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ssid")
    ap.add_argument("--password")
    ap.add_argument("--seconds", type=int, default=90)
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    if a.selftest:
        selftest()
        return
    if not a.ssid or not a.password:
        print("需要 --ssid 和 --password")
        return

    pkt = build_packet(a.ssid, a.password)
    print("SSID=%r  密码长度=%d  报文=%d 字节" % (a.ssid, len(a.password), len(pkt)))
    print("提示：先让跑步机进入配网态（待机下按住面板“-”键 5 秒，听到“哔”声）")
    print("下发中，每包 0.52s，一轮约 %.0f 秒…" % (len(pkt) * 0.52 + 2))

    sender = FySender(pkt)
    t0 = time.time()
    rnd = 0
    try:
        while time.time() - t0 < a.seconds:
            rnd += 1
            sender.one_round()
            print("  第 %d 轮完成，已用 %.0fs" % (rnd, time.time() - t0))
    except KeyboardInterrupt:
        pass
    finally:
        sender.close()
    print("结束")


if __name__ == "__main__":
    main()
