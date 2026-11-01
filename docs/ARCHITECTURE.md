# 实现说明

## 1. 线程模型

| 线程 | 职责 |
|---|---|
| main | UI、`CallService` 状态回调、通知、UDP 重绑触发 |
| `tcp-listener` | `ServerSocket.accept` |
| `caller-connect` / `caller-reconnect` | 拨号与 TCP 控制重连 |
| `control-reader` / `control-heartbeat` | 读取 JSON、心跳/RTT |
| `media-receiver` | UDP 收包、视频重组、音频入抖动缓冲 |
| `video-sender` | 视频分片发送、令牌桶整形 |
| `video-encoder` | MediaCodec 编码输出 |
| `video-decoder` | MediaCodec 解码与渲染 |
| `camera2` | Camera2 打开/配置/重复请求 |
| `audio-capture` / `audio-playback` | 音频采集编码/解码播放 |
| `media-feedback` | 500 ms 反馈包、统计、超时清理 |
| `call-cleanup` | 通话结束后异步释放 MediaCodec/Camera/Audio，避免阻塞主线程 |

## 2. 呼叫状态机

```
IDLE
 ├─ caller: DIALING ── accept ──> ACTIVE
 │              ├─ reject/busy ─> ENDED
 │              └─ control lost ─> RECONNECTING ─> ACTIVE | FAILED
 └─ callee: RINGING ── answer ──> ACTIVE
                ├─ reject/timeout ─> ENDED
                └─ caller cancel ─> ENDED
ACTIVE ── media stall 12s ─> RECONNECTING ── media back ─> ACTIVE
ACTIVE/RECONNECTING ── media stall 60s ─> FAILED
```

控制断开时：

1. `ControlLink` 在 10 秒心跳超时后关闭。
2. `CallSession.onControlClosed` 进入 `RECONNECTING`。
3. 原呼叫方每 1.2 秒重连，发送 `HELLO {resume:true, callId}`。
4. 接听方收到同 `callId` resume 后 `attachResumed`，更新 UDP 对端并回复 `ACCEPT {resume:true}`。
5. 若 30 秒内 TCP 恢复不了但 UDP 媒体仍在（<4 s），通话继续在“控制通道不可用”状态；否则结束。
6. UDP 媒体路径不断开，因此短暂 TCP 故障不会打断画面/声音。

## 3. 媒体包格式

固定头 29 字节（大端）：

| 偏移 | 长度 | 字段 |
|---|---|---|
| 0 | 4 | magic `VCM1` (0x56434D31) |
| 4 | 1 | type: 1 video / 2 audio / 3 feedback / 4 probe / 5 bye |
| 5 | 1 | flags: START / END / KEY / CONFIG |
| 6 | 4 | streamId（callId 32 位散列） |
| 10 | 4 | seq（按类型递增） |
| 14 | 4 | frameSeq |
| 18 | 4 | timestamp（视频 90 kHz，音频 16 kHz 样本） |
| 22 | 2 | fragIndex |
| 24 | 2 | fragCount |
| 26 | 2 | payloadLength |
| 28 | 1 | version |

反馈包 payload 32 字节：视频最高序号/收包数/丢包数、音频最高序号/收包数/丢包数、接收视频/音频 kbps、抖动 ms、flags（KEYFRAME 请求）。

MAX_PAYLOAD = 1150，加头后 1179 字节，低于常见 1500 MTU。

## 4. 拥塞控制

`AdaptiveBitrate` 每 1.5 秒最多调整一次：

- 丢包 ≥12%：×0.65
- 丢包 ≥5%：×0.82
- 丢包 ≥1.5%：×0.94
- 丢包 <0.8% 连续 2 个窗口，且接收码率没有明显低于目标：×1.08 + 8 kbps
- 抖动 >300 ms：强制 ×0.85
- 下限 220 kbps，上限由画质档位和蜂窝/Wi-Fi 决定。

调整通过 `MediaCodec.setParameters(PARAMETER_KEY_VIDEO_BITRATE)` 生效，无需重建编码器。

## 5. 视频重组与恢复

- 每帧分成 N 个 ≤1150 字节分片，携带 `frameSeq`、`fragIndex`、`fragCount`。
- 接收端 `Map<frameSeq, Assembly>` 收集，START/END 齐全且分片数一致后拼接，交给解码器。
- 500 ms 未完整则丢弃并置 `KEYFRAME` 反馈标志；发送端收到后 `PARAMETER_KEY_REQUEST_SYNC_FRAME`。
- 解码器在等待关键帧时丢弃 P 帧；队列 >6 帧时清空并重新等待 IDR。
- 编码器配置（SPS/PPS + 宽高）作为 CONFIG 视频包在关键帧前发送；接收端据此配置 MediaFormat。
- 音频配置（AudioSpecificConfig）随音频流每 3 秒重发一次，防止首包丢失导致永久静音。

## 6. 方向与镜像

- 发送端只发原始 sensor 数据；旋转角按 Camera2 `SENSOR_ORIENTATION` 与**重力传感器**推导的物理握持方向计算。重力传感器缺失时回退到 `Display.getRotation()`。
- `GravityOrientation` 使用 `TYPE_GRAVITY`（缺失时加速度计），按 x/y 重力分量判定 0/90/180/270，并做 350 ms 稳定性过滤，避免临界角度抖动。
- 前置摄像头区分两个角：本地预览使用镜像补偿角 `(360 - (sensor + device)) % 360` 并 `scaleX=-1`；发送给对端的原始流使用 `(sensor + device) % 360`，`mirror=false`。后置摄像头两者都使用 `(sensor - device + 360) % 360`。
- 接收端把 TextureView 的布局尺寸设为“原始宽高 × 覆盖缩放”，再旋转整个 View；父容器裁切。
  这样避免依赖 TextureView `setTransform` 在不同厂商上的坐标语义，也不会拉伸。
- 重力方向变化、屏幕方向变化都会重新发布旋转信息；通话中点击本机小窗可再手动微调 90°。

## 7. 儿童模式安全边界

- UI：儿童模式首页不渲染设置、添加、编辑、任意地址输入；只渲染联系人。
- Service：`callContact` 再次检查 `Prefs.isApprovedContact`；空联系人列表直接拒绝。
- 通话 UI：`CallActivity` 在 `childMode` 下隐藏顶部栏、控制栏、本机小窗、统计按钮与状态栏/导航栏；返回键无效。
- 通知：儿童模式进行中的通话通知不添加挂断 Action。
- 仅允许在 `DIALING`（尚未接通）时显示“取消呼叫”，接通后全部按键消失。
- 家长设置入口需要 PIN；PIN 默认值 `1234`，应用提示用户修改。

## 8. 锁屏来电

- `CallService` 是高优先级前台服务，锁屏时仍能收到 TCP 来电。
- 来电通知使用 `CATEGORY_CALL`、高重要性和 `fullScreenIntent`；Android 14+ 需要用户在系统设置中允许“全屏通知”，应用首次启动会引导，设置页也可再次打开。
- `IncomingCallActivity`：`showWhenLocked`、`turnScreenOn`、`FLAG_KEEP_SCREEN_ON`，并持有短时 `FULL_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP`，确保锁屏/息屏时能显示接听按钮。
- 通知“接听”是 Activity PendingIntent（不是 Service action），点击后在可见的 IncomingCallActivity 中自动接听，再启动 `CallActivity`；后者同样 `showWhenLocked`，因此接听后视频界面覆盖锁屏。
