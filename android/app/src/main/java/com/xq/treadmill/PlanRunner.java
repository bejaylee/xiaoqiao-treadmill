package com.xq.treadmill;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 课程 / 计划跑 —— 按原 App 的逻辑复刻（设备端执行）。
 *
 * 原 App 的流程（反编译 RunActivity/getPlanModel/Encode12 得出）：
 *   1. 计划 = 多天，每天若干段，每段 = 速度(km/h) + 时长(分钟)
 *   2. 开始前把当天全部分段打成 0x12 帧下发：
 *        index      = 上次跑到第几段
 *        lastSecond = 该段内已跑秒数
 *        每段 start/end = 累计「秒」（App 内部存分钟，发送前 ×60）
 *   3. 紧接着发开机命令 0x11 01 02
 *   4. 之后设备自己按段变速、自己计时，App 只负责显示
 *   5. 中断时记录 index 与段内秒数，下次续跑
 *
 * 设备侧的两个安全行为（实机验证）：
 *   · 起步一定先到 1.0 km/h（= 原 App 的 minSpeed），随后由设定值接管
 *   · 跑带上没人时，计时与计划推进被冻结（duration 停在 ~5 秒）
 */
public class PlanRunner {

    public interface Listener {
        /**
         * @param segIndex   当前第几段（0-based）
         * @param segTotal   总段数
         * @param targetKmh  该段目标速度
         * @param countedSec 设备已计秒数（含续跑偏移）
         * @param totalSec   计划总秒数
         * @param percent    进度百分比
         * @param frozen     是否因"无人"被冻结
         */
        void onProgress(int segIndex, int segTotal, double targetKmh,
                        int countedSec, int totalSec, int percent, boolean frozen);

        void onFinish(String reason);
    }

    /** 计划里的一段：速度 + 时长（内部统一用秒，课程里有 90 秒的段） */
    public static class Seg {
        public final double speed;
        public final int seconds;

        private Seg(double speed, int seconds) {
            this.speed = speed;
            this.seconds = Math.max(1, seconds);
        }

        public static Seg ofMinutes(double speed, int minutes) {
            return new Seg(speed, Math.max(1, minutes) * 60);
        }

        public static Seg ofSeconds(double speed, int seconds) {
            return new Seg(speed, seconds);
        }

        public int minutes() {
            return Math.max(1, Math.round(seconds / 60f));
        }
    }

    private final TreadmillClient client;
    private final Listener listener;

    private volatile boolean running;
    private volatile boolean userStop;
    private Thread thread;

    public PlanRunner(TreadmillClient client, Listener listener) {
        this.client = client;
        this.listener = listener;
    }

    public boolean isRunning() {
        return running;
    }

    /** 从头开始 */
    public void start(List<Seg> segs, int planId, int uid) {
        start(segs, 0, 0, planId, uid);
    }

    /** @param startIndex 从第几段开始（续跑） @param lastSecond 该段内已跑秒数 */
    public void start(final List<Seg> segs, final int startIndex, final int lastSecond,
                      final int planId, final int uid) {
        if (running || segs == null || segs.isEmpty()) return;
        running = true;
        userStop = false;
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                exec(segs, startIndex, lastSecond, planId, uid);
            }
        }, "plan-runner");
        thread.setDaemon(true);
        thread.start();
    }

    /** 用户主动停止：只置标志；减速/断电由 exec 的唯一退出路径执行（M2） */
    public void stop() {
        if (!running) return;
        userStop = true;
        running = false;
    }

    private void exec(List<Seg> segs, int startIndex, int lastSecond, int planId, int uid) {
        int n = segs.size();
        int[][] arr = new int[n][4];
        int acc = 0;
        int total = 0;
        for (int i = 0; i < n; i++) {
            Seg sg = segs.get(i);
            int dur = Math.max(1, sg.seconds);
            int speed10 = (int) Math.round(clamp(sg.speed, 1.0, 12.0) * 10);
            arr[i][0] = speed10;
            arr[i][1] = acc;
            acc += dur;
            arr[i][2] = acc;
            arr[i][3] = i + 1;
        }
        total = acc;

        int idx = Math.max(0, Math.min(startIndex, n - 1));
        int base = -1;

        try {
            client.send(TreadmillClient.cmdPlan(arr, idx, 33, planId, uid, Math.max(0, lastSecond)));
            sleep(800);
            client.send(TreadmillClient.cmdPowerOn());
        } catch (IOException e) {
            running = false;
            listener.onFinish("发送失败: " + e.getMessage());
            return;
        }

        int counted = Math.max(0, lastSecond);
        int lastDuration = -1;
        long frozenAt = 0;

        while (running) {
            TreadmillClient.Status st = client.lastStatus;
            if (st != null) {
                if (base < 0 && st.power >= 1) base = st.duration;
                if (base >= 0) {
                    counted = Math.max(counted, lastSecond + Math.max(0, st.duration - base));
                }

                int segIndex = 0;
                for (int i = 0; i < n; i++) {
                    if (counted >= arr[i][1] && counted < arr[i][2]) {
                        segIndex = i;
                        break;
                    }
                    if (counted >= arr[i][2]) segIndex = Math.min(i + 1, n - 1);
                }

                boolean frozen = false;
                // 设备每 ~2 秒才更新一次 duration，用 12 秒无变化判定"无人冻结"，避免误报
                if (st.power == 2 && st.duration == lastDuration) {
                    if (frozenAt == 0) frozenAt = System.currentTimeMillis();
                    else if (System.currentTimeMillis() - frozenAt > 12000) frozen = true;
                } else {
                    frozenAt = 0;
                }
                lastDuration = st.duration;

                int percent = total > 0 ? (int) Math.min(100, counted * 100L / total) : 0;
                listener.onProgress(segIndex, n, arr[segIndex][0] / 10.0,
                        counted, total, percent, frozen);

                if (counted >= total) {
                    running = false;
                    listener.onFinish("完成，共 " + (total / 60) + " 分钟");
                    return;
                }
                // 设备跑完会自动进停止态
                if (st.power == 3 && counted > 0) {
                    running = false;
                    listener.onFinish("设备已结束（" + counted + "/" + total + " 秒）");
                    return;
                }
            }
            sleep(1000);
        }
        // M2：唯一的停止出口，命令不再与 exec 循环并发
        if (userStop) {
            try {
                for (int i = 0; i < 4; i++) {
                    client.send(TreadmillClient.cmdSpeed(0));
                    sleep(1200);
                }
                client.send(TreadmillClient.cmdPowerOff());
            } catch (IOException ignored) {
            }
        }
        listener.onFinish("已停止");
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }


}
