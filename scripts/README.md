# 开发与测试

所有命令在仓库根目录执行。完整 Xcode 需已选为当前开发工具；Android SDK 需要 platform 37，以及 Android Gradle Plugin 要求的 build-tools（当前为 36.0.0），JDK 21。可通过 `JAVA_HOME`、`ANDROID_HOME` 指定安装位置。

```sh
./scripts/build.sh
./scripts/build-macos.sh                         # 仅 Mac App
swift test --package-path apps/macos             # Swift 核心测试
apps/android/gradlew -p apps/android testDebugUnitTest lintDebug
python3 -m unittest discover -s tests -v         # 元数据与配置边界
```

`build.sh` 使用 Android Debug 与 Xcode Debug，生成可安装的开发包，不需要个人发行证书。Android Wrapper、SwiftPM 锁文件随源码保存。

`icons.swift` 从 `assets/brand/icon.json` 生成图标；`xcode-prepare.sh` 只准备 Mac 资源。`package.py` 生成 Info.plist 与权限本地化，版本来自 `apps/macos/version.json`。

## 可选构建配置

默认没有下载站点或更新源。Mac 可通过 `SOUNDLINK_BUILD_CONFIG` 指向本地 JSON，字段包括 `bundleIdentifier`、`applicationName`、`supportDirectory`、`websiteURL`、`updateBaseURL`、`feedURL`、`publicEDKey`。启用更新须同时提供 HTTPS feed 与验签公钥。`SOUNDLINK_VERSION_FILE` 可覆盖版本元数据来源。Xcode 的 bundle identifier 由 `PRODUCT_BUNDLE_IDENTIFIER` 管理。

Android 可使用 `SOUNDLINK_APPLICATION_ID`、`SOUNDLINK_APP_NAME`、`SOUNDLINK_WEBSITE_URL`、`SOUNDLINK_UPDATE_BASE_URL` 配置自己的构建；可选厂商 App ID 由 `SOUNDLINK_XIAOMI_APP_ID` 提供。默认不需要这些变量。

开发配置和诊断文件放在被忽略的 `.local/`；不要把密钥、日志或个人设备数据加入源码。自动测试不会代替真机蓝牙与声音路由验收。
