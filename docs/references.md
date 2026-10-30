# 平台依据与查证入口

查证日期：2026-10-14。下列链接支撑平台接口及约束，不替代目标 ROM、设备和发行包的实测。产品规则来自用户确认的设计目标，时间预算属于工程起点。

| 主题 | 官方文档 | 用于确认 |
|---|---|---|
| Android 耳机连接 | [BluetoothDevice](https://developer.android.com/reference/android/bluetooth/BluetoothDevice#connect()) | API 37、伴生/权限条件、异步 profile 结果 |
| 系统伴生授权 | [Companion device pairing](https://developer.android.com/develop/connectivity/bluetooth/companion-device-pairing) | 系统用户确认、关联与应用能力 |
| 后台蓝牙 | [Communicate in the background](https://developer.android.com/develop/connectivity/bluetooth/ble/background) | PendingIntent、伴生服务、随机地址限制 |
| 前台服务 | [Restrictions on starting a foreground service](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) | 后台启动约束，不能把通知当永不被杀保证 |
| 局域网 | [Local network permission](https://developer.android.com/privacy-and-security/local-network-permission) | target 37 的直接网络访问权限、拒绝处理 |
| 播放观察 | [AudioManager](https://developer.android.com/reference/android/media/AudioManager) | 活跃播放配置与回调，非全局声音拦截 |
| Mac 广播 | [startAdvertising](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanager/startadvertising(_:)) | 名称与服务 UUID，非任意广告载荷 |
| GATT 读取 | [didReceiveRead](https://developer.apple.com/documentation/corebluetooth/cbperipheralmanagerdelegate/peripheralmanager(_:didreceiveread:)) | 动态特征值读取响应 |
| APK 签名 | [Sign your app](https://developer.android.com/studio/publish/app-signing) | 安装身份与升级签名连续性 |

实现时应再次核对对应 SDK、entitlement、发行渠道和 API 可用性，固定构建版本并将实测记录写入 [验证文档](validation.md)。
