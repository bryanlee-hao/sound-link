# Sound Link

[English](README.md) | **简体中文**

**让蓝牙耳机随播放，在 Mac 与 Android 之间切换。**

Sound Link 帮助你在电脑和手机之间共用一副蓝牙耳机。当另一端开始播放时，应用根据耳机状态和系统授权协调连接，减少手动打开蓝牙设置的次数。

两端通过扫码配对，在局域网内交换协调状态，音频仍由设备直接通过蓝牙播放。

**[官网与下载](https://gkagent.cn/sound-link/) · [反馈问题](https://github.com/bryanlee-hao/sound-link/issues) · [MIT License](LICENSE)**

> 项目仍在持续开发和实机验证中。目前主要围绕 AirPods、Mac 和 Android 手机测试，具体限制见下文。

## 功能

- **双向自动切换**：根据新的播放事件，将耳机连接切换到正在使用的设备。
- **扫码配对**：Android 扫描 Mac 中的配对码，建立持久的设备信任。
- **蓝牙辅助重连**：通过蓝牙发现已配对的 Mac，帮助恢复局域网连接。
- **播放交接**：切换成功后，在权限与播放器能力允许时，尝试暂停原设备的播放。
- **手动控制**：可暂停自动切换，也可从 Mac 菜单栏手动将耳机切换到电脑。
- **状态与诊断**：查看设备连接状态，导出诊断日志，方便排查问题。
- **中英文界面**：支持简体中文、英文，以及跟随系统的浅色和深色外观。

日常使用不需要 Root、ADB 或 Shizuku，也不需要将音频传到云端。

## 系统要求

| 项目 | 要求 |
|---|---|
| Mac | macOS 14 或更新版本 |
| Android | Android 17 或更新版本，API 37 |
| 耳机 | 已分别与 Mac、Android 完成系统蓝牙配对 |
| 网络 | 两端局域网可互通，蓝牙保持开启 |

目前以 AirPods 为主要验证对象，其他蓝牙耳机需要逐型号验证。暂不支持 Windows 和 iPhone。

连接同一个 Wi-Fi 通常便于使用，但访客网络、设备隔离或防火墙仍可能阻止两端通信。

## 开始使用

### 1. 安装两端应用

前往 **[Sound Link 官网](https://gkagent.cn/sound-link/)**，查看项目介绍并获取当前可用的 Mac 和 Android 安装包。

- **Mac**：打开下载的 DMG，将 Sound Link 拖入“应用程序”。
- **Android**：安装 APK，按系统提示允许对应来源安装应用。

也可以按照下方步骤自行构建。安装包的公证状态和首次打开说明见官网下载区。

### 2. 配对电脑和手机

1. 在 Mac 和 Android 上打开 Sound Link。
2. 在 Android 中选择“扫描配对码”，扫描 Mac 显示的配对二维码。
3. 按应用引导完成蓝牙、局域网、相机、通知和伴生设备等相关授权。

配对二维码用于建立设备信任，请勿公开分享。

### 3. 选择耳机

先在两端的系统蓝牙设置中配对同一副耳机，再在 Sound Link 中选择并确认目标耳机，完成手机端的耳机控制授权。

首次连接仍需由系统或用户完成。两端都没有连接耳机时，应用不会仅因为出现播放就主动连接。

### 4. 开始播放

确认两端连接状态正常、自动切换已开启，然后在电脑或手机上开始播放。

关闭 Mac 窗口后，应用会继续在菜单栏运行。可以随时从应用或菜单栏暂停自动切换。

## 从源码构建

### 准备环境

在 Mac 上构建完整项目需要：

- 完整 Xcode，并选为当前开发工具。
- JDK 21。
- Android SDK Platform 37，以及当前工程要求的 Build Tools 36.0.0。
- Python 3。
- 网络连接，用于首次下载 Gradle、SwiftPM 等依赖。

无需单独安装 Gradle，仓库已包含 Gradle Wrapper。非默认安装位置可通过 `JAVA_HOME` 和 `ANDROID_HOME` 指定。

### 获取源码并构建

```bash
git clone https://github.com/bryanlee-hao/sound-link.git
cd sound-link

./scripts/build.sh
```

脚本会执行 Android 单元测试和 lint、构建开发 APK、运行 Swift 测试，并生成完整的 Mac App。

构建产物：

```text
Mac
build/macos/Build/Products/Debug/Sound Link.app

Android
apps/android/app/build/outputs/apk/debug/app-debug.apk
```

分别安装两端开发包，再按上面的步骤扫码配对。

开发包使用独立的应用标识和数据存储，可与官网版本并存；配对和设置不会自动继承。默认不配置在线下载或更新服务。

### 单独构建

仅构建 Mac：

```bash
./scripts/build-macos.sh
```

也可以用 Xcode 打开 `apps/macos/SoundLink.xcodeproj`，选择 **Sound Link** scheme 和 **My Mac**。

仅构建 Android：

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"

./apps/android/gradlew -p apps/android assembleDebug
```

### 运行测试

```bash
# Mac 核心逻辑
swift test --package-path apps/macos

# Android 单元测试与静态检查
./apps/android/gradlew -p apps/android testDebugUnitTest lintDebug

# 构建元数据与配置隔离
python3 -m unittest discover -s tests -v
```

更详细的说明见[开发与测试文档](scripts/README.md)。

## 已知限制

- **设备兼容性仍在扩展。** 已有测试结果不代表所有耳机、播放器和 Android 厂商系统均可正常工作。
- **切换需要时间。** 蓝牙连接和音频路由切换存在延迟，不能保证无缝切换或完全没有短暂外放。
- **部分提示音可能触发切换。** Mac 当前的播放识别可能将通知或系统提示音视为新的播放事件。
- **原设备暂停属于尽力而为。** 是否能够暂停取决于系统权限和播放器能力，不能保证所有应用都响应。
- **后台恢复受系统影响。** 锁屏、深睡、省电策略、权限撤销和进程清理可能影响连接与响应；持续后台运行和耗电仍需更多实机验证。
- **Mac 退出不会主动归还耳机。** 休眠、暂停或退出会停止 Mac 端协调，但目前没有完整的主动归还流程。

自动化测试通过不等于真实设备上的全部场景都已通过。更多范围说明见[验证文档](docs/validation.md)。

## 隐私与连接

- 音频通过系统蓝牙直接播放，不经过 Sound Link 云端转发。
- 两端在局域网内交换协调状态，连接使用设备身份校验和加密通信。
- 设备身份密钥保存在系统 Keychain 或 Android Keystore 中。
- 分享诊断日志前，请检查并遮蔽个人设备名称、网络地址和其他个人信息。

## 项目结构

```text
apps/
  macos/       Mac 应用与核心逻辑
  android/     Android 应用
packages/
  protocol/    通信协议与测试样例
assets/        图标与品牌资源
scripts/       本地构建和资源生成
tests/         跨模块测试
docs/          架构、交互与验证说明
```

本仓库包含应用源码及独立开发构建所需内容。官网运维、官方签名、公证和部署配置单独维护，不是构建本项目的前置依赖。

## 反馈与贡献

欢迎通过 [Issues](https://github.com/bryanlee-hao/sound-link/issues) 报告问题或提出建议，也欢迎提交 Pull Request。

报告兼容性问题时，请尽量提供：

- Mac 型号、macOS 版本。
- 手机型号、Android 版本及厂商系统版本。
- 耳机型号。
- 应用版本、复现步骤、预期与实际行为。
- 问题发生时是否锁屏、省电或切换过网络。

请勿提交配对二维码、私钥、签名文件或未经检查的原始诊断数据。

## 许可证

Sound Link 使用 [MIT 许可证](LICENSE)。

第三方依赖的许可与声明见 [THIRD_PARTY_NOTICES.txt](THIRD_PARTY_NOTICES.txt)。
