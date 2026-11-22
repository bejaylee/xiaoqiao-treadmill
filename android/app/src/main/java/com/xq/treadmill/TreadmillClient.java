package com.xq.treadmill;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 小乔 SmartRun 跑步机 局域网控制客户端。
 *
 * 协议（逆向自 com.qiaosports.xqiao v2.0.3）：
 *   发现：UDP 19032，跑步机周期广播 30 字节，Magic "XJXQ"
 *   控制：TCP 12304，帧 = [type][len][payload...][chk_hi][chk_lo] 0x0D 0x0A
 *   校验：sum(索引 2 起所有字节)，大端 2 字节
 *   心跳：每 5 s 发 04 01 00 00 00 0D 0A
 */
public class TreadmillClient {

    public static final int UDP_PORT = 19032;
    public static final int TCP_PORT = 12304;

    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
        void onStatus(Status s);
        /** 收到非状态帧，调试用 */
        void onFrame(byte[] payload);
    }

    // ------------------------------------------------------------ 帧构造
    /** 与 SocketUtil.intArrayToByteArray 完全一致 */
    public static byte[] frame(int[] body) {
        int sum = 0;
        for (int i = 2; i < body.length; i++) {
            sum += body[i] & 0xFF;
        }
        byte[] out = new byte[body.length + 4];
        for (int i = 0; i < body.length; i++) {
            out[i] = (byte) body[i];
        }
        out[body.length] = (byte) ((sum >> 8) & 0xFF);
        out[body.length + 1] = (byte) (sum & 0xFF);
        out[body.length + 2] = 0x0D;
        out[body.length + 3] = 0x0A;
        return out;
    }

    public static byte[] cmdQuery() {
        return frame(new int[]{0x11, 0x04, 0x00, 0x00, 0x00, 0x00});
    }

    public static byte[] cmdPowerOn() {
        return frame(new int[]{0x11, 0x04, 0x01, 0x02, 0x00, 0x00});
    }

    public static byte[] cmdPowerOff() {
        return frame(new int[]{0x11, 0x04, 0x01, 0x01, 0x00, 0x00});
    }

    /** @param kmh 目标速度，0.1 km/h 精度 */
    public static byte[] cmdSpeed(double kmh) {
        int v = (int) Math.round(kmh * 10.0);
        if (v < 0) v = 0;
        if (v > 255) v = 255;
        return frame(new int[]{0x11, 0x04, 0x02, v, 0x00, 0x00});
    }

    /** 本次运动限时（跑步机到点自动结束） */
    public static byte[] cmdTime(int minutes) {
        int sec = Math.max(0, Math.min(0xFFFF, minutes * 60));
        return frame(new int[]{0x15, 0x05, 0x01, sec & 0xFF, (sec >> 8) & 0xFF, 0x00, 0x00});
    }

    /** 上报用户与模式；uid=0 表示无账号，跑步机不会因此拒绝控制 */
    public static byte[] cmdUid(int uid, int mode) {
        return frame(new int[]{0x13, 0x05, mode,
                (uid >> 24) & 0xFF, (uid >> 16) & 0xFF, (uid >> 8) & 0xFF, uid & 0xFF});
    }

    public static byte[] cmdHeartbeat() {
        return frame(new int[]{0x04, 0x01, 0x00});
    }

    /**
     * 课程/计划跑（0x12 变长帧）。
     *
     * segs[i] = {速度×10, 起始秒, 结束秒, infoId}，起止是**累计秒**（原 App 存分钟，发送前 ×60）。
     * index 从第几段开始，lastSecond 是该段内已跑秒数（续跑用）。
     */
    public static byte[] cmdPlan(int[][] segs, int index, int modeId, int planId, int uid, int lastSecond) {
        int n = segs.length;
        int[] body = new int[2 + 12 + n * 8];
        int p = 0;
        body[p++] = 0x12;
        body[p++] = (n * 8 + 12) & 0xFF;
        body[p++] = index & 0xFF;
        body[p++] = modeId & 0xFF;
        body[p++] = (planId >> 8) & 0xFF;
        body[p++] = planId & 0xFF;
        body[p++] = (uid >> 24) & 0xFF;
        body[p++] = (uid >> 16) & 0xFF;
        body[p++] = (uid >> 8) & 0xFF;
        body[p++] = uid & 0xFF;
        body[p++] = lastSecond & 0xFF;
        body[p++] = (lastSecond >> 8) & 0xFF;
        body[p++] = (lastSecond >> 16) & 0xFF;
        body[p++] = (lastSecond >> 24) & 0xFF;
        for (int i = 0; i < n; i++) {
            int speed10 = segs[i][0];
            int startSec = segs[i][1];
            int endSec = segs[i][2];
            int infoId = segs[i][3];
            body[p++] = i & 0xFF;
            body[p++] = speed10 & 0xFF;
            body[p++] = startSec & 0xFF;
            body[p++] = (startSec >> 8) & 0xFF;
            body[p++] = endSec & 0xFF;
            body[p++] = (endSec >> 8) & 0xFF;
            body[p++] = infoId & 0xFF;
            body[p++] = (infoId >> 8) & 0xFF;
        }
        return frame(body);
    }

    // ------------------------------------------------------------ 状态
    public static class Status {
        public int mileage;
        public int calorie;
        public int duration;      // 秒
        public int heartRate;
        public int speed10;       // 0.1 km/h
        public int error;
        public int power;         // 低 4 位
        public int mode;
        public int modeIndex;
        public int step;
        public int personOn;
        public int finish;
        public int percentage;

        public double kmh() {
            return speed10 / 10.0;
        }

        public static Status parse(byte[] b) {
            if (b == null || b.length != 34 || (b[0] & 0xFF) != 0x01) {
                return null;
            }
            Status s = new Status();
            s.mileage = le32(b, 2);
            s.calorie = le16(b, 14);
            s.duration = le16(b, 16);
            s.heartRate = b[18] & 0xFF;
            s.speed10 = b[20] & 0xFF;
            int bp = b[21] & 0xFF;
            s.error = (bp >> 4) & 0x0F;
            s.power = bp & 0x0F;
            s.mode = b[22] & 0xFF;
            s.modeIndex = b[23] & 0xFF;
            s.step = le16(b, 24);
            s.personOn = b[26] & 0xFF;
            s.finish = b[27] & 0xFF;
            s.percentage = b[28] & 0xFF;
            return s;
        }

        private static int le16(byte[] b, int i) {
            return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8);
        }

        private static int le32(byte[] b, int i) {
            return (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8)
                    | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
        }

        public String powerName() {
            switch (power) {
                case 0:
                    return "待机";
                case 1:
                    return "已开机";
                case 2:
                    return "运行中";
                case 3:
                    return "停止中";
                default:
                    return "状态" + power;
            }
        }

        public String toText() {
            return String.format(java.util.Locale.US,
                    "速度 %.1f km/h\n时长 %02d:%02d\n卡路里 %d  步数 %d\n心率 %d\n"
                            + "%s%s\n模式 %d#%d  完成 %d%%\n里程计数 %d",
                    kmh(), duration / 60, duration % 60, calorie, step, heartRate,
                    powerName(), error != 0 ? "  故障码 " + error : "",
                    mode, modeIndex, percentage, mileage);
        }
    }

    // ------------------------------------------------------------ UDP 发现
    public static class Device {
        public String ip;
        public int factory;
        public int model;
        public String mac;
        public int wifiVersion;
        public int wifiState;
        public int updateState;
        public int wifiType;

        @Override
        public String toString() {
            return mac + "  " + ip + "  model=" + model + "  wifiVer=" + wifiVersion;
        }
    }

    /** 解析一条 UDP 广播；不是本协议返回 null */
    public static Device parseDiscovery(DatagramPacket p) {
        byte[] d = p.getData();
        int len = p.getLength();
        if (len < 19) return null;
        if ((d[0] & 0xFF) != 'X' || (d[1] & 0xFF) != 'J'
                || (d[2] & 0xFF) != 'X' || (d[3] & 0xFF) != 'Q') {
            return null;
        }
        Device dev = new Device();
        dev.ip = p.getAddress().getHostAddress();
        dev.factory = ((d[4] & 0xFF) << 8) | (d[5] & 0xFF);
        dev.model = ((d[6] & 0xFF) << 8) | (d[7] & 0xFF);
        StringBuilder sb = new StringBuilder();
        for (int i = 8; i < 14; i++) {
            sb.append(String.format(java.util.Locale.US, "%02x", d[i] & 0xFF));
            if (i != 13) sb.append(':');
        }
        dev.mac = sb.toString();
        dev.wifiVersion = ((d[14] & 0xFF) << 8) | (d[15] & 0xFF);
        dev.wifiState = d[16] & 0xFF;
        dev.updateState = d[17] & 0xFF;
        dev.wifiType = d[18] & 0xFF;
        return dev;
    }

    // ------------------------------------------------------------ TCP
    private volatile Socket socket;
    /** 每次连接递增：旧收包线程据此判断自己是否已被替换 */
    private volatile int generation;
    private OutputStream out;
    private InputStream in;
    private volatile boolean alive;
    private volatile boolean connecting;
    private String ip = "";
    private final Object writeLock = new Object();
    /** 最近一帧状态，后台逻辑（计划跑）直接读 */
    public volatile Status lastStatus;
    private final Listener listener;
    private ScheduledExecutorService heartbeat;
    private Thread rxThread;

    public TreadmillClient(Listener l) {
        this.listener = l;
    }

    public boolean isConnected() {
        Socket s = socket;
        return alive && s != null && s.isConnected() && !s.isClosed();
    }

    public String ip() {
        return ip;
    }

    /** 连接（可重复调用：换 IP 会先关闭旧连接） */
    public void connect(final String host) {
        synchronized (this) {
            if (connecting) return;
            if (isConnected() && host.equals(ip)) return;
            cleanup();                    // 先清掉旧 socket / 心跳
            connecting = true;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = new Socket();
                    s.connect(new InetSocketAddress(host, TCP_PORT), 5000);
                    s.setTcpNoDelay(true);
                    s.setSoTimeout(0);
                    final int[] gen = new int[1];
                    synchronized (TreadmillClient.this) {
                        socket = s;
                        out = s.getOutputStream();
                        in = s.getInputStream();
                        ip = host;
                        alive = true;
                        connecting = false;
                        gen[0] = ++generation;      // 本次连接的代际
                    }
                    rxThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            readLoop(s, gen[0]);
                        }
                    }, "xq-rx");
                    rxThread.start();

                    startHeartbeat();
                    listener.onConnected();
                    // 握手：上报 uid=0 / mode=0，然后查询状态
                    send(cmdUid(0, 0));
                    send(cmdQuery());
                } catch (Exception e) {
                    synchronized (TreadmillClient.this) {
                        connecting = false;
                    }
                    cleanup();
                    listener.onDisconnected("连接失败: " + e.getMessage());
                }
            }
        }, "xq-connect").start();
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeat = Executors.newSingleThreadScheduledExecutor();
        heartbeat.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    send(cmdHeartbeat());
                } catch (Exception ignored) {
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        if (heartbeat != null) {
            heartbeat.shutdownNow();
            heartbeat = null;
        }
    }

    private void cleanup() {
        generation++;                 // 让所有旧收包线程失效
        alive = false;
        stopHeartbeat();
        Socket s = socket;
        socket = null;
        out = null;
        in = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * 收包状态机：按 [type][len] 取定长，校验和 + CRLF 双验证。
     * 帧体里出现 0D 0A 也不会切错（旧实现按尾部扫描，会切在数据中间）。
     */
    private void readLoop(final Socket mySocket, final int gen) {
        String reason = "连接已断开";
        try {
            InputStream is = mySocket.getInputStream();
            while (alive && gen == generation) {
                int type = is.read();
                if (type < 0) break;
                int len = is.read();
                if (len < 0) break;
                if (len > 512) {                    // 明显不是帧头，向后同步
                    resync(is, gen);
                    continue;
                }
                byte[] tail = readFully(is, len + 4);   // data + chk2 + CRLF2
                if (tail == null) break;
                if ((tail[len + 2] & 0xFF) != 0x0D || (tail[len + 3] & 0xFF) != 0x0A) {
                    resync(is, gen);
                    continue;
                }
                int sum = 0;
                for (int i = 0; i < len; i++) sum += tail[i] & 0xFF;
                int chk = ((tail[len] & 0xFF) << 8) | (tail[len + 1] & 0xFF);
                if (sum != chk) continue;           // 校验失败：丢弃这一帧
                byte[] frame = new byte[2 + len + 2];   // 保留 type/len/chk，去掉 CRLF
                frame[0] = (byte) type;
                frame[1] = (byte) len;
                System.arraycopy(tail, 0, frame, 2, len + 2);
                dispatch(frame);
            }
        } catch (IOException e) {
            reason = "读线程异常: " + e.getMessage();
        } finally {
            if (gen == generation) {
                // 只有当前连接才清理全局资源并通知上层
                cleanup();
                listener.onDisconnected(reason);
            } else {
                // 已被新连接替换：只关自己的 socket，绝不动新连接
                try { mySocket.close(); } catch (IOException ignored) { }
            }
        }
    }

    private byte[] readFully(InputStream is, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = is.read(buf, off, n - off);
            if (r < 0) return null;
            off += r;
        }
        return buf;
    }

    /** 丢掉垃圾字节，直到遇到 CRLF 为止 */
    private void resync(InputStream is, int gen) throws IOException {
        int prev = -1;
        while (alive && gen == generation) {
            int b = is.read();
            if (b < 0) return;
            if (prev == 0x0D && b == 0x0A) return;
            prev = b;
        }
    }

    private void dispatch(byte[] p) {
        if (p.length == 0) return;
        if ((p[0] & 0xFF) == 0x01) {
            Status s = Status.parse(p);
            if (s != null) {
                lastStatus = s;
                listener.onStatus(s);
                return;
            }
        }
        listener.onFrame(p);
    }

    /** 多线程写同一 socket 必须串行，否则两帧字节会交错 */
    public void send(byte[] data) throws IOException {
        OutputStream o = out;
        if (o == null) throw new IOException("未连接");
        synchronized (writeLock) {
            o.write(data);
            o.flush();
        }
    }

    /** 安全停机：先减速到 0，1.5 s 后再关机，避免跑带骤停 */
    public void safeStop() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    send(cmdSpeed(0));
                    Thread.sleep(1500);
                    send(cmdPowerOff());
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    public void disconnect() {
        boolean was = isConnected();
        cleanup();
        // cleanup 会让收包线程静默退出，这里补一次回调，保证上层状态同步
        if (was) listener.onDisconnected("手动断开");
    }
}
