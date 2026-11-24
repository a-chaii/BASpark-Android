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

## 两种渲染实现，可以手动切

设置里有个「渲染实现」开关，两套引擎跑的是同一套粒子数学：

- **WebView** —— 直接加载上游的 `index.html`，保真度最高，代价是 Chromium 那套东西要常驻。实测 PSS 约 47 MB。
- **原生 View** —— 把那 500 行粒子数学用 Java + Canvas 重写了一遍，不启 WebView 进程，同样场景下 PSS 约 12 MB。
原生版不是像素级复刻，但拖尾那部分已经对齐了：整条尾迹一次描边、线性渐变从尾巴递进到指针，笔帽和线宽也按上游的单位换算过。没有做上游那套离屏缓冲加脏矩形，`lighter` 叠加换成了 `BlendMode.PLUS`（HDR 时启用），所以极近距离盯的话，辉光的堆叠方式还是能看出差别。

## 更新日志

### 0.6

- 拖尾改成整条路径一次描边加线性渐变，不再逐段上色，接缝处的"串珠子"感消失了
- 拖尾碎片补上屏幕密度换算——之前在这块 2.975 密度的屏上，碎片只有应有尺寸的三分之一，缩成了亮点
- 拖尾笔帽从圆头改回 butt，线宽不再被缩放系数削弱；采样阈值改成按密度算
- 修掉 HDR 开启后中心那个突兀的大白点
- 新增 HDR 倍率调节，0.3x 到 3.0x
- 新增「隐藏最近任务卡片」开关
- 修掉原生 View 在 root 模式下完全不显示的问题：触摸事件改为主线程分发

### 0.5

- 修掉"越往屏幕上方，特效越往下偏"：窗口补上 `FLAG_LAYOUT_NO_LIMITS` 与刘海区铺满，坐标换算改成按窗口实际边界而不是屏幕尺寸

### 0.4

- 深色模式下状态指示面板与文字混色，改为走 `surfaceContainerHigh` 配 `onSurface`
- 开关控件重做成 Material 3 造型
- 多指拖拽优化（默认开，关掉就是"翻绳"）
- 应用白名单
- 无障碍降级方案

### 0.3

- 界面整体按 Material 3 重做，动态取色、语义色卡片、pill 按钮
- 通知栏快捷磁贴、开机自启、渲染帧率限制

### 0.2

- 原生 Canvas 渲染引擎，可在设置里与 WebView 手动切换


## 现在能用的

悬浮窗特效本身；横竖屏坐标都对得上；多指可以玩（默认只让第一根手指牵引尾迹，关掉就是"翻绳"）；设置面板里能调大小、颜色、透明度、拖尾与爆裂速度、渲染帧率；应用白名单；通知栏快捷磁贴；开机自启；浅色/深色跟随系统。

帧率那条要多说一句：**只能限制，不能超过面板刷新率**。165Hz 的屏设 165 才是满帧，设 60 是省电档。如果实测远低于设定值，多半是系统 WebView 自己把 rAF 锁在 60 了，这个不是应用能改的。

## 编译

没用 Gradle。这台机器的构建环境是 proot 里的 Ubuntu，装 Gradle 反而更麻烦，所以直接走 aapt2 + javac + d8 + apksigner 这一套。

```bash
ANDROID_HOME=/path/to/android-sdk bash android/build.sh
```

需要 build-tools（36 测试过）、platform android-35、JDK 17。产物在 `android/out/BASparkMobile.apk`。

本地编的话签名是现场生成的临时 key，装过旧版就得先卸载。CI 那边从仓库 Secret 里读固定的 keystore，所以 Actions 下的包能直接覆盖安装——想要同样的效果，把 keystore 的 base64 放进环境变量 `BASPARK_KEYSTORE`（base64 内容存在 `KEYSTORE_BASE64` secret 里，构建前解到 `android/release.keystore`）。

包名 `com.miu.baspark`，minSdk 26，targetSdk 35。

## 已知问题

- 旋转屏幕后偶尔要等一次配置变更才对齐，个别 ROM 上可能还是偏一点点
- 白名单依赖「使用情况访问」权限，得手动去系统设置里给
- 两种渲染方式的内存差距只在「服务跑着」时才有意义：WebView 模式常驻约 47 MB，原生模式约 12 MB。空转时都不画东西，CPU 是 0
- 无障碍模式下没有拖尾，这是那套 API 本身的限制，不是我偷懒
- 悬浮窗盖不住锁屏和通知中心。这两层是 SystemUI 的系统窗口，层级高于所有应用悬浮窗，root 也绕不过去——试过用 root 起独立进程加系统级窗口，被 WMS 的进程注册检查挡回来了
- 部分 ROM（比如 ColorOS）会把悬浮层排除在截屏之外，所以截图里看不到特效是正常现象

## 许可

跟着上游走 MIT。美术语言来自 *Blue Archive*，版权属于 Nexon / Yostar，这只是一个非商业的粉丝作品。
