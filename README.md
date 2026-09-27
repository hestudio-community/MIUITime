MIUITime
---

为HyperOS还原MIUI的通知栏时间效果。

### 如何下载

请前往 https://github.com/Xposed-Modules-Repo/net.hestudio.miuitime/releases 下载。

### 适配系统

- HyperOS 4 Beta（在`REDMI K80 Pro, 4.0.0.10 Beta`上测试通过）
- HyperOS 3（理论上支持，基于HyperOS 3的资源进行分析）

### 如何使用？

1. 需要安装LSPosed.
2. 安装本软件。
3. 勾选“系统界面”作用域。
4. 重启系统界面或者重启手机

### 从源码构建

1. `./gradlew assembleDebug` — APK 输出至 `app/build/outputs/apk/debug/app-debug.apk`
2. `./gradlew installDebug` — 构建并安装到已连接设备
   
