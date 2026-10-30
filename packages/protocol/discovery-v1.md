# BLE 地址发现协议 v1（0.2.0）

本协议只发现并认证 Mac 的局域网入口，不传音频、不授予耳机控制权限。TLS 配对继续使用 [pairing-v1](pairing-v1.md)。0.1.x 的持久身份与配对无需迁移或重新扫码。

## 广播与 GATT

每台 Mac 的服务 UUID 由 `SHA-256(UTF8("SoundLink/discovery/v1\n") + UTF8(pin))` 前 16 字节生成，设置 UUID version/variant 位为 5 / RFC4122。这里是项目确定性映射，不是 RFC4122 SHA-1 UUIDv5 命名算法。`pin` 为已保存的 SPKI SHA-256 小写 hex。两端使用同一 [公开测试向量](fixtures/discovery-v1.json)。不能以广播名字或蓝牙 MAC 地址建立信任。

广播请求名称为 `Sound Link 四位数`，数字从安装身份导出并稳定保留；系统可能合并或缓存名称。服务提供：

- challenge `27491e7a-7f1c-4e92-a002-534c00000001`：write with response，手机写入本次 16 字节随机 nonce。
- endpoint `27491e7a-7f1c-4e92-a002-534c00000002`：动态 read，读取该 central 对应的签名地址快照。

Mac 收到 challenge 时生成不可变快照，保存 15 秒；最多 16 个 central。读取处理 ATT offset，不在分段读取之间重新签名或换地址。手机发出 challenge 后十秒内必须完成读取/校验。请求 MTU 247 只是优化，超时会继续服务发现；地址包最大 183 字节，可通过 ATT long read 读取。读取后关闭短期 GATT 连接。

## 二进制格式

按网络字节序；所有字段都属于签名 body：

| 字段 | 字节数 | 约束 |
|---|---:|---|
| version | 1 | 1 |
| available | 1 | 0 / 1 |
| endpoint_revision | 8 | 当前进程内地址/可用状态版本；不是跨进程安全序号 |
| nonce | 16 | 本次手机挑战，必须完全匹配 |
| public_key | 65 | P-256 X9.63 uncompressed，首字节 0x04 |
| TLS port | 2 | available=1 时非零 |
| address count | 1 | 0–4；available=1 时至少一个 |
| IPv4 candidates | 4 × count | RFC1918，优先 UI 选定网卡，再提供其他本机候选 |

body 后紧跟一字节 DER 签名长度和 ECDSA DER 签名。签名算法 P-256 / SHA-256，签名输入为 `UTF8("SoundLink/discovery/v1\n") + body`。不复用配对请求的签名域。

手机将 X9.63 公钥重建为标准 SPKI DER，先核对已配对 pin，再验签、nonce、版本、长度、地址范围。拒绝额外尾字节、截断、错误签名、其他 Mac 的公钥、旧挑战以及公网/回环地址。校验通过仅允许尝试候选地址；**只有新地址的固定公钥 TLS 和手机签名认证也成功后，才保存新地址**。

描述不是秘密，附近设备可读取；不包含邀请 secret、长期私钥或会话 token。签名防伪，不能阻止无线干扰或阻止中继真实广播；TLS 身份仍是最终连接边界。

## Android 系统入口

首次用户点“启用自动发现与恢复”，以服务 UUID 过滤系统伴生关联列表。保存 association ID 与已配对 pin 的对应关系。关联只是后台授权，GATT 签名及 TLS 仍会重新验证。

- CompanionDeviceService + `startObservingDevicePresence(associationId)`：系统设备在场回调。
- BluetoothLeScanner PendingIntent + UUID filter + FIRST_MATCH / MATCH_LOST：补充随机蓝牙地址变化时的广播发现；不按每个广播包拉起 App。
- 伴生服务的网络回调：局域网恢复时重新连接。
- 开机、应用覆盖升级、蓝牙重新打开：重新登记已有、仍启用的观察入口。

只有用户已启用且系统关联仍有效时，后台事件才能申请启动 connectedDevice 前台服务。系统拒绝时记录原因并提示，不绕过后台限制、不发后台 Activity。手动暂停取消扫描/在场观察并停止服务，进程重建仍保持暂停；解除配对只清除此产品记录的 Mac 关联。

前台服务在连接失败时按 3/6/12/24/30 秒上限退避；连续失败超过 75 秒后的下一轮失败会结束本轮服务，仅保留系统发现入口，不无限主动扫描。一次主动 BLE 尝试最多 25 秒。手机全程息屏及进程被回收后的事件送达属于机型实测门槛。

## Mac 生命周期

IP/网卡变化会更新后续 challenge 的响应。正常暂停或系统睡眠清空会话并停止广告；恢复发布广告，旧 token 不会恢复效力。退出 App 关闭 HTTP/TLS 与广播。当前没有耳机交回动作，不能以这些连接事件替代后续 P4 的交接验收。

## 官方约束

- [Android 后台 BLE](https://developer.android.com/develop/connectivity/bluetooth/ble/background)：PendingIntent、CompanionDeviceService 与后台前台服务限制。
- [设备在场观察](https://developer.android.com/reference/android/companion/CompanionDeviceManager)：关联 ID 的 BLE 观察仍依赖设备地址；随机地址有局限。
- [ObservingDevicePresenceRequest.Builder](https://developer.android.com/reference/android/companion/ObservingDevicePresenceRequest.Builder)：setUuid 路径有汽车投屏 profile/额外权限约束，本产品没有使用该特权路径。
- [CoreBluetooth 广播](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:))：名称与服务 UUID，空间有限、尽力广播；不能保证与其他 App 共存的所有结果。
