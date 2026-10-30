# Mac 应用

AppKit 应用，最低 macOS 14。打开 `SoundLink.xcodeproj`，选择 **Sound Link** scheme 和 My Mac，使用默认 Debug 配置即可本机构建。工程没有预设个人 Team，不需要 Developer ID 证书。

从仓库根目录执行 `./scripts/build-macos.sh`，产物为 `build/macos/Build/Products/Debug/Sound Link.app`。`swift test --package-path apps/macos` 测试核心逻辑；直接 `swift run` 不具备完整 App 的权限文案和资源，应使用 Xcode App bundle 运行。

开发构建标识为 `app.soundlink.mac.dev`，状态存于 `~/Library/Application Support/Sound Link Dev/`，Keychain 身份使用该 bundle ID 的独立命名。默认关闭在线更新与下载二维码。安装配套 Android 开发包后，使用配对二维码建立连接。

- `Sources/SoundLink`：界面、蓝牙、媒体观察、耳机交接与诊断。
- `Sources/SoundLinkCore`：身份、协议、状态机与本地 TLS 服务。
- `Tests/SoundLinkCoreTests`：状态转换、协议和安全边界。
- `version.json`：开发版本元数据。

首次解析 Sparkle 依赖需要网络。Sparkle 只在显式配置更新 feed 与验签公钥时启动。运行时不依赖源码目录、Python、ADB 或外部服务器。
