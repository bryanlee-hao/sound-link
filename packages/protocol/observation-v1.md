# 观察协议 v1（当前产品 0.6.5）

本文定义观察数据；实际控制另见 handoff-v1.md。历史 mode=observe_only 字段仍为兼容保留，不能用它判定当前自动切换是否关闭，须读取 handoff.enabled。下述信息通过既有配对身份认证的 TLS 传输。

## 目标耳机

任一端都可从本机系统已配对音频设备发起选择。Mac 仍负责持久化与串行裁决，`observation-target.json` 权限为 0600。原字段为 `revision`、`macAddress`、`macName`、`phoneAddress`、`phoneName`；0.6.5 增加可选 `pendingSide`（`mac` / `phone`）、`previousAddress` 和 `previousName`，旧文件无需迁移。

发起新选择时递增 revision，保存发起端地址，清空接收端地址并把其原选择记录在 previous 字段。`pendingSide` 未完成时不允许自动切换。对端仅在本机已配对列表精确匹配蓝牙地址，且此前未选设备或仍为该地址时自动确认；不按名称匹配。未找到或与此前选择冲突时显示确认，用户可以手工指定对应的本地设备。接收端确认后再次递增 revision，填入其本地地址并移除 pending / previous 字段。重复提议不会丢失原有冲突信息。

`POST /v1/observe/target` 携带认证 bearer 和 `{revision, address, name, mode}`。`mode=propose` 由手机发起新选择；`mode=confirm` 接受 Mac 的待确认选择，省略 mode 保持旧客户端 confirm 行为。Mac 发起和确认使用同一核心状态机。旧 revision、错误接收方向、非法地址/名称/模式被拒绝。旧弹窗不能覆盖后续选择；连接中断后用状态快照恢复，不盲目重放过时选择。

通过已认证 `/v1/events` 发送 `target_changed`，包括 pairing_id 和 observation；周期心跳作为丢失事件的回补。Android 对 snapshot 按 revision 单调更新，并验证本地配对 generation，拒绝旧心跳覆盖新选择。Compose 子页面直接订阅偏好变化，无需退出重进。实际连接仍受系统 CDM 授权约束；可复用已有目标耳机授权，但同步不会把用户暂停的自动切换重新开启。

## 音频可读性与方向保护（2026-10-27）

样本增加可选布尔值 `audioReadable`。false 表示音频状态不可确认，不能授权自动接管；true 与 `protectedAudio=true` 可以同时出现，表示状态明确且有受保护输入/用途。保护只阻止从受保护来源端自动夺走耳机，不阻止有新播放事件的目标端接管。旧样本无此字段时，仅在播放状态已知且 `protectedAudio=false` 时视为可读。进程/PID/输入设备明细仅记在 Mac 本地诊断，不加入网络样本。

## 样本与新鲜度

`POST /v1/ping` 在原 nonce 字段外可增加 `observation`。样本字段：

| 字段 | 含义 |
|---|---|
| instance / event | 观察实例与新活动序号，不包含控制命令 |
| targetRevision | 此样本对应的目标版本 |
| playing | active / idle / unknown；仅代表系统可见活动 |
| playback | 用途或进程活动描述 |
| connection / connectionDetail | connected / disconnected / unknown，与判断依据 |
| route | 路由描述，明确区分默认/预期/活动进程输出 |
| protectedAudio | 当前端的输入、通信或未知用途保护信号，用于保护来源端占用，不是完整通话识别保证 |
| audioReadable | 可选；区分已确认输入与音频状态不可读，缺省时按旧保护信号保守判断 |
| media | Android 最近新事件是否含媒体/游戏用途；Mac 不把输出活动冒充媒体分类 |
| eventAgeMs（0.3.3 可选） | 本机观察新边沿到发送的单调年龄；仅供诊断；恢复快照不补认新事件 |
| ageMs | 本机采样至发送的单调时钟年龄 |

Android 以音频回调加约 1 秒采样刷新元数据，约 3 秒随认证心跳发送；0.3.3 起服务结束、明确暂停或有限失败恢复结束才撤销本地观察；单次心跳过期不撤销。Mac 约 0.2 秒采样。手机样本年龄为发送前年龄加 Mac 收到后的单调时间；超过 10 秒不作为当前状态；Mac 仍可按已建立的使用会话本地接管。响应携带目标、双端样本、判断标题/原因、最多 5 条判断记录。Android 仅在有效心跳 10 秒内展示实时建议，失联后旧记录只作为历史。

观察实例变化、目标变化、明确退出及未知基线先建立基线。当前短暂失联、重新认证或 stateSync 快照均建立基线，不重放恢复时已有播放；之后新的事件才参与判断。目标版本必须两端一致。

## 判断

按顺序检查：目标确认 → 双端新鲜 → 目标版本一致 → 连接/播放已知 → 耳机至少连一端且不是两端均连 → 来源端无受保护音频且两端音频可读 → 基线之后的新活动。

- 在线路径遇双断开、双连接、未知、重连已有播放、来源端输入/通话保护时不建议切换；手机状态过期另走 Mac 使用会话路径。
- 手机新媒体/游戏，耳机在 Mac：显示「候选建议：Mac → 手机」，并提示无法确认显式外放意图。
- Mac 新输出活动，耳机在手机：显示「候选建议：手机 → Mac」，并提示媒体/通知分类与路由意图未确认。
- 新活动发生在已连接耳机的一端：无需切换；同时出现双端新活动：观察版不决定抢占顺序。
- 候选最多保留 8 秒；活动停止、状态过期或条件失效后取消。当前与最近判断记录都可见。

## 能力边界

Mac 的 `kAudioProcessPropertyIsRunningOutput` 表示活跃输出流，不能证明实际有声，也不能完整区分音乐、通知和会议。`kAudioProcessPropertyDevices` 按输出 scope 读取活动进程设备；默认输出单独标示。IOBluetooth `isConnected` 是链路状态，不保证 A2DP 活跃路由。macOS 14.2 以下显示进程观察能力不足。

Android `AudioPlaybackConfiguration` 为普通应用提供匿名用途，不能依赖它识别播放器包名或精确的播放器替换。`getAudioDevicesForAttributes` 是媒体的预期路由，不能冒充每个其他 App 的实际路由。已废弃且未填充的 `getAudioDeviceInfo` 不使用。持续静音视频、同用途同计数的播放器替换、非常短的事件可能不可见。仅有 `isMusicActive` 而无用途配置时保守保护。

两端均不采集声音、申请麦克风录制权限、启用私有 API 或借助 ADB。候选建议不意味着已经达到自动切换发布门槛。

## 官方依据

- [Android AudioPlaybackConfiguration](https://developer.android.com/reference/android/media/AudioPlaybackConfiguration)
- [Android AudioManager](https://developer.android.com/reference/android/media/AudioManager)
- [Apple Core Audio process objects](https://developer.apple.com/documentation/coreaudio/kaudiohardwarepropertyprocessobjectlist)
- [Apple output activity property](https://developer.apple.com/documentation/coreaudio/kaudioprocesspropertyisrunningoutput)

## 历史演进（当前行为以 0.4.9 为准）

0.3.3 补充：手机过期不再否决所有 Mac 本地新起播候选。最多参考 120 秒内目标一致的手机连接记录，明确标为“待验证”，不能作为硬件动作授权。旧端普通音乐不阻止新端候选，展示接管后尽力暂停、失败不回滚的计划；本协议仍无执行命令。详见 [流转规则](../../docs/coordination.md)。


0.4.2 起 Android 播放与连接变化即时串行提交，3 秒心跳仍作兜底。Mac 0.4.3 约 0.2 秒采样，`media` 表示新输出活动持续约 0.6 秒后具备候选资格，并非媒体语义识别；不使用播放器名单。进程名/bundle ID 仅用于展示及可选暂停来源校验。长提示音、静音输出仍可能误判。观察协议本身不携带动作，实际执行见 [交接协议](handoff-v1.md)。

0.4.4：Mac `media` 改为新输出在首次采样即可具备候选资格，不再等待 0.6 秒。约 0.2 秒采样与其余规则保持不变，短提示音也可能触发。


## 0.4.9 使用会话与恢复字段

手机样本可携带 stateSync（恢复基线）与 usageRecoveryToken（采样时回传 Mac 最近下发的失败恢复标识）。Mac 响应 observation 增加 usageStatus、usageSessionActive、offlineRetryBlocked、offlineMacAllowed、offlineUseValid、usageRecoveryToken。phoneUseAgeSeconds 为兼容字段，表示最近任一端连接确认的年龄，仅用于诊断，无 300 秒上限。

新鲜目标连接建立使用会话；明确双断开或本地协调生命周期变化结束会话。Mac 失联事务失败后锁定后续独立尝试，恢复标识匹配且失败后重新采样的手机连接确认，或 Mac 新鲜本地连接确认可解锁。手机界面断联时只展示上次状态，不冒充实时。0.4.10 手机独立失联接管已接入。

## 0.4.10 本地事务同步

手机样本新增可选 `localControlGeneration`（最多 100 字符），独立接管开始/结束变更；Mac 将代次变化视为恢复基线，不重放该事务起播。认证响应 observation 新增 `phoneRecoveryToken`，回显请求 handoff.localRecoveryToken，且回显前必须重新采样 Mac。手机仅用匹配当前标识的新鲜目标连接确认解除失败锁；旧响应仍可展示，但不能解除。
