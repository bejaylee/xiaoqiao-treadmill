package com.xq.treadmill;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 全局连接管理器：整个进程只有一条到跑步机的 TCP 连接。
 *
 * 解决的问题：
 *   · 多个 Activity 各建一条连接，设备只接受单客户端时互踢
 *   · 自动发现与直连并发时互相打断，形成「连上→断开→重连」震荡
 *
 * 页面只负责注册回调与发命令，不自己 new TreadmillClient。
 */
public class ConnectionManager implements TreadmillClient.Listener {

    public interface Listener {
        void onConnected();
        void onDisconnected(String reason);
        void onStatus(TreadmillClient.Status s);
        void onFrame(byte[] payload);
    }

    private static ConnectionManager instance;

    public static synchronized ConnectionManager get() {
        if (instance == null) instance = new ConnectionManager();
        return instance;
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    /** 所有 socket 写统一在这里串行执行，避免主线程网络操作与并发交错 */
    private final java.util.concurrent.ExecutorService writer =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<Listener>();
    private TreadmillClient client;
    private String ip = "";
    private boolean manualStop;
    private volatile boolean switching;      // 只抑制换设备时旧连接的那一次断开
    private volatile boolean discovering;
    private volatile boolean connecting;     // 建连线程正在跑，禁止重复发起
    private int failCount;
    private final long[] backoff = {3000L, 10000L, 30000L, 30000L, 30000L};

    private ConnectionManager() {
    }

    private TreadmillClient ensure() {
        if (client == null) client = new TreadmillClient(this);
        return client;
    }

    public TreadmillClient client() {
        return ensure();
    }

    public void register(Listener l) {
        if (l != null && !listeners.contains(l)) listeners.add(l);
    }

    public void unregister(Listener l) {
        listeners.remove(l);
    }

    public boolean isConnected() {
        return client != null && client.isConnected();
    }

    public String ip() {
        return ip;
    }

    public boolean isManualStop() {
        return manualStop;
    }

    public TreadmillClient.Status lastStatus() {
        return client == null ? null : client.lastStatus;
    }

    /** 保留 throws IOException 以兼容调用方；实际写入在后台线程执行 */
    public void send(final byte[] data) throws IOException {
        writer.execute(new Runnable() {
            @Override
            public void run() {
                try { ensure().send(data); }
                catch (Exception e) { Logs.add("发送失败: " + e.getMessage()); }
            }
        });
    }

    public void safeStop() {
        if (isConnected()) ensure().safeStop();
    }

    /** 连接指定设备；同 IP 的重复请求（含 force）一律忽略，避免自己打断自己 */
    public void connect(String host, boolean force) {
        if (host == null || host.isEmpty()) return;
        if (isConnected() && host.equals(ip)) return;
        if (connecting && host.equals(ip)) return;
        main.removeCallbacks(retry);
        main.removeCallbacks(connectTimeout);
        manualStop = false;
        connecting = true;
        switching = force && client != null && client.isConnected();
        ip = host;
        if (force && client != null) client.disconnect();
        main.postDelayed(connectTimeout, 8000);
        ensure().connect(host);
    }

    public void disconnect(boolean manual) {
        manualStop = manual;
        connecting = false;
        switching = false;
        discovering = false;
        main.removeCallbacks(retry);
        main.removeCallbacks(connectTimeout);
        if (client != null) client.disconnect();
    }

    /** 启动 / 回到前台时自动发现；失败按 3s→10s→30s 退避，最多 5 次 */
    public void autoFind(Context c) {
        if (isConnected() || manualStop) return;
        boolean ever = c.getSharedPreferences("xq", Context.MODE_PRIVATE)
                .getBoolean("ever", false);
        String saved = c.getSharedPreferences("xq", Context.MODE_PRIVATE)
                .getString("ip", "");
        if (!ever && (saved == null || saved.isEmpty())) return;

        // 有上次 IP：先直连；广播发现只用于 DHCP 换了地址的情况
        if (saved != null && !saved.isEmpty() && !isConnected() && !connecting) {
            connect(saved, false);
        }
        startDiscovery();
    }

    private void startDiscovery() {
        if (discovering || isConnected() || manualStop) return;
        discovering = true;
        final boolean[] triggered = {false};
        new Thread(new Runnable() {
            @Override
            public void run() {
                DatagramSocket sock = null;
                long start = System.currentTimeMillis();
                try {
                    sock = new DatagramSocket(null);
                    sock.setReuseAddress(true);
                    sock.bind(new InetSocketAddress(TreadmillClient.UDP_PORT));
                    sock.setSoTimeout(1000);
                    byte[] buf = new byte[64];
                    while (discovering && !isConnected()
                            && System.currentTimeMillis() - start < 12000) {
                        DatagramPacket p = new DatagramPacket(buf, buf.length);
                        try {
                            sock.receive(p);
                        } catch (java.net.SocketTimeoutException te) {
                            continue;
                        }
                        final TreadmillClient.Device d = TreadmillClient.parseDiscovery(p);
                        if (d == null) continue;
                        discovering = false;
                        triggered[0] = true;
                        final String found = d.ip;
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isConnected()) return;
                                // 同 IP：正在连就别打断；没在连才补一次
                                if (found.equals(ip)) {
                                    if (!connecting) connect(found, false);
                                    return;
                                }
                                // IP 变了（DHCP 重新分配）：确需切换
                                connect(found, true);
                            }
                        });
                        return;
                    }
                } catch (Exception ignored) {
                } finally {
                    discovering = false;
                    if (sock != null) sock.close();
                    // 触发过连接就交给建连回调统一调度，避免双重重试
                    if (!manualStop && !isConnected() && !connecting && !triggered[0]) {
                        main.post(retry);
                    }
                }
            }
        }, "xq-discovery").start();
    }

    public void stopAutoFind() {
        discovering = false;
    }

    private final Runnable connectTimeout = new Runnable() {
        @Override
        public void run() {
            if (connecting && !isConnected()) {
                connecting = false;       // 连接回调丢失时兜底
                Logs.add("连接超时保护：重置状态");
                main.post(retry);
            }
        }
    };

    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            if (manualStop || isConnected() || connecting) return;
            if (failCount >= backoff.length) return;      // 连续失败 5 次后停
            long delay = backoff[Math.min(failCount, backoff.length - 1)];
            failCount++;
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    startDiscovery();
                }
            }, delay);
        }
    };

    // ------------------------------------------------------------ 回调分发
    @Override
    public void onConnected() {
        main.post(new Runnable() {
            @Override
            public void run() {
                main.removeCallbacks(retry);
                main.removeCallbacks(connectTimeout);
                connecting = false;
                failCount = 0;
                manualStop = false;
                switching = false;
                discovering = false;
                for (Listener l : listeners) l.onConnected();
            }
        });
    }

    @Override
    public void onDisconnected(final String reason) {
        main.post(new Runnable() {
            @Override
            public void run() {
                boolean wasSwitching = switching;
                switching = false;          // 只抑制换设备时那一次旧连接断开
                connecting = false;
                main.removeCallbacks(connectTimeout);
                for (Listener l : listeners) l.onDisconnected(reason);
                if (!manualStop && !wasSwitching && !isConnected()) {
                    main.removeCallbacks(retry);
                    main.post(retry);
                }
            }
        });
    }

    @Override
    public void onStatus(final TreadmillClient.Status s) {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onStatus(s);
            }
        });
    }

    @Override
    public void onFrame(final byte[] payload) {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : listeners) l.onFrame(payload);
            }
        });
    }
}
