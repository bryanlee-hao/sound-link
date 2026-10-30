# 交接事务 v1 · 当前 Sound Link 0.4.10

沿用扫描固定身份的 TLS、短期认证 token、nonce/epoch 心跳。不新增未认证控制接口。每轮 ping 可以上报 `handoff: {ready, revision, result?}`，响应包含 `handoff: {enabled, phoneReady, status, history, command?}`。

command 字段：`id` 随机事务 ID；`epoch` 当前已验证会话；`action` 为 connect/pause；`revision` 当前目标版本；`address` 手机目标地址；`instance/event` 起播或旧播放观察标识；`ttlMs` 服务端单调剩余预算。客户端从 TTL 扣除整个 RTT 和排队时间，取有效预算，重新校验当前本机授权/目标/实例/事件/音频保护；消费 ID:action 必须先持久化再调用硬件。执行没有秘密、路径、任意脚本或任意播放器参数。

result 字段：相同 `id/revision`，`stage` 为 connected / failed / pause_handled，`message` 可读详情，`routeVerified` 仅当目标 profile 和媒体预期路由吻合为 true。pause_handled 包含明确 pause 已发送的尽力处理结果，不能作为播放器暂停确认。服务端只接收当前事务、未过期、相同 epoch 的结果，connected 还需手机当前观察确认为目标已连接。

事务从 Mac 创建起共 5 秒，含连接、路由验证与尽力暂停；手机仅接受 0 < ttlMs <= 5000。成功连接后的暂停只使用剩余预算。Mac 本机接管无需先向手机请求释放或暂停；手机接管请求利用起播后的下一次有效心跳下发。超时、重复、目标变化、保护音频等不会自动重试，成功后无固定冷却；在线失败退避 10 秒，失联发起的 Mac 事务失败后另加会话级阻止。冷却期间的新事件不排队。

当前无离线下发手机命令，也无退出归还指令。Mac 可凭保留的使用会话在手机状态过期时本地接管；这不是给手机的离线命令。失联发起事务不补发旧端暂停。

## 方向性保护（2026-10-27）

connect 在目标端检查 `audioReadable`、新播放事件和媒体资格，目标端的已确认输入不再直接否决接管；来源端仍受 `protectedAudio` 保护。pause 始终检查被暂停来源端的保护状态。状态不可读、会话失效、旧事件、过期与取消仍拒绝执行；Mac 已提交连接后的只读锁定仍不可恢复。手动切换是本机明确意图，不复用自动候选或下发远端暂停。

## 历史演进（当前期限和恢复规则以上下文 0.4.9 说明为准）

0.4.2：状态边沿/交接结果立即触发串行 ping，3 秒周期仅兜底。result 新增 edgeToRequestMs 和 requestToResultMs，均为手机自身单调时钟间隔，缺失以 -1 表示。连接请求已经提交后只读验证不受瞬时媒体配置变化直接取消；相同目标/实例/期限仍必须成立，原事件或保护状态变化时不执行旧端暂停。


## 0.4.5 阶段耗时与只读验证

手机结果增加 `timingsMs`（可选字典）及 `verificationPollMs`（250）。字典按同一手机单调时钟记录相对请求计时起点的首次观察：`connect_requested`、`connect_api_returned`、`acl_connected_broadcast`、`profile_connected_observed`、`media_profile_connected_observed`、`audio_output_observed`、`media_route_observed`、`route_verified`。已有状态使用 `*_preexisting` 标记；未观察到的阶段缺失，不作 0 ms。ACL 广播只作为时序线索，不作为成功证明。最终结果携带全部已记录阶段，经原认证心跳保存到 Mac `phone_handoff_report`。

Mac 本地 `mac_handoff_timing` 包含同一事务 ID、stage、sinceStartMs、pollIntervalMs，阶段为 executor_started（含 edgeToExecutorMs）、connect_requested、connect_api_returned、bluetooth_link_observed、audio_output_observed、route_set_requested、route_set_returned、route_verified、read_only、finished/verification_closed；被生命周期取消时还可能出现 verification_stopped。每个阶段只记录一次。API 返回状态单独记录。没有设置路由的事务不会伪造路由设置阶段。

时间是本端首次被观察到的时间，轮询会带来约 250 ms 的正常粒度并可能受调度拖延，不是硬件精确发生时间。输出设备出现、媒体预期/默认路由成功和实际首声是不同证据；仍需用户听感。跨端 wall clock 不能相减。

Mac 在请求提交后出现保护或意图变化时，继续有界只读验证，不再修改路由或暂停旧端；只读锁定不可恢复。本轮取消/超时、换目标/实例/会话仍终止验证。路由已由系统切好时可以确认成功，同时明确跳过旧端暂停。


0.4.6：成功后的新起播无固定冷却，失败后 10 秒退避；交接中事件水位防止补做，路由确认后的可选暂停可被新的有效反向意图取代。两端新增本地短时静音保护（5 秒），不新增远端任意音量控制协议。Android `mute_guard` 和 Mac `mac_mute_guard` 记录应用/恢复结果；Android 媒体流静音不等于旧 demo 的单音轨增益。协议路由确认仍不证明已经听到声音，静音恢复日志也需单独核对。


## 当前恢复与失败阻止

恢复快照 stateSync 只更新状态、建立基线，不生成补做的起播事件。Mac 使用会话无固定时间到期，失联失败阻止通过新的连接确认解除；手机证据须回传本次失败后的 usageRecoveryToken，字段见观察协议。长时间失联不必然触发认证更新：有效 token 可复用，无效/到期/失败后重新认证；不放宽 nonce、epoch 和动作 TTL 校验。

## 0.4.10 手机独立执行

请求 handoff 增加 `localTakeover: {active, blocked, status, recoveryToken}` 与 `localRecoveryToken`。Mac 只在手机控制授权/目标匹配且观察新鲜时接受 active 抑制协调；active 报告会取消当前协调事务、消费已见事件。手机本地事务在内存中创建 `phone-local-*` 标识，复用 5000 ms 的连接/路由及静音保护，不需要远端命令，不回传普通远程结果，也不补发暂停。失败标记持久化于硬件动作之前；诊断记录 phone_local_begin、phone_local_result 和原有阶段耗时。退出/取消也保留失败锁，不能靠 App 重启重试。

认证响应 handoff.phoneLocalGeneration 回显请求观察样本的 localControlGeneration；手机执行命令前必须与当前本地代次一致，防止独立接管前发出的在途响应在接管完成后执行。Mac 收到代次变化也取消旧事务，即使没有收到中间 active 报告。

### 0.5.0 展示字段

Mac 的 `handoff` 响应增加可选 `phase` 与 `lastOutcome`，仅供产品界面展示，不作为授权或命令依据。`lastOutcome` 为 `""`、`"succeeded"` 或 `"failed"`；路由确认后，即使旧播放器暂停失败也属于成功。新事务和取消协调会清除旧结果。旧客户端可忽略新增字段；新客户端收到旧 Mac 响应时使用原有连接证据。
