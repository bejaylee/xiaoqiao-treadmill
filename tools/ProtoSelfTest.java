package com.xq.treadmill;

/** 协议层纯 JVM 回归测试：不需要 Android 设备或模拟器 */
public class ProtoSelfTest {

    private static int passed, failed;

    private static void check(boolean ok, String name) {
        if (ok) {
            passed++;
            System.out.println("  PASS  " + name);
        } else {
            failed++;
            System.out.println("  FAIL  " + name);
        }
    }

    /** 按 [type][len][payload][chk][CRLF] 解析一整帧，模拟 readLoop 的校验逻辑 */
    private static byte[] parseOne(byte[] f) {
        int len = f[1] & 0xFF;
        if (f.length != 2 + len + 4) return null;
        int sum = 0;
        for (int i = 0; i < len; i++) sum += f[2 + i] & 0xFF;
        int chk = ((f[2 + len] & 0xFF) << 8) | (f[3 + len] & 0xFF);
        if (sum != chk) return null;
        if ((f[4 + len] & 0xFF) != 0x0D || (f[5 + len] & 0xFF) != 0x0A) return null;
        byte[] out = new byte[2 + len + 2];
        System.arraycopy(f, 0, out, 0, out.length);
        return out;
    }

    public static void main(String[] args) {
        System.out.println("协议回归测试");

        // 1. 固定帧与校验和
        byte[] speed = TreadmillClient.cmdSpeed(4.5);
        check(speed.length == 10, "cmdSpeed 帧长 10");
        check((speed[speed.length - 2] & 0xFF) == 0x0D
                && (speed[speed.length - 1] & 0xFF) == 0x0A, "帧尾 CRLF");
        check((speed[2] & 0xFF) == 2 && (speed[3] & 0xFF) == 45, "4.5 km/h → 45");
        check(parseOne(speed) != null, "cmdSpeed 校验和通过");

        // 2. 载荷里带 0D 0A：不能被提前切帧
        byte[] tricky = TreadmillClient.frame(new int[]{0x11, 0x04, 0x0D, 0x0A, 0x00, 0x00});
        check(tricky.length == 10, "载荷含 0D0A 时帧长仍为 10");
        byte[] parsed = parseOne(tricky);
        check(parsed != null && parsed.length == 8, "按 len 解析，不被 0D0A 切断");
        check(parsed != null && (parsed[2] & 0xFF) == 0x0D && (parsed[3] & 0xFF) == 0x0A,
                "载荷 0D0A 原样保留");

        // 3. 校验和损坏应丢弃
        byte[] broken = speed.clone();
        broken[2] = (byte) (broken[2] ^ 0xFF);
        check(parseOne(broken) == null, "校验和不符时丢弃");

        // 4. 状态帧（34 字节，不含 CRLF）
        byte[] st = new byte[34];
        st[0] = 0x01;
        st[1] = 30;
        st[2] = 100; st[3] = 0; st[4] = 0; st[5] = 0;      // mileage=100
        st[14] = 25; st[15] = 0;                             // calorie=25
        st[16] = 90; st[17] = 0;                             // duration=90
        st[18] = 88;                                         // heartRate
        st[20] = 13;                                         // speed 1.3
        st[21] = 0x0A;                                       // 载荷里正好是 0D 0A！
        st[22] = 33; st[23] = (byte) 255;
        st[24] = 42; st[25] = 0;
        st[26] = 1; st[27] = 0; st[28] = 42;
        int sum = 0;
        for (int i = 2; i < 32; i++) sum += st[i] & 0xFF;
        st[32] = (byte) ((sum >> 8) & 0xFF);
        st[33] = (byte) (sum & 0xFF);
        byte[] full = new byte[36];
        System.arraycopy(st, 0, full, 0, 34);
        full[34] = 0x0D;
        full[35] = 0x0A;
        byte[] stParsed = parseOne(full);
        check(stParsed != null, "状态帧整体校验通过（载荷含 0D0A）");
        TreadmillClient.Status s = TreadmillClient.Status.parse(stParsed);
        check(s != null, "Status.parse 成功");
        if (s != null) {
            check(s.mileage == 100 && s.calorie == 25 && s.duration == 90, "里程/热量/时长解析");
            check(s.speed10 == 13 && s.power == 10, "速度 1.3 与 power 位解析");
            check(s.mode == 33 && s.modeIndex == 255 && s.percentage == 42, "模式/进度解析");
            check(Math.abs(s.kmh() - 1.3) < 0.001, "kmh() 精度");
        }

        // 5. 计划帧长度
        int[][] segs = {{30, 0, 180, 1}, {50, 180, 300, 2}, {40, 300, 480, 3}};
        byte[] plan = TreadmillClient.cmdPlan(segs, 1, 33, 7, 0, 0);
        check((plan[1] & 0xFF) == 3 * 8 + 12, "0x12 len = 8N+12");
        check(plan.length == 2 + (3 * 8 + 12) + 4, "0x12 总长 = 2+len+4");
        check(parseOne(plan) != null, "0x12 校验和通过");

        // 6. 限时夹取
        byte[] t = TreadmillClient.cmdTime(100000);
        int sec = (t[3] & 0xFF) | ((t[4] & 0xFF) << 8);
        check(sec == 0xFFFF, "cmdTime 超大值夹到 0xFFFF");

        System.out.println("\n结果: " + passed + " 通过, " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }
}
