# BASpark · Android 移植

这个仓库里的安卓代码是 AI 写的。我负责提需求、拿真机试、判断哪些效果算对。
所以你会看到注释比代码长、有些地方绕了远路、个别的坐标换算纯粹是试出来的。
能用，但别当教科书看。

---

BASpark 原本是个 Windows 小工具，把《蔚蓝档案》里点一下屏幕就炸开的粒子效果搬到桌面上。
这个仓库是它的安卓移植：**透明悬浮窗 + WebView 承载上游那套 Canvas 粒子**，手指点哪儿，特效就炸在哪儿。

装上去之后它长这样——一个小界面管所有设置，剩下的都发生在这块透明层里，你在别的 App、桌面、甚至锁屏前随便点，它都在那儿响应。

## 上游

Windows 原版在 [DoomVoss/BASpark](https://github.com/DoomVoss/BASpark)，MIT 协议。
本仓库是它的 fork，上游的 C# / WPF 源码（`src/`、`BASpark.sln`、`pages/` 等）我原样保留了，想看原版怎么写的可以直接翻。
安卓移植全部在 `android/` 目录里，没有改动上游任何一行。

## 怎么实现的

上游最妙的一点是：真正好看的粒子全在一个自包含的 `index.html` 里，而且它把接口暴露得很干净——

```js
window.externalBoom(percentX, percentY)
window.externalMove(percentX, percentY)
window.externalUpdateColor(...)
```

所以安卓这边**一行 JS 都没改**，把 `index.html` 塞进 WebView，然后从原生端把百分比坐标喂进去就行。移植的工作量几乎全在"窗口"和"触摸"这两件事上。

窗口好办，`TYPE_APPLICATION_OVERLAY` 加透明背景、`FLAG_NOT_TOUCHABLE` 让它点击穿透。

麻烦的是触摸：窗口既然能穿透，就收不到事件；想收事件，就没法穿透。绕不开的两条路——

- **root**：直接读 `/dev/input/event*` 的二进制事件流，自己解析 `struct input_event`。这是唯一能和桌面端"全局钩子"等价的做法，多指、拖拽、零延迟都对得上。移植版默认走这条。
- **无障碍**：监听 `TYPE_VIEW_CLICKED`，只能拿控件中心的坐标，没有连续拖拽，也不会有尾迹。算降级方案，但至少不用 root。

## 现在能用的

悬浮窗特效本身；横竖屏坐标都对得上；多指可以玩（默认只让第一根手指牵引尾迹，关掉就是"翻绳"）；设置面板里能调大小、颜色、透明度、拖尾与爆裂速度、渲染帧率；应用白名单；通知栏快捷磁贴；开机自启；浅色/深色跟随系统。

帧率那条要多说一句：**只能限制，不能超过面板刷新率**。165Hz 的屏设 165 才是满帧，设 60 是省电档。如果实测远低于设定值，多半是系统 WebView 自己把 rAF 锁在 60 了，这个不是应用能改的。

## 编译

没用 Gradle。这台机器的构建环境是 proot 里的 Ubuntu，装 Gradle 反而更麻烦，所以直接走 aapt2 + javac + d8 + apksigner 这一套。

```bash
ANDROID_HOME=/path/to/android-sdk bash android/build.sh
```

需要 build-tools（36 测试过）、platform android-35、JDK 17。产物在 `android/out/BASparkMobile.apk`，用脚本里自动生成的 debug keystore 签的名。

包名 `com.miu.baspark`，minSdk 26，targetSdk 35。

## 已知问题

- 旋转屏幕后偶尔要等一次配置变更才对齐，个别 ROM 上可能还是偏一点点
- 白名单依赖「使用情况访问」权限，得手动去系统设置里给
- 原生渲染版还没做，所以常驻内存偏高（WebView 大概 80MB 上下）。空转时 CPU 是 0，但内存不会吐出来
- 无障碍模式下没有拖尾，这是那套 API 本身的限制，不是我偷懒

## 许可

跟着上游走 MIT。美术语言来自 *Blue Archive*，版权属于 Nexon / Yostar，这只是一个非商业的粉丝作品。
