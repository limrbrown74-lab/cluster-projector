# 仪表投屏 Cluster Projector

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-8.0%2B-brightgreen.svg)
![Gradle](https://img.shields.io/badge/Gradle-8.4-007396.svg)

把 Android 车机中控屏上的任意应用画面，悬浮投射到仪表盘显示屏上。
仪表盘无触控，所有窗口位置 / 尺寸 / 透明度均在中控屏控制面板上调节。

> 本项目由 AI 辅助原创开发，仅供学习研究使用。实车操作请务必停车进行。

---

## ✨ 功能特性

- **应用投屏**：列出本机已安装应用，点击即可投到仪表盘
- **悬浮图层**：画面以 overlay 方式浮在原车仪表盘之上，不遮挡原车信息
- **可调窗口**：位置（X/Y）、尺寸（宽/高）、透明度均可实时调节
- **双屏并行**：中控屏控制面板与仪表盘画面同时运行，互不打断
- **测试模式**：一键发送纯色测试画面，快速验证投屏链路
- **后台保活**：前台服务 + 电池优化白名单 + 无障碍服务，降低被系统回收概率
- **零依赖库**：核心模块仅依赖 Android Framework，可独立编译为 AAR

## 📺 效果演示

```
┌──────────────────────┐        ┌──────────────────────┐
│   中控屏（控制面板）    │        │   仪表盘（悬浮画面）    │
│                      │        │                      │
│  [应用列表]           │  ───▶  │  ┌──────────────┐    │
│  ● 高德地图           │        │  │  高德地图画面  │    │
│  ● 网易云音乐         │        │  │  (可调大小)   │    │
│  ● ...               │        │  └──────────────┘    │
│                      │        │  原车仪表盘信息可见     │
│  滑块: X Y 宽 高 透明度 │        │                      │
└──────────────────────┘        └──────────────────────┘
```

## 🛠 技术栈

| 项 | 版本 |
|---|---|
| minSdk | 23 (Android 8.0) |
| compileSdk | 34 |
| JDK | 17 |
| Gradle | 8.4 |
| AGP | 8.1.4 |
| 第三方依赖 | 无（纯 Android Framework） |

## 📁 项目结构

```
cluster-projector/
├── app/                          # Demo 应用模块（可安装运行）
│   └── src/main/java/org/clusterprojector/app/
│       ├── MainActivity.java          # 主界面：应用列表 + 滑块控制面板
│       ├── ScreenCastService.java     # MediaProjection 前台服务
│       ├── CastAccessibilityService.java  # 无障碍服务（后台保活）
│       ├── TestCastActivity.java      # 投屏测试画面
│       └── BootUpReceiver.java        # 开机自启
│
└── clusterdisplay/               # 投屏核心库（可复用 AAR）
    └── src/main/java/org/clusterprojector/display/
        ├── CastController.java           # 统一控制器
        ├── VirtualScreenHost.java        # 虚拟屏 + 应用启动
        ├── OverlayWindowHost.java        # 仪表盘悬浮窗
        ├── DisplayEnumerator.java        # Display 枚举
        └── WindowGeometry.java           # 窗口几何数据
```

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog 或更新版本
- JDK 17
- Android SDK 34

### 编译

```bash
./gradlew :app:assembleDebug
```

产物路径：

```
app/build/outputs/apk/debug/app-debug.apk
```

### 作为依赖引入

把 `:clusterdisplay` 作为 composite 模块引入你的工程，或直接引用编译出的 AAR。

## 📖 使用说明

### 首次启动

1. 安装 APK，打开"仪表投屏"
2. 授予**悬浮窗权限**（设置 → 显示 → 其他应用在顶部显示）
3. 授予**电池优化白名单**（设置 → 电池 → 不限制后台）
4. 开启**无障碍服务**（设置 → 无障碍 → 仪表投屏）
5. 授予**录屏权限**（投屏时系统弹窗确认）

### 投屏操作

1. 在应用列表中点击要投射的应用
2. 同意系统录屏授权弹窗
3. 仪表盘上即出现该应用的悬浮画面
4. 在中控屏拖动滑块调整：
   - **水平位置 X**：左右移动窗口
   - **垂直位置 Y**：上下移动窗口
   - **宽度 / 高度**：缩放窗口大小
   - **透明度**：0% 全透明 ~ 100% 不透明
5. 点击"测试投屏"可发送纯色画面验证链路

## 🔐 权限说明

| 权限 | 用途 |
|---|---|
| `SYSTEM_ALERT_WINDOW` | 在仪表盘上创建悬浮窗 |
| `FOREGROUND_SERVICE` | 保持投屏服务前台运行 |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | Android 14+ 录屏前台服务类型 |
| `START_ACTIVITIES_FROM_BACKGROUND` | 后台把应用启动到虚拟屏 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 电池优化白名单，防止被杀 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启 |
| `POST_NOTIFICATIONS` | Android 13+ 前台服务通知 |
| `QUERY_ALL_PACKAGES` | 列出本机全部已安装应用 |
| `BIND_ACCESSIBILITY_SERVICE` | 无障碍服务绑定（后台保活） |

## 🧩 核心模块

### clusterdisplay —— 仪表盘投屏

```java
// 1. 枚举可用 Display
DisplayEnumerator dm = new DisplayEnumerator(context);
List<DisplayTarget> displays = dm.listDisplays();

// 2. 创建控制器
CastController ctrl = new CastController(context, clusterInfo, display);
ctrl.start(mediaProjection, metrics);

// 3. 启动指定应用到仪表盘
ctrl.launchApp("com.autonavi.minimap");

// 4. 程序化调整窗口
ctrl.setBounds(new WindowGeometry(100, 50, 800, 400));
ctrl.setAlpha(0.85f);
```

## ⚠️ 注意事项

- 本项目已适配**吉利博越L（银河 NOS 2.3）**，其他车型 / 车机系统需自行调整 Display 枚举与窗口参数
- 本项目仅支持搭载 Android 系统的车机 / 平板，iOS 不适用
- 仪表盘必须为系统枚举的独立 Display（AAOS 或定制 Android 车机）
- 部分 OEM 车机的私有 Display 通道可能需要额外适配
- 实车使用请遵守当地法律法规，导航数据不能作为驾驶依据
- 请在停车状态下完成投屏与窗口调整操作

## 📜 免责声明

1. **使用风险自担**：本软件按"原样"提供，不附带任何明示或暗示的担保。使用者须自行承担因安装、运行或修改本软件所产生的一切风险与后果。
2. **车辆损坏免责**：因安装或使用本软件导致的任何车辆硬件损坏、仪表盘故障、车机系统异常、电路问题、保修失效或其他直接 / 间接损失，作者不承担任何责任。
3. **数据与隐私**：本软件在本地运行，不上传任何数据；但使用者仍需自行评估隐私与数据安全风险。
4. **交通法规**：驾驶过程中操作本软件可能违反当地交通法规，由此产生的罚单、事故或法律责任由使用者自行承担。
5. **第三方品牌**：本项目提及的"高德地图""网易云音乐"等名称仅为应用示例，商标归各自所有者所有；本项目与上述公司无任何关联。
6. **非官方性质**：本软件为独立开源项目，与任何车厂、车载系统厂商无官方合作或授权关系。

## 🤝 贡献

欢迎提交 Issue 和 Pull Request。

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/amazing`)
3. 提交改动 (`git commit -m 'Add amazing feature'`)
4. 推送到分支 (`git push origin feature/amazing`)
5. 开启 Pull Request

## 📄 开源协议

本项目基于 [MIT License](LICENSE) 开源。

## 🙏 致谢

- Android Open Source Project
- 所有为本项目提供思路与反馈的开发者
