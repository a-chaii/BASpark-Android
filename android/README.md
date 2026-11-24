# android/

安卓移植的全部代码都在这儿。根目录下的 C# / WPF 是上游原版，和这里没有依赖关系。

## 目录

```
android/
├── AndroidManifest.xml          权限、服务、磁贴、无障碍声明
├── build.sh                     构建脚本（aapt2 + javac + d8 + apksigner）
├── assets/index.html            上游的粒子页面，原样拷贝，一行没改
├── res/                         图标、字符串、无障碍配置
└── src/com/miu/baspark/
    ├── MainActivity.java        设置界面（M3 语义色 + 手写组件）
    ├── NativeSparkView.java     原生 Canvas 粒子引擎（WebView 的等价重写）
    ├── DLog.java                调试日志（logcat 刷得太快时写文件）
    ├── OverlayService.java      前台服务：透明 WebView 悬浮窗 + 设置注入 + 帧率限制
    ├── TouchReader.java         root 读 /dev/input，解析触摸事件并换算百分比
    ├── BasAccessibilityService.java  免 root 降级：控件级点击坐标
    ├── BasparkTileService.java  下拉通知栏磁贴
    ├── BootReceiver.java        开机自启
    ├── AppFilter.java           前台应用判定（白名单）
    └── Config.java              SharedPreferences 封装
```

## 构建

```bash
# 需要有 build-tools / platform android-35 / JDK 17
ANDROID_HOME=~/Android bash build.sh
```

产物：`out/BASparkMobile.apk`（约 190KB）。

脚本会把 `out/gen/` 里的 `R.java` 一起编进去，`assets/index.html` 通过 `aapt2 link -A` 打进 APK，
最后用 keytool 现场生成一个 debug keystore 签名 —— 仅供自用，别用它发版。

## 三条链路

**窗口**
`WindowManager.addView` 一个 `TYPE_APPLICATION_OVERLAY` 的 WebView，`MATCH_PARENT` + `TRANSLUCENT`。
`FLAG_NOT_TOUCHABLE` 是关键，否则整块屏幕的点击都会被它吃掉。

**触摸（root）**
`cat /dev/input/eventN` 拿到 24 字节一组的 `struct input_event`（64 位内核：
timeval 16 字节 + type 2 + code 2 + value 4）。解析出 `ABS_MT_SLOT / POSITION_X / POSITION_Y / TRACKING_ID`
就能还原每个手指的位置，再除以面板的 ABS 最大值得到 0~1 的百分比。
设备名和取值范围是运行时从 `getevent -p` 里解析的，换机型不用改代码。

**触摸（无障碍）**
监听 `TYPE_VIEW_CLICKED`，取 `AccessibilityNodeInfo.getBoundsInScreen()` 的中心点。
只有"点"，没有"拖"，所以没有拖尾。

## 两套渲染实现

`Config.renderer` 决定用哪个：0 = WebView（默认，加载 assets/index.html），
1 = NativeSparkView。两者对外都吃 0~1 的百分比坐标，切换时服务会重启。

原生版是把 index.html 里的常量与公式搬过来：波环的 `RINGS_ANIM_CFG`、
实心圆的 `FILLED_CIRCLE_CFG`、爆裂的 4 颗三角碎片、拖尾的三段辉光。
简化点写在 NativeSparkView 的类注释里。

## 帧率限制

上游的动画是按 `deltaTime` 推进的，所以跳帧只影响顺滑度，不会让动画变慢。
`OverlayService.FPS_LIMITER_JS` 会在页面加载完成后注入一段脚本，包住上游的
`window.scheduleNextAnimationFrame`：没到目标间隔就先用一个空 rAF 把时间耗掉。
另外它顺手统计了真实绘制的 fps（`window.__basRealFps`），界面上显示的就是这个值。

## 坐标与旋转

触摸面板的坐标空间是固定的（竖屏方向），屏幕一动就不会跟着转，所以 `TouchReader.mapX/mapY`
按 `Display.getRotation()` 做了四个方向的重映射。配置变更用
`ACTION_CONFIGURATION_CHANGED` 广播监听，不依赖 Activity 的生命周期。

## 注意

- 改完代码记得重新 `bash build.sh`，`out/` 是每次清空重建的
- `assets/index.html` 是上游文件，想升级特效直接整份替换，不用管 Java 侧
