# 小乔跑步机 App 发布前代码全面审查报告

- 审查对象：`xiaoqiao/android/app-debug.apk`（22 个 Java 源文件，6118 行）+ 构建脚本 + Manifest
- 审查方式：全量源码走查 + APK 产物校验（签名、清单、版本）
- 审查日期：2026-11-22
- 结论：**当前状态不建议直接发布**。发现 7 个发布阻断项（严重）、8 个高优先级问题、12 个中低优先级问题与若干优化建议。

---

## 一、发布阻断项（严重，必须先修）

### S1. 每个 Activity 各自新建 TCP 连接，多连接互踢 + 自动重连风暴

**位置**：`MainActivity.connectTo()` / `CourseActivity.connect()` / `PlanActivity.connect()` / `AddDeviceActivity.showConnecting()`

**问题**：四个页面各自 `new TreadmillClient(...)` 直连跑步机，而主页连接在 `startActivity` 之后并不释放。如果跑步机只接受单客户端（家用 IoT 常见限制）：

1. 课程页连上 → 主页那条连接被踢 → `MainActivity.onDisconnected` → 3 秒后 `maybeAutoConnect()` 自动扫描重连 → 又把课程页踢掉 → 课程中断、两页循环互踢。
2. 用户在主页点「添加设备」换设备时，主页仍连着旧设备，返回后 `connectTo()` 因 `isConnected()` 直接 return，新设备永远连不上（见 H4）。

**修复方案**（推荐做法）：

```
新增 ConnectionManager（Application 级单例）：
  - 全局唯一 TreadmillClient 实例，持有连接状态、当前 IP
  - Activity 通过 addListener/removeListener 订阅回调，不再各自 connect
  - 自动发现/自动重连逻辑收敛到 ConnectionManager 内部（带退避）
  - 切换设备 = 调 ConnectionManager.switchTo(ip)

迁移步骤：
  1. TreadmillClient 增加 Listener 注册/注销接口（CopyOnWriteArrayList）
  2. MainActivity.onCreate 从 ConnectionManager 订阅；onDestroy 取消订阅
  3. CourseActivity/PlanActivity/AddDeviceActivity 去掉 new TreadmillClient，
     发送命令一律走 ConnectionManager.send(...)
  4. 主页「切换」改为调用 ConnectionManager.switchTo
```

**工作量预估**：1–2 天，收益最大，建议最先做。

---

### S2. TCP 收包按字节扫 0x0D0A 切帧，不读长度字段，帧体出现 0D0A 会切错

**位置**：`TreadmillClient.readLoop()`（314–341 行）

**问题**：当前逻辑是"任意时刻缓冲末尾出现 `0D 0A` 就切一帧"。但 0x0D 0x0A 只是帧尾约定，**帧体数据里完全可能出现这两个连续字节**——例如状态帧 `b[20]=13`（速度 1.3 km/h）且 `b[21]=0x0A` 时，会在帧中间提前切帧，产生一条 22 字节垃圾帧 + 一条残帧，该次状态更新丢失。每字节还调用 `buf.toByteArray()`，分配 O(n²)。

**修复方案**：

```
readLoop 改为状态机解析，使用协议自带的长度字段：
  - 帧格式 [type][len][payload...][chk_hi][chk_lo][0D][0A]
  - 固定头帧（type=0x11/0x15/0x13/0x04 等）：总长 = len + 4（chk+换行）
  - 0x12 变长帧：len 字段即 payload 长度，总长 = 2 + len + 4
  - 用 BufferedInputStream 按块读；先读 2 字节头 → 计算总长 → 循环读满 →
    校验末尾确实是 0D0A、校验 sum（索引 2 起累加 == chk）→ 通过才 dispatch
  - 任一步不满足：丢弃 1 字节重新同步（resync）

伪代码：
  while (alive) {
      int type = read(); int len = read();
      int total = (type == 0x12) ? 2 + len + 4 : len + 4;
      byte[] frame = readFully(total - 2);
      if (frame[total-3]==0x0D && frame[total-2]==0x0A && sumOK(frame)) dispatch(frame);
      else resync();
  }
```

---

### S3. `TreadmillClient.send()` 无锁，多线程并发写会交错

**位置**：`TreadmillClient.send()`（356–361 行）

**问题**：`send` 被以下线程并发调用：UI 主线程（`doSend`/`resend`/`send(cmd,...)`）、心跳线程、`safeStop` 线程、`PlanRunner`/`CourseRunner` 线程。`OutputStream.write` 不保证多写者原子，两个帧的字节可能在 TCP 流里交错，跑步机收到残帧。

**修复方案**：

```java
private final Object writeLock = new Object();
public void send(byte[] data) throws IOException {
    OutputStream o = out;
    if (o == null) throw new IOException("未连接");
    synchronized (writeLock) {
        o.write(data);
        o.flush();
    }
}
```

一行锁即可，所有调用方无需改动。

---

### S4. MainActivity 销毁后 Handler 延迟任务仍执行，泄漏 + 幽灵重连

**位置**：`MainActivity.onDisconnected()`（852–857 行 `postDelayed(3000)`）、`onDestroy()`（95–101 行）

**问题**：`onDisconnected` 里 `ui.postDelayed(reconnect, 3000)`；用户在这 3 秒内退出 App → Activity 已销毁，runnable 照跑 → `maybeAutoConnect()` 在死 Activity 上启动扫描线程、发起连接。`onDestroy` 没有 `ui.removeCallbacksAndMessages(null)`。

**修复方案**：

```java
@Override protected void onDestroy() {
    super.onDestroy();
    ui.removeCallbacksAndMessages(null);   // 清掉 doSend/resend/延迟重连
    saveFreeRun();
    ...
}
// 重连 runnable 内再加守卫：
if (isFinishing() || isDestroyed()) return;
```

`isDestroyed()` 需 API 17+，minSdk 21 满足。同样问题存在于 `CourseActivity`/`PlanActivity` 的 `ui.post`（程度较轻，销毁后只是空跑），建议统一在 onDestroy 清理 Handler。

---

### S5. 心跳 ScheduledExecutorService 泄漏：自然断线不停、重连后叠加

**位置**：`TreadmillClient.connect()` / `readLoop()` / `disconnect()`

**问题**：
- 断线走 `readLoop` 的 finally 分支时，`heartbeat` 没有被 shutdown——只有用户显式调 `disconnect()` 才停。心跳线程每 5 秒对死 socket 写一次（异常被吞），**永久存活**。
- 用户在未调 `disconnect()` 的情况下再次 `connect()`（代码无重入保护），会再创建一个 executor，两个心跳并发写。

**修复方案**：

```java
// readLoop 的 finally 里：
private void shutdownHeartbeat() {
    if (heartbeat != null) { heartbeat.shutdownNow(); heartbeat = null; }
}
// connect() 开头：
shutdownHeartbeat();
closeSocketQuietly();
// finally { alive=false; shutdownHeartbeat(); listener.onDisconnected(reason); }
```

---

### S6. 待发布 APK 使用公开 Android Debug 证书签名

**实测**：`apksigner verify --print-certs app-debug.apk` → `CN=Android Debug, O=Android`。debug keystore 的口令 `android` 是公开常识，且 `debug.keystore` 就放在仓库目录里。任何人都能重新签名一个同名 APK 覆盖安装，无法防伪。

**修复方案**：

1. 生成私有发布 keystore（不入库，写进 `.gitignore`）：
   `keytool -genkeypair -keystore release.keystore -alias xiaoqiao -keyalg RSA -keysize 2048 -validity 10950`
2. `build_apk.sh` 增加 release 模式：`apksigner sign --ks release.keystore --v2-signing-enabled true ...`，输出命名 `app-release.apk`。
3. `apksigner verify --min-sdk-version 21` 验证 v2+ 签名。
4. 版本号提为 `versionCode 2 / versionName 1.0.1` 起步。

---

### S7. 声明了 ACCESS_FINE_LOCATION 却从不申请，WiFi 预填功能实际不可用

**位置**：`AndroidManifest.xml`（9 行）/ `ProvisionActivity.prefill()`（162–175 行）

**问题**：Android 6.0+ 读取 SSID/BSSID 需要运行时定位授权，代码里没有任何 `requestPermissions` 调用 → `info.getSSID()` 永远返回 `"<unknown ssid>"` → 预填逻辑空转。同时 Manifest 里这个权限还会让商店页面显示"需要定位"，用户体验差。

**修复方案**（二选一）：

- 方案 A（保留预填）：`ProvisionActivity.onCreate` 里 `requestPermissions(new String[]{ACCESS_FINE_LOCATION}, 100)`，`onRequestPermissionsResult` 成功后再 `prefill()`；拒绝时保持手动输入。
- 方案 B（砍掉自动预填）：删除权限声明与 `prefill()` 中的 SSID/BSSID 读取，配网页本来就是手动输入，BSSID 可留空。建议先用 B 上线，A 后续版本再加。

---

## 二、高优先级问题

### H1. `ProvisionActivity.localIp()` 可能拿到错误的网卡 IP

**位置**：177–194 行。遍历所有网络接口取**第一个**非环回 IPv4。多网卡手机（蜂窝 + WiFi + VPN + 热点）可能返回蜂窝或 VPN 地址 → ESP-Touch 把错误 IP 编码发给跑步机 → 设备回执发到错误地址，配网超时失败。

**修复方案**：

```java
// 优先用 WiFi 接口的地址（与路由器同网段，回执才能到手机）
WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
int ip = wm.getConnectionInfo().getIpAddress();
if (ip != 0) return String.format(Locale.US, "%d.%d.%d.%d",
        ip & 0xFF, (ip >> 8) & 0xFF, (ip >> 16) & 0xFF, (ip >> 24) & 0xFF);
// 兜底再走 NetworkInterface 遍历，但跳过 name 含 "ppp"/"tun"/"rmnet" 的接口
```

### H2. AddDeviceActivity 扫描超时判断写错位置，设备持续广播时永远超时不了

**位置**：`AddDeviceActivity.startScan()`（310–326 行）。超时检查只在 `SocketTimeoutException` 分支里；跑步机广播周期约 2 秒，`sock.receive` 基本不超时 → `while (scanning)` 空转到用户手动取消。对比 `MainActivity.startAutoScan` 把 deadline 放在 while 条件里，是正确的。

**修复方案**：把超时判断移到循环顶部：

```java
while (scanning) {
    if (System.currentTimeMillis() - start > SCAN_TIMEOUT_MS) { showFail(...); return; }
    ...
}
```

### H3. 收帧不校验校验和

**位置**：`TreadmillClient.dispatch()`。现在只看长度/类型。干扰帧、串扰帧只要凑够长度就会被当成状态。

**修复方案**：与 S2 的状态机改造一起完成——`sum(索引2起) == chk_hi<<8|chk_lo` 才 dispatch，否则丢弃并 resync。

### H4. 换设备流程：主页未断旧连接，返回后连不上新设备

**位置**：`MainActivity.onActivityResult`（103–110 行）→ `connectTo(ip)` 第 621 行 `if (isConnected()) return;`

**问题**：主页连接旧设备 A 时进「添加设备」找到 B，B 写入偏好；返回主页 `connectTo(B)` 因仍连着 A 直接返回，界面显示还连着 A，用户以为换机成功。

**修复方案**（在 S1 架构改造前可先打补丁）：

```java
// 进添加向导前断开（btnAdd 点击处）：
disconnect();
// connectTo 改为按 IP 判断：
if (isConnected()) {
    if (ip.equals(currentIp)) return;
    client.disconnect(); client = null;   // IP 变了才强制切换
}
```

### H5. EsptouchProvisioner 无重入保护、配网期间无唤醒锁

**位置**：`EsptouchProvisioner.start()` / `ProvisionActivity`。

- `start()` 不检查 `running`，重复调用会起两个发送线程（UI 层的 `prov != null` 保护在 stop→start 间隙存在竞态）。
- 120 秒配网期间屏幕熄灭，线程被挂起 → 广播停止 → 配网失败。

**修复方案**：

```java
public void start(...) {
    if (running) return;          // 重入保护
    ...
}
// ProvisionActivity.onCreate：
getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
// 或配网线程里 acquire PARTIAL_WAKE_LOCK，finally 释放
// 接收 socket：new DatagramSocket(null) → setReuseAddress(true) → bind(18266)，
// 避免快速重进页面时 "Address already in use"
```

### H6. `TreadmillClient.connect()` 无并发/重复保护

连续调用产生多条 rx 线程、多个心跳调度器，旧 socket 不关闭。修复方案同 S5：connect 入口同步清理旧资源后再建连。

### H7. 自动重连无退避、无次数上限，失败场景可能反复扫描空耗

**位置**：`MainActivity.onDisconnected` → 3 秒后 `maybeAutoConnect` → 12 秒扫描 → 失败静默。路由器不在线时每次回前台都会扫一轮。

**修复方案**：ConnectionManager 内做指数退避（3s → 10s → 30s，上限）+ 连续失败 5 次后停止自动重试，状态栏提示"未找到跑步机，点右上角重试"。

### H8. UDP 发现 socket 未设置 SO_REUSEADDR

**位置**：`MainActivity.startAutoScan` / `AddDeviceActivity.startScan`。快速重启扫描或两个页面先后扫描时， bind(19032) 抛 "Address already in use" → 日志里出现"自动发现失败/扫描失败"。

**修复方案**：

```java
DatagramSocket sock = new DatagramSocket(null);
sock.setReuseAddress(true);
sock.bind(new InetSocketAddress(TreadmillClient.UDP_PORT));
sock.setSoTimeout(...);
```

---

## 三、中优先级问题

| # | 位置 | 问题 | 修复建议 |
|---|------|------|----------|
| M1 | `PlanActivity.java`、`CourseRunner.java`、`Plan.java` | **死代码**：PlanActivity 未在 Manifest 注册、无任何跳转入口；CourseRunner/Plan 全工程无引用，但都被打进 classes.dex | 二选一：①删除三个文件；②若"计划"功能后续要上线，注册 Manifest 并加主页入口。发布版建议先删 |
| M2 | `PlanRunner.stop()` | stop 线程（4×减速+断电）与 exec 线程的循环发送并发，时序依赖设备容错；stop 后 exec 还会回调 `onFinish("已停止")` | stop 只置 `running=false`；把"减速到 0 → 1.5s → 断电"收敛到 exec 的唯一退出路径（finally），保证命令序列唯一 |
| M3 | `CourseActivity.onDestroy` | 停掉全局单例 `Metronome`/`VoiceCoach`：用户在主页开的节拍器，进一次课程页返回就被停 | 明确归属：课程页只停"自己启动"的实例，或主页 onResume 时若发现被停且用户此前开启则恢复；至少确认这是预期行为并写进注释 |
| M4 | `VoiceCoach.shutdown()` / `Metronome.release()` | 定义了释放方法但全工程无人调用，TTS 引擎/ToneGenerator 进程期常驻 | 在 Application 或主页 onDestroy 统一释放；ToneGenerator 底层句柄有限，反复进出会累积 |
| M5 | `ShareCard.render` | ①"本次节奏"标题下**没有画任何分段内容**（死文案）；②课程名 74sp 超长（自定义名）直接出血，无 measureText/缩字号 | 补分段轴绘制（最多 8 段，宽度按秒数比例）或删标题；课程名超宽时二分缩字号 |
| M6 | `ShareCard.saveToGallery` | `openOutputStream` 返回 null 时 `compress` 抛 NPE 被吞返回 null，但 MediaStore 里残留的 pending 记录不清理（API 29+） | 判空；catch 分支 `ctx.getContentResolver().delete(uri, null, null)` |
| M7 | `HistoryActivity.doShare` | 权限被拒分支 `pendingRec` 未置空，Activity 销毁前一直持有 | 拒绝时 `pendingRec = null` |
| M8 | `AddDeviceActivity.onConnected` | 连接成功后 client 直到 onDestroy 才断开，期间与主页连接并存（S1 衍生）；`connectedOnce` 为 true 后 onDisconnected 直接 return，连接悄悄断开用户无感知 | 纳入 S1 改造；补丁：showSuccess 后立即 `client.disconnect()` |
| M9 | `MainActivity` | `connecting`/`manualDisconnect` 非 volatile，rx 线程与 UI 线程存在数据竞争（`autoScan` 是 volatile，不一致） | 统一加 volatile，或并入 S1 后由单线程持有状态 |
| M10 | `PlanActivity` 多处 | `u.styleFilled(btnStart, x)` 连续调用两次（328、380、390 行附近） | 删冗余行 |
| M11 | `NaturalPlan.parseDay` | "3分半" → `Math.round(3.5)=4`，分半语义变成 4 分钟 | `(int)(minutes + 0.5)` 同样结果；要保真就 `minutes*60` 直接转秒存 `Seg.ofSeconds`，不改写为整分钟 |
| M12 | `Ui.gap(View, float dp)` | 参数名 `dp` 遮蔽方法 `dp()`，可读性差 | 参数改名 `marginDp` |

---

## 四、低优先级 / 优化建议

1. **构建**：`build.gradle` release 未开 R8（`minifyEnabled false`）。无三方依赖收益有限，但建议 `minifyEnabled true` + `shrinkResources true`，并加混淆字典防闲人 diff 逆向。
2. **版本号单一来源**：`build.gradle` 与 `build_apk.sh` 各写死 `versionCode 1 / versionName 1.0`，改成脚本从 gradle 读或独立 `version.txt`。
3. **readLoop 性能**（并入 S2 一并解决）：单字节 read + 每字节 `toByteArray()`。
4. **Manifest 主题**：`@android:style/Theme.Material.Light` 带系统 ActionBar，与自绘顶栏可能双重标题栏，需在真机确认；建议换 `Theme.Material.NoActionBar`（仅保留窗口背景/状态栏染色逻辑不变）。
5. **cmdTime 截断静默**：`minutes*60` 超 0xFFFF 被夹到上限 18.2 小时，UI 无提示；chips 固定 15/30/45/60 不会触发，仅防御。
6. **RecordStore**：全量 JSON 读写 200 条规模 OK；后续若加月统计再迁 SQLite/Room（当前手工构建不便引入 Room，可用 SQLiteOpenHelper）。
7. **协议层建议补 JVM 单测**：`frame()`/`Status.parse`/`cmdPlan` 边界、ESP-Touch 编码与官方向量比对，这些全是纯函数，测试成本极低、回归价值最高。
8. **死工具方法**：`PlanRunner.parse`、`NaturalPlan.describe`、`Ui.metric`、`ThemeManager.modeName` 无调用方，随 M1 一起清理。
9. **TTS 播报打断**：`speak` 用 `QUEUE_FLUSH`，连续播报互相截断；教练场景可接受，若要完整句子改 `QUEUE_ADD` + 去重。
10. **保存自由跑 discard 阈值** `dur < 60` 直接丢弃且无提示，用户短时跑步会觉得"记录丢了"，建议 toast 提示"不足 1 分钟不计入"。
11. **AddDeviceActivity.startScan finally** 里 `scanning=false` 在成功分支已置过，冗余（无害）。
12. **日期边界**：`HistoryActivity.today/month` 在构造时取一次，跨午夜后"今天"标记会过期（重进页面即恢复，影响极低）。

---

## 五、发布前 Checklist（建议按序执行）

- [ ] S1 连接架构收敛为单例 ConnectionManager（或至少修 H4/H6/M9）
- [ ] S2 + H3 收包状态机 + 校验和验证
- [ ] S3 send 加锁
- [ ] S4 onDestroy 清理 Handler + isDestroyed 守卫
- [ ] S5 心跳生命周期修复
- [ ] H1/H2/H5/H8 配网与扫描健壮性
- [ ] M1 死代码处理
- [ ] M2 停止流程收敛
- [ ] S6 私有 keystore 重新签名 + 版本号提升
- [ ] S7 定位权限策略定稿
- [ ] M5 分享卡补充分段轴或删死文案
- [ ] 真机回归：换设备流程、课程页进出 20 次（观察 Metronome/TTS）、断网重连、分享保存/分享、深色模式
- [ ] 协议编解码单测补齐

---

## 附：审查覆盖范围

- 全部 22 个 Java 源文件逐行走查（通信、存储、UI、业务、工具）
- `AndroidManifest.xml`、`build.gradle`、`settings.gradle`、`gradle.properties`、`build_apk.sh`
- APK 产物实测：签名证书、versionCode/Name、权限列表、Activity 注册数（5 个）、dex 大小 149 KB
- 历史逆向笔记目录 `analysis/` 未逐条复核，协议常量以源码注释与实测一致为准
