# 已实现：安装、配对与连接协议 v1

对应产品 0.1.0–0.1.1（2026-10-14），与 [后续协调协议草案](v1.md) 分开。本版没有耳机动作接口、WS、NDJSON 流或 BLE/GATT；用短 HTTPS 请求验证身份和往返心跳，不能把在线标记当作未来的硬件控制租约。

## 安装入口

安装配套的 Mac 和 Android 开发包后扫码配对。安装渠道与局域网身份协议无关，Mac 不提供旧版 APK 下载接口。

## 身份与配对码

Mac 首次运行创建 Keychain 永久 P-256 密钥与自签 TLS 证书。`pin` 是 DER SubjectPublicKeyInfo 的 SHA-256，小写十六进制；`id` 是 pin 前 32 字符。证书 SAN 为 `<id>.soundlink.local`，用途为 TLS serverAuth，当前有效期 10 年。Android 私钥在 AndroidKeyStore，安装 ID 为随机 UUID，不进入 APK。

二维码为 `soundlink://pair/` 加无 padding 的 base64url UTF-8 JSON：

```json
{"v":1,"id":"32位十六进制","pin":"64位十六进制","name":"Mac 名称","host":"局域网 IPv4","port":12345,"invite":"UUID","secret":"32随机字节的base64url"}
```

Mac 以本地时钟判断五分钟到期，邀请不依赖手机时间。刷新、绑定成功均使旧邀请不能新建绑定。已配对的相同请求可幂等查询结果，防止 Mac 配对后响应丢失导致手机无法保存配对。

当前仅接受 RFC1918 IPv4，Mac 可选 en/bridge 网络接口。不接受 URL、回环、公网地址、任意协议或自动跳转。配对后二维码改为相同身份与地址、`mode:"route"`，不含邀请。手机仅允许更新已保存 pin 相同的 Mac 地址；全新手机不能用 route 码建立绑定。自动跨 IP 发现属于 P2。

TLS 客户端严格检查扫描/已保存的 SPKI pin、证书自签名、有效期、serverAuth 和稳定 SAN。IP 仅用于路由，hostname verifier 校验固定身份，绝不采用无条件信任。Mac 最低 TLS 1.2。

## 签名信封

签名请求统一：`{"payload":"base64url(rawJSON)","signature":"base64url(DER ECDSA签名)"}`。

使用 P-256 ECDSA SHA-256，签名输入为 UTF-8 `SoundLink/1\n` + 原始 JSON 字节。接收端验证原始字节，不重新序列化 JSON。密钥编码为 base64url SPKI DER，签名为 DER（不是 raw r/s）。

### POST /v1/pair

签名 payload：`v=1, action=pair, client_id, name, public_key, request_id, invitation_id, secret`。

从 0.6.1 起，Mac 验证签名、未过期的一次性邀请和字段边界后直接原子落盘配对公钥/请求 ID，返回 `state=paired, client_id, pairing_id`，无需 Mac 二次确认。只有掌握二维码 secret 并通过签名校验的请求才能绑定；TLS 身份固定校验仍然保留。磁盘保存失败不得返回成功。

相同签名申请可幂等查询，即使邀请已消费、超时或 Mac 重启，也返回原配对结果。不同手机/新请求不能复用已消费邀请，已绑定 Mac 不允许静默替换手机。新绑定完成后两端默认开启自动切换并进入设置引导；幂等重试不能覆盖用户后续偏好。旧 Mac 仍可能返回 `pending`，Android 保留其兼容提示。

### POST /v1/challenge 与 POST /v1/session

已配对 client_id 请求 32 字节随机 nonce，30 秒有效、一次消费。session payload 为 `v=1, action=session, client_id, nonce`，必须由已保存手机公钥对应私钥签名。

返回随机 32 字节 token、新 epoch、`expires_in=900` 秒；token 只存在两端进程内存，不是永久凭据。Android 提前一分钟更新。Mac 最多保留八个并行会话（连接与更新可共存）；达到上限时淘汰最早会话。未消费 challenge 最多 32 个。

### POST /v1/ping

请求携带 `Authorization: Bearer <token>` 和随机 nonce。响应回显 nonce、epoch、Mac 收到心跳计数、state/name。成功 JSON 均带 server_id。Android 校验 server_id、nonce、epoch，丢弃超过八秒的响应。每轮完成后三秒再发起一次；UI 十秒无有效回应即显示离线。

Mac 的计数是收到的认证往返请求数，不是手机播放事件；两端不传输音频。Mac 休眠/唤醒、退出、撤销会清除内存会话；重新打开保持 Keychain 身份、配对和控制端口。App bundle 不写运行状态。

Android 当前用 connectedDevice 前台服务保持短请求。失败持续超过一分钟后停止，手动重连入口保留；没有实现 CDM 唤醒、开机恢复或长期锁屏承诺。用户“停止连接”后不自动拉起。

### GET /v1/release 与 GET /v1/apk

当前源码已移除旧安装包接口。安装渠道独立于配对协议，开发包默认不配置在线更新。

## 数据与边界

- Mac：Keychain 密钥；Application Support/<构建配置的状态目录>/peer.json（目录 0700、文件 0600）；偏好中的固定监听端口。磁盘写失败不能把配对显示为已完成。
- Android：私有 SharedPreferences 保存 pin/地址/公钥身份元数据；临时申请保存到结果确认后清除 secret；禁用云备份和设备迁移以避免复制没有私钥的配对。
- HTTP/1.1 每连接一请求；头 8 KiB、体 32 KiB、签名载荷 8 KiB、最大 32 连接、12 秒截止；拒绝重复请求头、Transfer-Encoding、流水线和超长输入。
- HTTPS 只有已知固定接口；HTTP 下载入口不接受任何控制请求；二维码和长期私钥不写日志。
- 仍需跨设备、真实息屏与长期稳定性验证。


## 0.6.0 生命周期扩展（仍兼容 v1）

- 配对成功及认证心跳响应增加 `pairing_id`，值为已配对的 `request_id`。手机保存在独立偏好中；旧配对升级后可从新 Mac 的心跳补齐。
- `POST /v1/unpair`：签名载荷 `v:1, action:unpair, client_id, pairing_id`。Mac 必须使用当前 peer 公钥验证，并检查当前配对 ID。此通知不依赖协调租约，暂停时也可接收；旧配对重放不得删除新配对。
- `POST /v1/events`：Bearer 认证，通过现有固定公钥 TLS 等待最多 20 秒；平时返回 `event:idle`，Mac 主动解除时对当前等待者仅发送一次 `event:unpaired, pairing_id, server_id`。无队列、无送达重试、无确认回执。没有当前等待者即跳过通知。
- 心跳 `supports_events:true` 表示支持该通道；手机只在收到能力声明后建立等待连接，暂停网络/停止服务时停止等待。消息不更新心跳，不授予耳机控制。手机以本机 pairing generation 和 pairing_id 双重检查迟到响应。
- 手机心跳 `recoveryReady` 表示 BLE 系统关联和自动恢复意愿已完成，仅用于 Mac 引导显示，不作为控制授权。
- 未绑定时每次邀请到期（300 秒）自动轮换；未过期的邀请保持稳定。绑定后立即消费邀请，不再生成新的配对邀请。

### 0.6.6 设备显示名称

Android 配对 payload 的 name 使用用户可见设备名称，并在认证的 POST /v1/ping 中发送可选 device_name。0.6.7 起优先使用已授权读取的蓝牙名称，系统设备名称仅作回退。Mac 仅在 token 与 nonce 均有效、名称非空且不超过 64 个字符、无控制字符时更新已配对 Peer 的 name，原子持久化并刷新界面；其余身份字段及会话不变。字段缺失或无效时保持旧名称，兼容旧客户端。
