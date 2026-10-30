# Sound Link 技术架构

当前实现范围：0.4.10 已接入原生安装/配对/认证心跳、BLE/GATT 发现、播放观察、双向实际交接、短时静音、按使用会话的 Mac 失联接管和失败阻止。普通交接共 5 秒，成功后暂停旧端尽力而为。手机独立失联接管已接入，正常退出主动归还仍未实现；持续息屏可靠性以[验证记录](validation.md)为准。

精确已实现接口见[配对协议](../packages/protocol/pairing-v1.md)、[发现协议](../packages/protocol/discovery-v1.md)、[观察协议](../packages/protocol/observation-v1.md)和[交接协议](../packages/protocol/handoff-v1.md)。完整协议草案不代表已实现接口。

## 运行形态

```text
Mac Sound Link.app                          Android Sound Link APK
  UI 与配对二维码                             UI 与 App 内扫码
  BLE 广播 / GATT 地址描述  ← 蓝牙发现与读取 →  CDM / BLE 发现
  内置 TLS 通信服务        ← 局域网认证连接 →  连接与会话管理
  起播事件与交接决策                            起播事件与前台服务
  Mac 蓝牙与音频适配                           Android 蓝牙与播放适配
             └──── 系统蓝牙音频 ── 目标耳机 ── 系统蓝牙音频 ────┘
```

BLE 是发现和恢复的入口，局域网是首期控制通道，音频不经过 Sound Link。纯 BLE 传递全部协调消息可作为独立实验，不纳入本版架构承诺。

Mac App 内原生服务随应用启动、停止；首次产品实现不保留独立 Python 服务，也不依赖外部解释器。用户关闭窗口后菜单栏仍可运行，退出 App 则结束协调、广播与监听端口。登录启动为用户选择，首期不安装额外常驻 launchd 脚本。

## 技术基线与模块

Mac 采用 Swift、原生 UI、CoreBluetooth、Network.framework、Keychain 和平台蓝牙/音频接口。Android 当前采用 Java、Gradle 工程、原生权限流程、Camera2 + ZXing 扫码、CDM、Bluetooth API 与 connectedDevice 前台服务。扫码库和构建依赖需锁定版本、许可证与离线构建能力；此文不预选未经核查的第三方库版本。

当前通信为周期 HTTPS POST 请求/响应，尚无主动下行通道，避免把改成 WebSocket 当作后台可靠性改进。使用同一消息契约，未来替换为 WSS 不改变状态机。真正约束行为的是认证、双向确认、租约和动作入口检查，不是 socket 是否显示 connected。

| 归属 | 模块 | 责任 |
|---|---|---|
| apps/macos | App / Setup | 菜单栏、主界面、下载与配对二维码、权限与诊断 |
| apps/macos | Identity / Pairing | 钥匙串身份、邀请、确认绑定、撤销与迁移 |
| apps/macos | Discovery | 稳定广播、GATT 动态描述、网络接口变化与可选 Bonjour |
| apps/macos | Transport | 应用内 TLS listener、有限连接、消息校验与认证 |
| apps/macos | Coordinator | 新事件决策、直接接管事务、会话与目标版本、退出窗口 |
| apps/macos | Platform | 电源事件、耳机状态/连接、输出路由、播放候选事件 |
| apps/android | Setup / Identity | 扫码、系统授权、Keystore、电脑与目标设置 |
| apps/android | Discovery / Transport | CDM、过滤扫描、GATT 读取、网络发现和认证重连 |
| apps/android | CoordinationService | 前台通知、有效会话播放观察、命令执行与退出 |
| apps/android | Platform | 蓝牙 profile 观察、公开连接请求、播放分类输入 |
| packages/protocol | 契约与样例 | 版本、消息、错误、时效、幂等及跨语言一致性 |
| tests | 跨模块验收 | 状态机、时序、故障、双端集成及真机矩阵 |
| scripts | 开发工具 | 本机构建、资源生成与测试 |

平台适配输出观测并执行经过授权的单次动作，Mac HandoffEngine 与手机 OfflinePhonePolicy 分别承担在线协调和本地失联决策；状态机不调用网络、Bluetooth 或 UI。先写纯状态转换及可注入时钟，再连接平台。这使未知、过期、重复和退出并发可以确定性验证。

## Android 能力边界

首期 minSdk/targetSdk 以 37 为实现基线。公开 `BluetoothDevice.connect/disconnect` 需要附近设备权限及适用的伴生关联；异步请求必须继续观察 profile 结果，不能将返回码当作音频成功。[BluetoothDevice](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#connect())。

针对首期直接、持续的局域网通信声明并按需请求 `ACCESS_LOCAL_NETWORK`，处理拒绝和撤销；扫码 GATT 读取不代替局域网授权。系统服务选择器可减少部分网络权限，但不作为本方案已经免除权限的依据。[Android 局域网权限](https://developer.android.com/privacy-and-security/local-network-permission)。

本机只读播放观察与 10 秒心跳新鲜度分离；网络重试用尽后，有授权且有使用会话/失败锁时继续观察；手机用户停止/撤权仍停止。UI、通知、快捷设置和小组件都是状态/入口，不能保证进程常驻。伴生服务和过滤扫描是系统恢复途径，不承诺绕过强行停止。省电策略、用户撤权、进程重建和手机重启是不同测试维度。

声明范围按功能拆分：附近设备与网络为连接需要；通知为后台状态；相机只在扫码时；安装来源授权只在选择 App 内升级时。默认不申请麦克风、无障碍、通知读取、Root 或调试权限。厂商自启动/电池设置若确有需要，必须在兼容性结果中明确，不能算默认环境通过。

## Mac 能力边界

耳机连接由 IOBluetooth/公开平台接口适配，音频路由用 CoreAudio 实际结果确认；蓝牙 ACL 已连接不等于音频 profile 或默认输出已就绪。将旧 helper 的可用逻辑收进原生模块，或仅在确有隔离需求时使用随 App 一起签名的 helper，不能使用仓库绝对路径。

电源监听为正常退出提供尽力而为的窗口；真实 sleep、clamshell、关机、崩溃与用户暂停要分别记录。不能把菜单模拟暂停视为真实睡眠验收。公开媒体观察能否覆盖目标播放器、会议和网页是单独的发布门槛。

## 存储与更新

Mac 非敏感状态进入 Application Support，身份密钥进入 Keychain，缓存进入系统 Cache。Android 当前使用私有 SharedPreferences 与 Keystore，后台服务不能依赖 Activity 字段。目标更换和配对撤销采用可恢复的持久事务，内存重启后先握手再执行动作。

配置不含本机开发路径；APK 不含用户 IP、耳机地址、测试证书或长期共享 token。发行签名属于产品构建系统，与每次安装生成的设备身份分开。调试包与正式包采用明确身份隔离，旧 Demo 默认不自动导入配对密钥。

应用更新保持安装身份和存储结构兼容；配对记录迁移需要版本化和失败恢复。两端协议不兼容时只允许更新/诊断，不继续执行旧命令。下载渠道和控制会话具有独立权限。

## 可观测性

两端以统一事件 ID 记录握手、唤醒来源、进程实例、租约、耳机状态、事务阶段及拒绝原因。默认日志有容量上限，不记录二维码 secret、令牌、私钥或音频内容；导出时遮蔽局域网地址和设备标识。报告记录单调时间与本机 wall clock，跨机分析依赖关联 ID 和时钟映射，不能直接相减系统时间推导延迟。

诊断区分 scan registered / advertisement received / GATT read / TLS authenticated / lease valid / profile connected / route verified，任何一个前置步骤都不能代替下一步的成功。

2026-10-15：流转改为新起播端连接优先，旧端暂停 best-effort，不把实时手机推送当作必要先决条件。当时 0.3.3 仅实现观察侧调整，控制、幂等暂停和双端同时起播仲裁仍待实现。以 [流转规则](coordination.md) 为准。

0.4.0：新增 SoundLinkCore/HandoffEngine、MacHandoff、Android HeadphoneControl/HandoffCommand/PhonePause。沿用 HTTPS 心跳承载有限交接命令与结果，协议见 `packages/protocol/handoff-v1.md`。观察模型与执行策略分离；来源暂停不会成为连接前置。这是 0.4.0 的历史边界；0.4.9 已支持按使用会话的 Mac 失联接管，双端同时新起播和无法满足保护条件的场景仍拒绝执行。


0.4.9 当前认证恢复：长调度空白触发重新采样和 stateSync 基线，但不强制废弃仍有效的认证 token。服务端 900 秒有效，客户端按 840 秒提前更新；ping 异常清空 token，下次重新挑战认证。每次响应验证 nonce/epoch；控制命令单独检查最多 5000 ms 的剩余 TTL。0.4.10 已接入 OfflinePhonePolicy；网络轮询可暂停，本地观察独立保留，失败锁先持久化再连接，恢复同步代次避免重放。具体生命周期与进程重建限制见流转规则。
