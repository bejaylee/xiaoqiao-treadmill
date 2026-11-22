# -*- coding: utf-8 -*-
"""S2/S3/S5/H6：TCP 收包状态机、校验和、写锁、心跳生命周期。"""
import io

p = "TreadmillClient.java"
s = io.open(p, encoding="utf-8").read()

marker = "    // ------------------------------------------------------------ TCP"
idx = s.find(marker)
if idx < 0:
    raise SystemExit("TCP section not found")

new_section = r'''    // ------------------------------------------------------------ TCP
    private Socket socket;
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
                    synchronized (TreadmillClient.this) {
                        socket = s;
                        out = s.getOutputStream();
                        in = s.getInputStream();
                        ip = host;
                        alive = true;
                        connecting = false;
                    }
                    rxThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            readLoop();
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
    private void readLoop() {
        String reason = "连接已断开";
        try {
            InputStream is = in;
            while (alive) {
                int type = is.read();
                if (type < 0) break;
                int len = is.read();
                if (len < 0) break;
                if (len > 512) {                    // 明显不是帧头，向后同步
                    resync(is);
                    continue;
                }
                byte[] tail = readFully(is, len + 4);   // data + chk2 + CRLF2
                if (tail == null) break;
                if ((tail[len + 2] & 0xFF) != 0x0D || (tail[len + 3] & 0xFF) != 0x0A) {
                    resync(is);
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
            alive = false;
            cleanup();
            listener.onDisconnected(reason);
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
    private void resync(InputStream is) throws IOException {
        int prev = -1;
        while (alive) {
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
        cleanup();
    }
}
'''

s = s[:idx] + new_section
io.open(p, "w", encoding="utf-8").write(s)
print("TreadmillClient rewritten, lines:", s.count("\n"))
