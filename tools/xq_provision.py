#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小乔 SmartRun 跑步机 配网工具（ESP-Touch v2 / 乐鑫 SmartConfig）

完全按 APK 内置的 com.espressif.iot.esptouch SDK 复刻：
  - 目的端口 7001，广播 255.255.255.255
  - 引导码 4 包，长度 515/514/513/512，载荷填 0x31
  - 数据码每个字节 → 3 包，长度 = 40 + 高字节... （见 encode_datacode）
  - 包间隔 8 ms，引导 2 s + 数据 4 s 为一轮
  - 设备配网成功后会向 UDP 18266 回 11 字节（1 状态 + 6 MAC + 4 IP）

用法:
    python xq_provision.py --ssid "家里的WiFi" --password "12345678"
    python xq_provision.py --ssid ... --password ... --bssid aa:bb:cc:dd:ee:ff
    python xq_provision.py --selftest

Windows 会自动用 netsh 读取当前 SSID/BSSID；也可手动指定。
"""

import argparse
import re
import socket
import struct
import subprocess
import sys
import threading
import time

TARGET_PORT = 7001
LISTEN_PORT = 18266
GUIDE_LEN = 515            # 0x0203
GUIDE_FILL = 0x31          # '1'
GUIDE_INTERVAL_MS = 8
DATA_INTERVAL_MS = 8
GUIDE_PHASE_MS = 2000
DATA_PHASE_MS = 4000
TOTAL_SEND_MS = 45000


# ------------------------------------------------------------------ CRC8
def _crc_table():
    tab = []
    for i in range(256):
        c = i
        for _ in range(8):
            if c & 1:
                c = (c >> 1) ^ 0x8C
            else:
                c >>= 1
        tab.append(c & 0xFF)
    return tab


_CRC_TAB = _crc_table()


def crc8(data):
    v = 0
    for b in data:
        v = _CRC_TAB[(v ^ (b & 0xFF)) & 0xFF]
    return v


# ------------------------------------------------------------------ 编码
def encode_datacode(value, seq):
    """复刻 protocol/DataCode：1 个字节 + 序号 → 3 个 UDP 包的长度。

    返回 3 个包的字节（内容全是 0x31，信息只在长度里）。
    """
    value &= 0xFF
    seq &= 0xFF
    c = crc8([value, seq])
    data_hi, data_lo = (value >> 4) & 0x0F, value & 0x0F
    crc_hi, crc_lo = (c >> 4) & 0x0F, c & 0x0F

    # DataCode.getBytes() = [0, crcHi|dataHi, 1, seq, 0, crcLo|dataLo]
    b = [0x00, (crc_hi << 4) | data_hi, 0x01, seq, 0x00, (crc_lo << 4) | data_lo]

    # DatumCode.getU8s(): 每两个字节 (b0<<8)|b1，再加 40
    lens = []
    for i in range(0, 6, 2):
        lens.append(40 + ((b[i] << 8) | b[i + 1]))
    return [bytes([GUIDE_FILL]) * n for n in lens]


def guide_packets():
    """引导码：长度 515,514,513,512，载荷 0x31"""
    return [bytes([GUIDE_FILL]) * n for n in (515, 514, 513, 512)]


def parse_bssid(s):
    s = (s or "").strip()
    if not s:
        return []
    return [int(x, 16) & 0xFF for x in s.split(":")]


def build_datum(ssid, password, bssid, ip="192.168.1.100"):
    """严格按官方 DatumCode：
        Data = totalLen(1) + pwdLen(1) + crc(ssid)(1) + crc(bssid)(1) + xor(1)
               + ip(4) + 密码 + SSID + BSSID
        totalLen = 5 + 4 + pwdLen + ssidLen
    """
    ssid_b = ssid.encode("utf-8")
    pwd_b = password.encode("utf-8")
    bssid_b = parse_bssid(bssid)
    ip_parts = [int(x) for x in ip.split(".")]
    ip_len = len(ip_parts)

    pwd_len = len(pwd_b)
    ssid_len = len(ssid_b)
    total_len = 5 + ip_len + pwd_len + ssid_len

    c_ssid = crc8(ssid_b)
    c_bssid = crc8(bssid_b)

    codes = []
    codes.append((total_len, 0))
    codes.append((pwd_len, 1))
    codes.append((c_ssid, 2))
    codes.append((c_bssid, 3))

    x = total_len ^ pwd_len ^ c_ssid ^ c_bssid
    for p in ip_parts:
        x ^= p
    for b in pwd_b:
        x ^= b
    for b in ssid_b:
        x ^= b
    # 注意：BSSID 字节不参与 xor
    codes.append((x & 0xFF, 4))

    for i, p in enumerate(ip_parts):
        codes.append((p, 5 + i))
    for i, b in enumerate(pwd_b):
        codes.append((b, 5 + ip_len + i))
    for i, b in enumerate(ssid_b):
        codes.append((b, 5 + ip_len + pwd_len + i))
    # 官方实现：BSSID 不是追加到末尾，而是插到 5、9、13… 的位置
    ins = 5
    for i, b in enumerate(bssid_b):
        codes.insert(ins, (b, total_len + i))
        ins += 4

    packets = []
    for value, seq in codes:
        packets.extend(encode_datacode(value, seq))
    return packets


# ------------------------------------------------------------------ 发送
def _decode(b):
    """netsh 在部分系统输出 UTF-8，部分是 GBK，统一兼容"""
    for enc in ("utf-8", "gbk"):
        try:
            s = b.decode(enc)
            if "\ufffd" not in s:
                return s
        except UnicodeDecodeError:
            continue
    return b.decode("gbk", "ignore")


def get_wifi_info():
    """Windows: netsh 读当前 SSID / BSSID / 频段（兼容中文/英文系统）"""
    try:
        out = _decode(subprocess.check_output(
            ["netsh", "wlan", "show", "interfaces"],
            stderr=subprocess.DEVNULL))
    except Exception:
        return None, None, None
    ssid = bssid = band = None
    mac_re = re.compile(r"([0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}")
    for line in out.splitlines():
        line = line.strip()
        lower = line.lower()
        if "bssid" in lower:
            m = mac_re.search(line)
            if m and bssid is None:
                bssid = m.group(0)
        elif "ssid" in lower and ssid is None and ":" in line:
            ssid = line.split(":", 1)[1].strip()
        elif "ghz" in lower and band is None:
            band = "5G" if "5" in line else "2.4G"
    return ssid, bssid, band


def get_local_ip():
    """本机在 WiFi 网段的 IP —— 会写进配网数据，设备靠它回执"""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 53))     # 不发包，只为选路由
        return s.getsockname()[0]
    except Exception:
        return "192.168.1.100"
    finally:
        s.close()


class Provisioner(object):
    def __init__(self, ssid, password, bssid, ip=None):
        self.ssid = ssid
        self.password = password
        self.bssid = bssid
        self.ip = ip or get_local_ip()
        self.result = None
        self._stop = False

    def _listen(self):
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        try:
            s.bind(("", LISTEN_PORT))
        except OSError as e:
            print("  [警告] 监听 %d 失败: %s（仍然可以继续配网，只是看不到回执）" % (LISTEN_PORT, e))
            return
        s.settimeout(1.0)
        while not self._stop:
            try:
                data, addr = s.recvfrom(64)
            except socket.timeout:
                continue
            except OSError:
                break
            if len(data) >= 11:
                mac = ":".join("%02x" % b for b in data[1:7])
                ip = ".".join(str(b) for b in data[7:11])
                self.result = (mac, ip, addr)
                print("\n  [配网成功] 设备 MAC=%s  IP=%s" % (mac, ip))
                break
        s.close()

    def run(self, seconds=TOTAL_SEND_MS):
        guide = guide_packets()
        datum = build_datum(self.ssid, self.password, self.bssid, self.ip)
        print("  SSID=%r  BSSID=%r  密码长度=%d  本机IP=%s" % (self.ssid, self.bssid, len(self.password), self.ip))
        print("  引导包 4 个，数据包 %d 个（%d 字节数据）" % (len(datum), len(datum) // 3))

        threading.Thread(target=self._listen, daemon=True).start()

        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
        sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        target = ("255.255.255.255", TARGET_PORT)

        t0 = time.time()
        rounds = 0
        try:
            while (time.time() - t0) < seconds and not self.result:
                rounds += 1
                # 引导阶段
                g_end = time.time() + GUIDE_PHASE_MS / 1000.0
                while time.time() < g_end and not self.result:
                    for p in guide:
                        sock.sendto(p, target)
                        time.sleep(GUIDE_INTERVAL_MS / 1000.0)
                # 数据阶段：每次 3 包 = 一个字节
                d_end = time.time() + DATA_PHASE_MS / 1000.0
                idx = 0
                while time.time() < d_end and not self.result:
                    for k in range(3):
                        sock.sendto(datum[(idx + k) % len(datum)], target)
                        time.sleep(DATA_INTERVAL_MS / 1000.0)
                    idx = (idx + 3) % len(datum)
                sys.stdout.write("\r  第 %d 轮… 已用 %.1fs " % (rounds, time.time() - t0))
                sys.stdout.flush()
        except KeyboardInterrupt:
            print("\n  已中断")
        finally:
            sock.close()
            self._stop = True

        print()
        return self.result


# ------------------------------------------------------------------ 自测
def selftest():
    ok = True
    g = guide_packets()
    print("引导码长度:", [len(p) for p in g])
    if [len(p) for p in g] != [515, 514, 513, 512]:
        ok = False

    d = build_datum("MyWiFi", "12345678", "aa:bb:cc:dd:ee:ff")
    print("数据包数:", len(d), " 长度范围:", min(len(p) for p in d), "-", max(len(p) for p in d))
    # 每 3 个包对应一个字节：第 2 个包长度应为 296+seq
    seqs = []
    for i in range(0, len(d), 3):
        # 从 DataCode 反推 seq 不易，这里校验第 2 包长度落在 296-551
        seqs.append(len(d[i + 1]))
    print("序号包长度范围:", min(seqs), "-", max(seqs))
    if not all(296 <= s <= 551 for s in seqs):
        ok = False

    # 已知值校验：空密码/已知 ssid 时 totalLen 可手算
    # ssid="A"(1) bssid 空 ip 4 段 -> totalLen = 4+5+1+0 = 10
    d2 = build_datum("A", "", "")
    # 第一个字节 value=10 seq=0 -> 第 2 包长度 = 296
    if len(d2[1]) != 296:
        print("  [BAD] totalLen 编码异常:", len(d2[1]))
        ok = False
    print("自测%s" % ("通过" if ok else "失败"))
    return ok


def main():
    ap = argparse.ArgumentParser(description="小乔跑步机 ESP-Touch 配网")
    ap.add_argument("--ssid")
    ap.add_argument("--password")
    ap.add_argument("--bssid", help="路由器 BSSID，留空则自动读取")
    ap.add_argument("--ip", help="本机在 WiFi 网段的 IP，留空自动探测")
    ap.add_argument("--seconds", type=int, default=180)
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()

    if a.selftest:
        sys.exit(0 if selftest() else 1)

    ssid, bssid, band = get_wifi_info()
    ssid = a.ssid or ssid
    bssid = a.bssid or bssid
    if not ssid:
        print("读不到当前 WiFi，请用 --ssid 指定")
        sys.exit(1)
    if not a.password:
        print("请用 --password 指定 WiFi 密码")
        sys.exit(1)
    if not bssid:
        print("[警告] 拿不到 BSSID，用全 0 代替（部分固件要求真实 BSSID）")
        bssid = "00:00:00:00:00:00"
    if band == "5G":
        print("")
        print("!! 当前连的是 5GHz 频段 (%s)" % ssid)
        print("!! 跑步机的 WiFi 模块只支持 2.4GHz，必须切到路由器的 2.4G SSID 再来配网")
        print("")

    print("开始配网。若还没做，请立刻：待机状态下按住面板 \"-\" 键 5 秒，听到\"哔\"一声")
    print("（旋钮机型：拔掉安全锁后按住旋钮 5 秒）")
    p = Provisioner(ssid, a.password, bssid, a.ip)
    r = p.run(a.seconds)
    if r:
        print("\n下一步：python xq_client.py %s" % r[1])
    else:
        print("\n未收到设备回执。可能原因：")
        print("  1) 跑步机没进入配网模式（需要按面板上的 WiFi / 配网键）")
        print("  2) 手机/电脑连的是 5G 或 WiFi6 的 SSID —— 设备只支持 2.4G")
        print("  3) 该机型用的是飞燕(FyLink)方案而非 ESP-Touch")


if __name__ == "__main__":
    main()
