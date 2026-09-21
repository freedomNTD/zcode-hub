# 第三方组件

本项目包含或依赖以下第三方代码，版权归各自作者所有：

| 组件 | 用途 | 许可 | 位置 |
| --- | --- | --- | --- |
| [jsQR](https://github.com/cozmo/jsQR) | 浏览器端二维码解码（网页面板用） | Apache-2.0 | `public/jsQR.js` |
| [qrcode-generator](https://github.com/kazuhikoarase/qrcode-generator) | 二维码生成（网页面板「本页二维码」用） | MIT | `public/qrcode.js` |
| [ZXing](https://github.com/zxing/zxing) core 3.5.3 | 安卓 App 二维码解码 | Apache-2.0 | 不随仓库提供，`android-app/build.mjs` 首次构建时自动从 Maven Central 下载 |

安卓 App 的构建不使用 Gradle/AndroidX，直接调用 Android SDK 的 aapt2 / d8 / zipalign / apksigner，
因此除 ZXing 外没有其他运行时依赖。

## 关于 ZCode

本项目是第三方工具，与 ZCode 官方无关。它通过 ZCode 客户端自带的「手机远控」配对二维码与其公网中继通信，
协议细节来自对客户端实现的阅读（见 README 的「协议笔记」）。ZCode 本身的开源代码在
[zai-org/ZCode](https://github.com/zai-org/ZCode)。
