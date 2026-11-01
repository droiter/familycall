# 亲情视频（FamilyCall）

一款 Android 端、**不依赖服务器**的点对点视频通话应用。双方各自配置本机端口和对方 IP/端口后即可直接呼叫，适合家庭局域网、已做端口映射的公网 IP、IPv6 或 VPN 组网。

> 本目录是完整可编译工程。已生成可安装 APK：`dist/FamilyCall-1.0.6.apk`。

---

## 1. 已实现功能

| 需求 | 实现 |
|---|---|
| 移动网络下工作，考虑延迟/带宽/网络变化/信号中断 | 自定义 UDP 媒体通道、H.264/AAC 硬件编解码、分片重组、抖动缓冲、自适应码率、发送整形、丢包后请求关键帧、网络切换自动重绑 UDP、UDP 不通时自动切换 TCP 媒体备用通道、TCP 控制通道断线重连、60 秒媒体看门狗、通话中 WakeLock |
| 配置通讯对象：名字、IP、端口 | 首页「添加」/联系人编辑弹窗；支持 IPv4、IPv6、域名 |
| 配置本端端口 | 设置页「本机端口（TCP + UDP）」，保存后自动重新监听 |
| 配置本机名字，接听者显示对方名字 | 设置页「本机名字」；呼叫时随 HELLO 发送，接听页显示 |
| 儿童模式：来电显示接听按钮，接听后全屏视频，无其他按键，不能挂机 | 儿童模式首页只显示已配置联系人；来电页显示接听；接通后隐藏所有按键/顶部栏/本机小窗，沉浸式全屏，返回键无效，通知栏不提供挂断；仅对方挂断或网络超时可结束 |
| 儿童模式只能拨打已配置对象，无配置则无法拨打 | UI 不提供拨号盘/任意地址入口；`CallService` 再次校验联系人白名单；无联系人时直接拒绝 |

另外包含：静音、扬声器/听筒、前后摄像头切换、实时通话统计、来电铃声/震动、锁屏全屏来电、前台常驻服务、家长 PIN 保护设置。

---

## 2. 安装与使用

### 安装

```bash
adb install -r dist/FamilyCall-1.0.6.apk
```

或把 APK 发送到手机点击安装（需允许“安装未知来源应用”）。

### 首次配置（两台手机）

1. 两台手机都打开应用，允许**摄像头、麦克风、通知**权限。
2. 在「设置」中填写：
   - **本机名字**：例如“奶奶的手机”（对方来电时会看到）；
   - **本机端口**：默认 `50000`，两台手机可使用相同或不同端口；须与对方联系人里填写的端口一致；
   - 儿童模式按需开启，并设置 4–8 位家长密码（默认 `1234`，建议修改）。
3. 在首页「添加」联系对方：
   - 名字：例如“小明”；
   - IP 地址/域名：对方手机在当前网络下的地址；
   - 端口：对方设置里的本机端口。
4. 双方网络需满足：
   - 同一个 Wi-Fi/局域网；或
   - 双方都有公网可达 IPv4/IPv6；或
   - 在路由器/防火墙上把本机端口 **TCP 和 UDP 同时**映射到手机；或
   - 使用 Tailscale/ZeroTier 等 VPN，并填写 VPN 地址。
5. 在首页点击联系人拨打；对方会收到全屏来电页，接听后开始视频。

### 儿童模式

- 家长在设置中开启儿童模式并设置 PIN。开启后首页不再显示设置/添加/编辑，只显示联系人卡片。
- 儿童点击联系人即可拨打。没有联系人时页面会提示，且无法发起任何拨打。
- 来电时显示接听/拒绝；接听后进入全屏视频，所有按键隐藏。
- 儿童模式**接通后不能主动挂机**；对方挂断、网络超时或通话失败时自动退出。
- 家长解锁：点击右上角锁形按钮，或长按标题，输入 PIN 后进入设置。
- 儿童模式拨打后、对方尚未接听时，会显示「取消呼叫」按钮；一旦接通即隐藏。

---

## 3. 编译

环境：Android SDK Platform 36、Build Tools 36.0.0、JDK 17、Gradle 9.2.1、AGP 8.13.2。

```bash
# local.properties 中 sdk.dir 指向本机 Android SDK
./gradlew assembleDebug      # 调试包
./gradlew assembleRelease    # 可直接安装的演示签名 release 包
./gradlew testDebugUnitTest  # 协议/码率控制单元测试
./gradlew lintDebug          # Android Lint
```

本仓库的构建环境使用离线缓存：

```bash
GRADLE_USER_HOME=/workspace/termux/.gradle-home \
  ./gradlew --offline assembleRelease testDebugUnitTest
```

输出：
- `app/build/outputs/apk/debug/app-debug.apk`
- `app/build/outputs/apk/release/app-release.apk`
- `dist/FamilyCall-1.0.6.apk`（release 复制件）

> Release 演示包使用 Android 默认 debug keystore 签名，仅用于安装测试。正式发布请替换为自己的 keystore。

---

## 4. 网络与直连限制（重要）

本应用**不经过任何服务器、也没有 STUN/TURN/中继**，因此：

1. 呼叫方必须能主动 TCP 连接到被叫方配置的 `IP:端口`；双方媒体还要求 UDP 该端口双向可达。
2. 移动运营商 CGNAT 通常不允许从公网主动连入。跨运营商/跨蜂窝网络直连时，需要：
   - 运营商分配的公网 IPv4 + 端口映射；或
   - 可路由的公网 IPv6；或
   - VPN 组网（Tailscale 等）。
3. 处于同一 Wi-Fi 时最容易验证；如果一方在蜂窝、一方在家宽，通常需要端口映射或 VPN。
4. 控制通道用 TCP，媒体用 UDP。仅开放 TCP 时能看到“已连接”但没有画面/声音；请确认 UDP 同样放行。
5. 当前媒体流**没有加密**。请仅在可信网络/VPN 中使用，或后续加入 DTLS-SRTP/AES-GCM 与身份认证。
6. 手机重启后需要重新打开应用，前台服务才会重新监听；应用被系统强制停止后也无法接收来电。

这些是“无服务器直连”架构的固有限制。若要开箱即用地跨任意移动网络，需要增加信令服务器 + STUN/TURN 中继（WebRTC 或自建），不在本版本范围内。

---

## 5. 架构概览

```
┌──────────────────── Android 手机 A ────────────────────┐
│ MainActivity / IncomingCallActivity / CallActivity      │
│            │ Binder                                     │
│        CallService（前台服务、通知、铃声、网络监听）      │
│            │                                            │
│     CallSession（状态机、重连、看门狗）                  │
│       │                         │                       │
│  ControlLink（TCP JSON）    MediaEngine（UDP）           │
│  心跳 / RTT / 重连          VideoEncoder/Decoder         │
│                            AudioCapture/Playback        │
│                            Camera2 + TextureView        │
└─────────────────────────────────────────────────────────┘
              TCP 控制 + UDP 媒体（同一端口号）
┌──────────────────── Android 手机 B ────────────────────┐
```

### 控制通道

- TCP 换行分隔 JSON；端口与媒体 UDP 端口相同。
- HELLO / RINGING / ACCEPT / REJECT / BUSY / BYE / PING / PONG / VIDEO。
- 2 秒心跳、10 秒超时；控制断开时媒体可继续，原呼叫方自动重连并发送 `resume:true`。
- 接听方收到同 `callId` 的 resume 后恢复控制；未知 resume 回复 `ended`，不会误触发新来电。
- 控制恢复后更新 UDP 对端地址，重新请求关键帧。

### 媒体通道

- 自定义 29 字节头：magic、type、flags、streamId、seq、frameSeq、timestamp、fragIndex、fragCount、length、version。
- 视频 H.264 Baseline（默认 640×480@24、自动码率 480–1000 kbps 蜂窝 / 700–1500 kbps Wi-Fi；高清 720p 可选）；音频 AAC-LC 16 kHz 单声道 32 kbps。
- H.264 帧按 ≤1150 字节分片；接收端按 frameSeq 重组，缺片超时丢弃并请求 IDR。
- 支持异厂商 encoder 长度前缀输出自动转为 Annex-B。
- 发送端令牌桶整形，防止瞬时突发打爆移动网络；队列积压时丢最旧帧并请求关键帧。
- 接收端每 500 ms 回传丢包/接收码率/抖动反馈；发送端按丢包快速降码率、干净窗口缓慢升码率。
- 音频 2 帧（约 40–50 ms）抖动缓冲，缺失帧跳过，AAC 解码后写入 AudioTrack。
- 网络变化：`ConnectivityManager` 回调触发 UDP socket 重新 bind、重新探测、请求关键帧；TCP 断开时指数/固定间隔重连。
- 看门狗：12 秒无真实媒体 → 显示重连；60 秒无媒体 → 结束通话。
- 通话期间持有 `PARTIAL_WAKE_LOCK`，屏幕由 `keepScreenOn` 保持。

### 视频方向

- 摄像头按 sensor orientation 在发送端编码；接收端通过控制消息 `VIDEO {rot, mirror}` 获知旋转。
- 渲染使用 TextureView 整体旋转 + 等比放大裁切，避免 TextureView 矩阵在不同厂商上的差异。
- 音频使用 `VOICE_COMMUNICATION` + AEC（设备支持时），默认扬声器。

---

## 6. 源码结构

```
app/src/main/java/com/familycall/app/
├── MainActivity.java             首页/联系人/儿童模式入口
├── SettingsActivity.java         本机名字、端口、儿童模式、PIN、画质
├── IncomingCallActivity.java     全屏来电接听/拒绝
├── CallActivity.java             全屏通话、控制、统计、儿童模式锁定
├── CallService.java              前台服务、TCP 监听、通知、铃声、网络回调
├── data/                         联系人与配置、通话快照/统计
├── net/
│   ├── Protocol.java             协议常量、地址/时长工具
│   ├── MediaPacket.java          UDP 包头与分片/反馈
│   ├── CodecConfig.java          CSD 打包
│   ├── ControlLink.java          TCP JSON 心跳与 RTT
│   ├── InboundListener.java      TCP 监听与首次 HELLO 读取
│   ├── CallSession.java          通话状态机/重连/媒体协调
│   └── AdaptiveBitrate.java      丢包驱动码率控制
├── media/
│   ├── MediaEngine.java          UDP 收发、分片重组、反馈、统计
│   ├── VideoEncoder.java         MediaCodec H.264 编码
│   ├── VideoDecoder.java         MediaCodec H.264 解码
│   ├── H264.java                 Annex-B 规范化
│   ├── CameraController.java     Camera2 采集
│   ├── AudioCapture.java         AudioRecord + AAC 编码
│   └── AudioPlayback.java        AAC 解码 + 抖动缓冲 + AudioTrack
└── ui/UiUtils.java               旋转裁切、PIN 弹窗、尺寸工具

app/src/test/…                     JUnit 协议/码率/H.264 测试
tools/                             纯 JVM 自测程序
docs/ARCHITECTURE.md               更详细的实现说明
```

---

## 7. 测试情况

已在本构建环境执行：

- `:app:assembleDebug` / `:app:assembleRelease` — 通过；
- `:app:testDebugUnitTest` — 6 个测试通过（包头往返、反馈、分片重组、CSD、码率控制、H.264 长度前缀）；
- `:app:lintDebug` — 0 error（保留 API/机型相关 warning）；
- `tools/UdpSocketSelfTest` — UDP 收发与重新 bind 通过；
- `tools/ProtocolSelfTest` — 14 项协议检查通过。

**未连接真机或模拟器**：本环境没有 Android 设备/系统镜像，因此未执行摄像头、麦克风、实际双机视频通话和移动网络切换的真机验证。首次部署请按第 2 节用两台手机在同一 Wi-Fi 下验证，再按需要配置公网/VPN。

---

## 8. 安全与隐私

- 联系人和设置仅保存在应用私有 `SharedPreferences`，不联网上传。
- 家长 PIN 以明文存储在应用私有目录（仅适合家用设备；如需更强保护应改为加盐哈希）。
- 音视频、控制信令均未加密。直连 IP 会暴露给对端；在不可信网络中请使用 VPN。
- 应用仅申请通话所需权限；无广告/统计 SDK/第三方依赖（仅 JUnit 测试依赖）。

---

## 9. 后续可扩展

- WebRTC（ICE/STUN/TURN）以获得跨任意 NAT 的连通性；
- DTLS-SRTP 或自定义 AES-GCM 加密与联系人指纹校验；
- 呼叫记录、忙碌/勿扰、蓝牙音频路由、屏幕旋转自适应；
- 开机自启、电量优化白名单引导；
- 自适应分辨率（当前分辨率固定，仅码率/帧率层面自适应）。

## 10. 修复记录

- **v1.0.6**
  - 来电通知改用系统 `CallStyle`（Android 12+）：在折叠状态和锁屏上也直接显示“接听/拒接”两个大按钮，并标记为视频通话；旧系统仍使用普通通知按钮。
  - 未授予“全屏通知”权限时，服务在息屏来电时申请短时 `FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP` 点亮屏幕，让锁屏上的来电通知可见可点，尽量做到无需该权限也能接听。
  - 未授予“全屏通知”或通知权限时，首页顶部显示红色提醒条，设置页状态卡也会提示，并可直接跳转对应系统设置；全屏通知引导改为每次启动未授权都提醒（不再只弹一次）。
  - 来电通知补日志（是否 CallStyle、是否可用全屏通知），便于排查不同厂商 ROM 的锁屏行为。
- **v1.0.5**
  - 修复接听时在主线程向 TCP 控制连接写 `ACCEPT` 导致 `NetworkOnMainThreadException`、随后显示“接听失败”的问题；所有控制信令改为专用写线程异步发送。
  - 接听后的媒体初始化移到后台线程，避免主线程创建 UDP/音频资源造成卡顿。
  - 通知栏“接听”按钮改为 Activity PendingIntent + 自动接听，避免 App 在后台时被 Android 后台启动限制拦截，确保接听后能打开全屏通话页。
  - 通话结束/挂断/取消时给控制通道留出发送 BYE 的短暂时间。
