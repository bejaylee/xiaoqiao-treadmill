package com.xq.treadmill;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * ESP-Touch v2（乐鑫 SmartConfig）配网发射器。
 *
 * 完全是 APK 里 com.espressif.iot.esptouch 的等价实现：
 *   端口 7001 广播，引导码长度 515/514/513/512，数据每个字节 → 3 个包，
 *   包长度 = 40 + 高字节…（信息只存在于 UDP 包长度里，载荷恒为 '1'）。
 */
public class EsptouchProvisioner {

    private static final int TARGET_PORT = 7001;
    private static final int LISTEN_PORT = 18266;
    private static final int GUIDE_INTERVAL_MS = 8;
    private static final int DATA_INTERVAL_MS = 8;
    private static final int GUIDE_PHASE_MS = 2000;
    private static final int DATA_PHASE_MS = 4000;

    public interface Callback {
        void onProgress(int round, long elapsedMs);

        void onDeviceFound(String mac, String ip);

        void onFinish(boolean ok, String reason);
    }

    // ---------------------------------------------------------- CRC8
    private static final int[] CRC_TAB = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i;
            for (int j = 0; j < 8; j++) {
                if ((c & 1) != 0) {
                    c = (c >> 1) ^ 0x8C;
                } else {
                    c >>= 1;
                }
            }
            CRC_TAB[i] = c & 0xFF;
        }
    }

    private static int crc8(byte[] data) {
        int v = 0;
        for (byte b : data) {
            v = CRC_TAB[(v ^ (b & 0xFF)) & 0xFF];
        }
        return v;
    }

    // ---------------------------------------------------------- 编码
    /** 一个字节 + 序号 → 3 个 UDP 包（内容全是 '1'，长度承载信息） */
    static byte[][] encodeDataCode(int value, int seq) {
        value &= 0xFF;
        seq &= 0xFF;
        int c = crc8(new byte[]{(byte) value, (byte) seq});
        int dataHi = (value >> 4) & 0x0F, dataLo = value & 0x0F;
        int crcHi = (c >> 4) & 0x0F, crcLo = c & 0x0F;

        int b1 = (crcHi << 4) | dataHi;
        int b5 = (crcLo << 4) | dataLo;

        int len1 = 40 + ((0 << 8) | b1);
        int len2 = 40 + ((1 << 8) | seq);
        int len3 = 40 + ((0 << 8) | b5);

        return new byte[][]{fill(len1), fill(len2), fill(len3)};
    }

    private static byte[] fill(int len) {
        byte[] b = new byte[len];
        for (int i = 0; i < len; i++) {
            b[i] = 0x31;
        }
        return b;
    }

    static List<byte[]> guidePackets() {
        List<byte[]> l = new ArrayList<byte[]>(4);
        l.add(fill(515));
        l.add(fill(514));
        l.add(fill(513));
        l.add(fill(512));
        return l;
    }

    private static byte[] parseBssid(String s) {
        if (s == null || s.trim().isEmpty()) {
            return new byte[0];
        }
        String[] p = s.trim().split(":");
        byte[] out = new byte[p.length];
        for (int i = 0; i < p.length; i++) {
            try {
                out[i] = (byte) Integer.parseInt(p[i], 16);
            } catch (NumberFormatException e) {
                out[i] = 0;
            }
        }
        return out;
    }

    /**
     * 严格按官方 DatumCode 实现（EspressifApp/EsptouchForAndroid）：
     *
     *   totalLen = 5 + ipLen + pwdLen + ssidLen
     *   seq0 totalLen
     *   seq1 密码长度            ← 不是 SSID 长度
     *   seq2 crc8(ssid)          ← 不是 bssid
     *   seq3 crc8(bssid)
     *   seq4 xor（bssid 不参与）
     *   seq5.. ip
     *   接着 密码 → SSID
     *   最后 BSSID，且插入到列表的第 5、9、13… 位
     */
    static List<byte[]> buildDatum(String ssid, String password, String bssid, String ip) {
        byte[] ssidB = ssid.getBytes();
        byte[] pwdB = password.getBytes();
        byte[] bssidB = parseBssid(bssid);
        String[] ipPartsS = ip.split("\\.");
        int[] ipParts = new int[ipPartsS.length];
        for (int i = 0; i < ipPartsS.length; i++) {
            try {
                ipParts[i] = Integer.parseInt(ipPartsS[i].trim());
            } catch (NumberFormatException e) {
                ipParts[i] = 0;
            }
        }

        int ipLen = ipParts.length;
        int pwdLen = pwdB.length;
        int ssidLen = ssidB.length;
        int totalLen = 5 + ipLen + pwdLen + ssidLen;

        int cSsid = crc8(ssidB);
        int cBssid = crc8(bssidB);

        List<int[]> codes = new ArrayList<int[]>();
        codes.add(new int[]{totalLen, 0});
        codes.add(new int[]{pwdLen, 1});
        codes.add(new int[]{cSsid, 2});
        codes.add(new int[]{cBssid, 3});

        int x = totalLen ^ pwdLen ^ cSsid ^ cBssid;
        for (int p : ipParts) x ^= p;
        for (byte b : pwdB) x ^= (b & 0xFF);
        for (byte b : ssidB) x ^= (b & 0xFF);
        codes.add(new int[]{x & 0xFF, 4});

        for (int i = 0; i < ipLen; i++) {
            codes.add(new int[]{ipParts[i], 5 + i});
        }
        for (int i = 0; i < pwdLen; i++) {
            codes.add(new int[]{pwdB[i] & 0xFF, 5 + ipLen + i});
        }
        for (int i = 0; i < ssidLen; i++) {
            codes.add(new int[]{ssidB[i] & 0xFF, 5 + ipLen + pwdLen + i});
        }
        // BSSID 插到 5、9、13… 的位置（与官方一致，影响包流顺序）
        int ins = 5;
        for (int i = 0; i < bssidB.length; i++) {
            int pos = Math.min(ins, codes.size());
            codes.add(pos, new int[]{bssidB[i] & 0xFF, totalLen + i});
            ins += 4;
        }

        List<byte[]> packets = new ArrayList<byte[]>(codes.size() * 3);
        for (int[] c : codes) {
            byte[][] three = encodeDataCode(c[0], c[1]);
            packets.add(three[0]);
            packets.add(three[1]);
            packets.add(three[2]);
        }
        return packets;
    }

    // ---------------------------------------------------------- 发送
    private final Callback cb;
    private volatile boolean running;

    public EsptouchProvisioner(Callback cb) {
        this.cb = cb;
    }

    public void stop() {
        running = false;
    }

    public void start(final String ssid, final String password, final String bssid,
                      final String ip, final int totalSeconds) {
        if (running) return;                  // H5：重入保护
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                String reason = "超时：设备没有回执";
                boolean ok = false;
                DatagramSocket tx = null;
                DatagramSocket rx = null;
                try {
                    List<byte[]> guide = guidePackets();
                    List<byte[]> datum = buildDatum(ssid, password, bssid, ip);

                    rx = new DatagramSocket(null);
                    rx.setReuseAddress(true);   // H5：快速重进页面避免端口占用
                    rx.bind(new java.net.InetSocketAddress(LISTEN_PORT));
                    rx.setSoTimeout(800);
                    final DatagramSocket rxFinal = rx;

                    tx = new DatagramSocket();
                    tx.setBroadcast(true);
                    final DatagramSocket txFinal = tx;
                    final InetAddress target = InetAddress.getByName("255.255.255.255");

                    long t0 = System.currentTimeMillis();
                    long deadline = t0 + totalSeconds * 1000L;
                    int round = 0;

                    while (running && System.currentTimeMillis() < deadline) {
                        round++;
                        // 引导阶段
                        long gEnd = System.currentTimeMillis() + GUIDE_PHASE_MS;
                        while (running && System.currentTimeMillis() < gEnd) {
                            for (byte[] p : guide) {
                                txFinal.send(new DatagramPacket(p, p.length, target, TARGET_PORT));
                                Thread.sleep(GUIDE_INTERVAL_MS);
                                if (!running) break;
                            }
                        }
                        // 数据阶段：每次 3 包 = 1 个字节
                        long dEnd = System.currentTimeMillis() + DATA_PHASE_MS;
                        int idx = 0;
                        while (running && System.currentTimeMillis() < dEnd) {
                            for (int k = 0; k < 3; k++) {
                                byte[] p = datum.get((idx + k) % datum.size());
                                txFinal.send(new DatagramPacket(p, p.length, target, TARGET_PORT));
                                Thread.sleep(DATA_INTERVAL_MS);
                            }
                            idx = (idx + 3) % datum.size();
                            final int r = round;
                            final long el = System.currentTimeMillis() - t0;
                            cb.onProgress(r, el);
                            if (!running) break;
                        }
                        // 查回执（非阻塞式：超时即视为没有）
                        byte[] buf = new byte[64];
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        try {
                            rxFinal.receive(pkt);
                            if (pkt.getLength() >= 11) {
                                StringBuilder mac = new StringBuilder();
                                for (int i = 1; i < 7; i++) {
                                    mac.append(String.format(java.util.Locale.US, "%02x", buf[i] & 0xFF));
                                    if (i != 6) mac.append(':');
                                }
                                String devIp = (buf[7] & 0xFF) + "." + (buf[8] & 0xFF) + "."
                                        + (buf[9] & 0xFF) + "." + (buf[10] & 0xFF);
                                ok = true;
                                reason = "配网成功";
                                cb.onDeviceFound(mac.toString(), devIp);
                                break;
                            }
                        } catch (java.net.SocketTimeoutException ignored) {
                        }
                    }
                } catch (Exception e) {
                    reason = "异常: " + e.getMessage();
                } finally {
                    running = false;
                    if (tx != null) tx.close();
                    if (rx != null) rx.close();
                    cb.onFinish(ok, reason);
                }
            }
        }, "esptouch").start();
    }
}
