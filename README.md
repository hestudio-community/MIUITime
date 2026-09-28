MIUITime
---

为HyperOS还原MIUI的通知栏时间效果。

### 如何下载

请前往 https://github.com/Xposed-Modules-Repo/net.hestudio.miuitime/releases 下载。

### 功能支持

1. 在使用12小时制时，显示[MIUI的分段时间](https://web.vip.miui.com/page/info/mio/mio/detail?postId=1312529)(凌晨、上午、中午、下午、傍晚、晚上、半夜)。
2. 在桌面有时间卡片时，隐藏通知栏的时间显示。
3. 适配HyperOS的时间小部件（仅支持系统的小部件，不支持非官方的个性组件）
4. 适配HyperOS4的堆叠卡片。

### 适配系统 & 使用范围

目前适配的系统如下：
- HyperOS 4 Beta（在`REDMI K80 Pro, 4.0.0.10 Beta`上测试通过）
- HyperOS 3（理论上支持，基于HyperOS 3.300的资源进行分析）

> 以上提到的均为国行版本的Xiaomi HyperOS，未对海外版本进行测试。

经过测试的语言：
- 中文（简体）：完全测试，可以工作
- 中文（繁体）：非完全测试，可以工作
- English(US)：完全测试，可以工作
- 日本语：非完全测试，可以工作
- 韩语：非完全测试，可以工作

### 如何使用？

1. 需要安装LSPosed.
2. 安装本软件。
3. 勾选“系统界面”作用域。
4. 重启系统界面或者重启手机

### 从源码构建

1. `./gradlew assembleDebug` — APK 输出至 `app/build/outputs/apk/debug/app-debug.apk`
2. `./gradlew installDebug` — 构建并安装到已连接设备
   
