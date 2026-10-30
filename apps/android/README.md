# Android 应用

Jetpack Compose 应用，最低 Android 17（API 37）。需要 JDK 21 与 Android SDK 37。

从仓库根目录执行：

```sh
apps/android/gradlew -p apps/android assembleDebug testDebugUnitTest lintDebug
```

开发 APK：`apps/android/app/build/outputs/apk/debug/app-debug.apk`。默认包名 `app.soundlink.android.dev`，使用 SDK debug 签名，不读取任何发行 keystore。安装后授予蓝牙、局域网、相机、通知和伴生关联所需的权限，再扫描 Mac 开发包的配对码。

没有配置下载站点和更新源时，界面不提供在线更新；核心配对和耳机切换无需外部站点。

`src/test` 为本机单元测试；`src/androidTest` 为仪器与实机测试。仪器测试会操作界面、通知或应用状态，应使用专用测试设备。Gradle connected tests 可能重装应用，运行前确认测试设备和应用身份。

小米超级岛为可选能力：未配置或未获厂商授权时保留普通通知。通过 `SOUNDLINK_XIAOMI_APP_ID` 或 Gradle 属性 `soundlink.xiaomiAppId` 提供自己的 App ID；不在源码中保存厂商账号凭据。
