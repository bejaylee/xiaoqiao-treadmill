#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
小乔 SmartRun 跑步机 协议验证工具（PC 端，先在电脑上跑通再写 APK）

用法:
    python xq_client.py scan                       # 只听 UDP 19032，发现跑步机
    python xq_client.py 192.168.4.1                # 连接并进入交互式控制台
    python xq_client.py 192.168.4.1 -c "speed 6"   # 单条命令
    python xq_client.py --selftest                 # 离线自测帧构造

交互式命令:
    status            查询一次状态
    stream            持续打印状态帧 (Ctrl-C 退出)
    on / off          开机 / 关机(切断跑带动力)
    speed 8.5         设定速度 km/h (0.1 精度)
    time 30           设定限时 30 分钟
    raw 11 04 00 00 00 00 00 00   发送自定义帧(自动补校验+CRLF)

依赖: 仅标准库
"""

import socket
import struct
import sys
import time
import threading

UDP_PORT = 19032
TCP_PORT = 12304
MAGIC = b"XJXQ"


# ---------------------------------------------------------------- 帧构造
def frame(body):
    """body = [type, length, payload...]  ->  加 2 字节大端校验 + CRLF"""
    s = sum(b & 0xFF for b in body[2:]) & 0xFFFF
    return bytes(b & 0xFF for b in body) + bytes([s >> 8, s & 0xFF, 0x0D, 0x0A])


def cmd_query():
    return frame([0x11, 0x04, 0x00, 0x00, 0x00, 0x00])


def cmd_power_on():
    return frame([0x11, 0x04, 0x01, 0x02, 0x00, 0x00])


def cmd_power_off():
    return frame([0x11, 0x04, 0x01, 0x01, 0x00, 0x00])


def cmd_speed(kmh):
    v = max(0, min(255, int(round(float(kmh) * 10))))
    return frame([0x11, 0x04, 0x02, v, 0x00, 0x00])


def cmd_time(minutes):
    sec = int(minutes * 60) & 0xFFFF
    return frame([0x15, 0x05, 0x01, sec & 0xFF, (sec >> 8) & 0xFF, 0x00, 0x00])


def cmd_uid(uid=0, mode=0):
    return frame([0x13, 0x05, mode,
                  (uid >> 24) & 0xFF, (uid >> 16) & 0xFF, (uid >> 8) & 0xFF, uid & 0xFF])


def cmd_plan(segments, index=0, mode_id=33, plan_id=1, uid=0, last_second=0, info_ids=None):
    """
    课程/计划跑：类型 0x12，变长帧。

    segments: [(speed_kmh, start_sec, end_sec), ...]
              ★ 线上单位是「秒」，且 start/end 是**累计边界**。
                原 App 内部按分钟存，Encode12 里 getStartMin()*60 后才上链路，
                所以设备收到的就是秒。用 segments_from_minutes() 可直接换算。

    index       : 从第几段开始（0-based，对应原 App 的 lastRunInfoIndex）
    last_second : 该段内已经跑过的秒数（原 App 的 mPauseSecond，续跑用）
    info_ids    : 每段的 infoId（原 App 用数据库行 id；不传则用 1..N）

    帧结构（Encode12 反编译还原）：
        [0x12][8N+12][index][modeId][planId BE2][uid BE4][lastSecond LE4]
        [每段 8 字节: segIndex, speed*10, startSec LE2, endSec LE2, infoId LE2]
    末尾统一补校验(2B BE) + 0D 0A。
    """
    n = len(segments)
    ids = list(info_ids) if info_ids else [i + 1 for i in range(n)]
    body = [0x12, (n * 8 + 12) & 0xFF, index & 0xFF, mode_id & 0xFF]
    body += [(plan_id >> 8) & 0xFF, plan_id & 0xFF]
    body += [(uid >> 24) & 0xFF, (uid >> 16) & 0xFF, (uid >> 8) & 0xFF, uid & 0xFF]
    body += [last_second & 0xFF, (last_second >> 8) & 0xFF,
             (last_second >> 16) & 0xFF, (last_second >> 24) & 0xFF]
    for i, (spd, s_sec, e_sec) in enumerate(segments):
        body += [i & 0xFF, int(round(spd * 10)) & 0xFF,
                 s_sec & 0xFF, (s_sec >> 8) & 0xFF,
                 e_sec & 0xFF, (e_sec >> 8) & 0xFF,
                 ids[i] & 0xFF, (ids[i] >> 8) & 0xFF]
    return frame(body)


def segments_from_minutes(items):
    """[(速度 km/h, 分钟), ...] -> [(速度, 起始秒, 结束秒)]，累计边界。

    对应原 App 的 DbRunInfo(speed, time_consume) 列表：
    getPlanModel 里 end = 上一段 end + 本段时长，Encode12 再 *60 变成秒。
    """
    out = []
    acc = 0
    for spd, minutes in items:
        start = acc
        acc += int(round(minutes * 60))
        out.append((float(spd), start, acc))
    return out


def cmd_heartbeat():
    return frame([0x04, 0x01, 0x00])


# ---------------------------------------------------------------- 状态解析
def le16(b, i):
    return b[i] | (b[i + 1] << 8)


def le32(b, i):
    return b[i] | (b[i + 1] << 8) | (b[i + 2] << 16) | (b[i + 3] << 24)


class Status(object):
    __slots__ = ("mileage", "time_start", "time_end", "calorie", "duration",
                 "heart_rate", "rest_time", "speed", "error", "power", "mode",
                 "mode_index", "step", "person_on", "finish", "percentage")

    @classmethod
    def parse(cls, b):
        if len(b) != 34 or b[0] != 0x01:
            return None
        s = cls()
        s.mileage = le32(b, 2)
        s.time_start = le32(b, 6)
        s.time_end = le32(b, 10)
        s.calorie = le16(b, 14)
        s.duration = le16(b, 16)
        s.heart_rate = b[18]
        s.rest_time = b[19]
        s.speed = b[20]
        s.error = (b[21] >> 4) & 0x0F
        s.power = b[21] & 0x0F
        s.mode = b[22]
        s.mode_index = b[23]
        s.step = le16(b, 24)
        s.person_on = b[26]
        s.finish = b[27]
        s.percentage = b[28]
        return s

    def __str__(self):
        m, s = divmod(self.duration, 60)
        return ("速度 %.1f km/h | 时长 %02d:%02d | 里程(raw) %d | 卡 %d | 步 %d | 心率 %d | "
                "电源 %d 故障 %d | 模式 %d#%d | 人 %d 完成 %d %d%%" % (
                    self.speed / 10.0, m, s, self.mileage, self.calorie, self.step,
                    self.heart_rate, self.power, self.error, self.mode, self.mode_index,
                    self.person_on, self.finish, self.percentage))


# ---------------------------------------------------------------- UDP 发现
def udp_scan(seconds=20):
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        sock.bind(("", UDP_PORT))
    except OSError as e:
        print("绑定 UDP %d 失败: %s" % (UDP_PORT, e))
        print("Windows 上请允许 python 通过防火墙的专用网络；或关掉原 App 占用。")
        return []
    sock.settimeout(1.0)
    print("监听 UDP %d，等待跑步机广播（%d 秒）..." % (UDP_PORT, seconds))
    found = {}
    t0 = time.time()
    while time.time() - t0 < seconds:
        try:
            data, addr = sock.recvfrom(2048)
        except socket.timeout:
            continue
        if len(data) < 19 or data[:4] != MAGIC:
            print("  ? 非本协议包 from %s: %s" % (addr[0], data[:16].hex(" ")))
            continue
        info = {
            "ip": addr[0],
            "factory": struct.unpack(">H", data[4:6])[0],
            "model": struct.unpack(">H", data[6:8])[0],
            "mac": ":".join("%02x" % b for b in data[8:14]),
            "wifi_ver": struct.unpack(">H", data[14:16])[0],
            "wifi_state": data[16],
            "update_state": data[17],
            "wifi_type": data[18],
        }
        key = info["mac"]
        if key not in found:
            found[key] = info
            print("\n[发现设备]")
            for k, v in info.items():
                print("   %-12s %s" % (k, v))
            print("   原始: %s" % data.hex(" "))
        else:
            found[key]["ip"] = info["ip"]
    sock.close()
    return list(found.values())


# ---------------------------------------------------------------- TCP 客户端
class Client(object):
    def __init__(self, ip, port=TCP_PORT, verbose=True):
        self.ip = ip
        self.port = port
        self.sock = None
        self.buf = bytearray()
        self.status_cb = None
        self.verbose = verbose
        self._alive = False
        self.last_status = None

    def connect(self, timeout=5.0):
        self.sock = socket.create_connection((self.ip, self.port), timeout=timeout)
        self.sock.settimeout(None)
        self._alive = True
        threading.Thread(target=self._rx, daemon=True).start()
        threading.Thread(target=self._heartbeat, daemon=True).start()
        print("已连接 %s:%d" % (self.ip, self.port))
        time.sleep(0.2)
        self.send(cmd_uid(0, 0))
        self.send(cmd_query())

    def _heartbeat(self):
        while self._alive:
            time.sleep(5.0)
            if not self._alive:
                break
            try:
                self.send(cmd_heartbeat())
            except Exception:
                break

    def _rx(self):
        while self._alive:
            try:
                chunk = self.sock.recv(256)
            except Exception:
                break
            if not chunk:
                break
            self.buf.extend(chunk)
            while True:
                idx = self.buf.find(b"\r\n")
                if idx < 0:
                    break
                payload = bytes(self.buf[:idx])
                del self.buf[:idx + 2]
                self._on_frame(payload)

    def _on_frame(self, p):
        if not p:
            return
        t = p[0]
        if t == 0x01:
            st = Status.parse(p)
            if st:
                self.last_status = st
                if self.verbose:
                    print("\r[状态] " + str(st) + " " * 6)
        elif self.verbose:
            print("\r[0x%02X] %s" % (t, p.hex(" ")))

    def send(self, data):
        self.sock.sendall(data)

    def close(self):
        self._alive = False
        try:
            self.sock.close()
        except Exception:
            pass


# ---------------------------------------------------------------- 自测
def selftest():
    cases = [
        ("查询", cmd_query(), "11 04 00 00 00 00 00 00 0d 0a"),
        ("开机", cmd_power_on(), "11 04 01 02 00 00 00 03 0d 0a"),
        ("关机", cmd_power_off(), "11 04 01 01 00 00 00 02 0d 0a"),
        ("速度8.5", cmd_speed(8.5), "11 04 02 55 00 00 00 57 0d 0a"),
        ("速度0", cmd_speed(0), "11 04 02 00 00 00 00 02 0d 0a"),
        ("心跳", cmd_heartbeat(), "04 01 00 00 00 0d 0a"),
        ("限时30分", cmd_time(30), "15 05 01 08 07 00 00 00 10 0d 0a"),
        ("uid0", cmd_uid(0, 0), "13 05 00 00 00 00 00 00 00 0d 0a"),
    ]
    ok = True
    for name, got, want in cases:
        h = got.hex(" ")
        flag = "OK " if h == want else "BAD"
        if h != want:
            ok = False
        print("[%s] %-8s %s" % (flag, name, h))
    print("\n自测%s" % ("全部通过" if ok else "存在不一致"))


# ---------------------------------------------------------------- main
def main():
    args = [a for a in sys.argv[1:]]
    if not args or args[0] in ("-h", "--help"):
        print(__doc__)
        return
    if args[0] == "--selftest":
        selftest()
        return
    if args[0] == "scan":
        secs = int(args[1]) if len(args) > 1 else 20
        udp_scan(secs)
        return

    ip = args[0]
    c = Client(ip)
    try:
        c.connect()
    except Exception as e:
        print("连接失败: %s" % e)
        print("检查: 手机/电脑与跑步机在同一 WiFi；跑步机已开机且未连接原 App。")
        return

    if "-c" in args:
        line = args[args.index("-c") + 1]
        run_command(c, line)
        time.sleep(1.5)
        c.close()
        return

    print(__doc__)
    try:
        while True:
            line = input("xq> ").strip()
            if not line:
                continue
            if line in ("exit", "quit", "q"):
                break
            run_command(c, line)
    except (KeyboardInterrupt, EOFError):
        pass
    finally:
        c.close()


def run_command(c, line):
    parts = line.split()
    cmd = parts[0].lower()
    try:
        if cmd == "status":
            c.send(cmd_query())
            time.sleep(0.5)
            if c.last_status:
                print(c.last_status)
        elif cmd == "stream":
            print("持续接收中，Ctrl-C 退出")
            while True:
                time.sleep(0.5)
        elif cmd == "on":
            c.send(cmd_power_on())
            print("已发送 开机")
        elif cmd == "off":
            c.send(cmd_power_off())
            print("已发送 关机")
        elif cmd == "speed":
            c.send(cmd_speed(parts[1]))
            print("已发送 速度 %s km/h" % parts[1])
        elif cmd == "time":
            c.send(cmd_time(float(parts[1])))
            print("已发送 限时 %s 分钟" % parts[1])
        elif cmd == "raw":
            body = [int(x, 16) for x in parts[1:]]
            f = frame(body)
            c.send(f)
            print("已发送 %s" % f.hex(" "))
        else:
            print("未知命令: %s" % cmd)
    except IndexError:
        print("参数不足")


if __name__ == "__main__":
    main()
