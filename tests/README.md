# 跨模块测试

`python3 -m unittest discover -s tests -v` 验证构建元数据与默认配置隔离。

Swift 与 Android 测试分别位于 `apps/macos/Tests` 和 `apps/android/app/src/test`。仪器测试见 Android 工程说明。实机蓝牙、音频路由、锁屏与耗电需要独立验收。
