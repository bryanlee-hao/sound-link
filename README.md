# Sound Link

让蓝牙耳机在 Mac 与 Android 之间随媒体播放切换。两端通过二维码建立身份信任，在局域网协调连接，并通过蓝牙发现恢复地址。

这是应用源码与开发仓库，包含 Mac AppKit 应用、Android Jetpack Compose 应用、协议、资源与测试。采用 [MIT 许可证](LICENSE)；第三方许可见 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)。

## 本地构建

需要 macOS、完整 Xcode、JDK 21、Android SDK 37、Python 3 和网络连接（首次下载依赖）。

```sh
./scripts/build.sh
```

脚本运行 Android 单元测试与 lint、构建开发 APK、运行 Swift 测试并生成 Mac App。无需网站、部署目录、发行证书、私有密钥或另一个仓库。

- Mac：`build/macos/Build/Products/Debug/Sound Link.app`
- Android：`apps/android/app/build/outputs/apk/debug/app-debug.apk`

开发包使用 `app.soundlink.mac.dev` / `app.soundlink.android.dev`，与其他安装身份分开存储。Mac 采用本机 ad-hoc 签名，Android 使用 SDK 开发签名。默认不连接在线更新服务；请分别安装两端开发包，再扫码配对。

只开发 Mac 时可打开 [Xcode 工程](apps/macos/SoundLink.xcodeproj)，选择 **Sound Link** scheme；也可运行 `./scripts/build-macos.sh`。

## 文档

- [开发与测试](scripts/README.md)
- [Mac 工程](apps/macos/README.md) · [Android 工程](apps/android/README.md)
- [产品范围](docs/product.md) · [技术架构](docs/architecture.md)
- [发现与配对](docs/discovery-and-pairing.md) · [耳机流转规则](docs/coordination.md)
- [协议](packages/protocol/README.md) · [验证边界](docs/validation.md)
- [后续工作](docs/implementation-plan.md)

## 当前边界

最低 macOS 14、Android 17（API 37）。实机结果主要来自有限的 AirPods / Mac / Android 组合，不代表所有耳机与厂商系统均通过。锁屏恢复、深睡、耗电、播放来源识别和 Mac 正常退出归还仍需持续实机验证。

应用不需要 Root、ADB 或 Shizuku 才能运行；开发测试工具与运行权限是不同的要求。双端切换需要用户授予相关蓝牙、网络和伴生权限。
