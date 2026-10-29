# 仪表投屏 Cluster Projector

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-6.0%2B-brightgreen.svg)
![Gradle](https://img.shields.io/badge/Gradle-8.4-007396.svg)
![AGP](https://img.shields.io/badge/AGP-8.2.2-673AB7.svg)

面向车机 Android 系统的仪表盘投屏实验工程：把其他 App 的画面镜像到仪表盘、把自绘数字仪表投上物理屏，**并支持两者合并——透明仪表元素叠加在应用画面上层**（投屏地图时效果最佳）。

> ⚠️ **重要**：本项目仅适配 **吉利博越 L / FX11 · 银河 NOS 2.3**，其他系统、车型、版本未经验证，请**自行适配**，适配过程中的任何问题与作者无关。实车测试前请完整阅读文末[免责声明](#免责声明)。

---

## 功能特性

- **应用投屏**：枚举全部已安装应用，选一个即可切屏 + 镜像投到仪表盘（ECARX XUI 切屏优先，VHAL 兜底）；
- **合并投屏**：投屏时把自绘仪表（车速/转速/档位/灯光/地图区等）作为**透明表层置顶**在应用画面上层——地图导航 + 仪表数据同屏显示；
- **仪表盘投屏**：不依赖原车画面，内置特斯拉风格数字仪表（日/夜双主题），可预览、可投物理屏；
- **仪表地图（AVM 官方通道）**：走厂商 OneOS API（`OneOSApiService`）把原车地图渲染到仪表盘，用于验证官方投屏通道；
- **仪表管理**：设置内可对仪表每个元素（车速大字、档位、续航、里程、地图区、转速、油量、水温、指示灯条、顶栏信息）**独立显示/隐藏**和**位置偏移**（-100% ~ +100%），带实时预览；
- **物理切屏自测**：共用底层（ECARX/VHAL）可单独手动切屏/恢复原厂，先验证再投屏；
- **后台保活与恢复**：无障碍服务 + 前台服务保活；App 被杀重启时自动尝试恢复原厂仪表；
- **诊断日志**：崩溃捕获 + 详细日志落盘 `/sdcard/Download/clusterprojector/`，出现闪退可一键查看并反馈。

---

## 快速开始

### 1. 环境要求

| 项目 | 要求 |
|---|---|
| 车型/系统 | 吉利博越 L（FX11）· 银河 NOS 2.3（其他系统自行适配） |
| Android | 6.0+（车机实际为 Android 11） |
| 构建 | JDK 21、Android SDK Platform 34、Gradle 8.4、AGP 8.2.2 |

### 2. 编译打包

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

### 3. 安装与权限

安装后按首页提示授予：

1. **悬浮窗**（仪表窗口必需）；
2. **应用列表权限**（获取全部已安装应用；MIUI/HyperOS 需到应用详情手动开启"获取应用列表"）；
3. **通知/日志**（可选，不影响投屏核心）；
4. **无障碍服务**（后台保活，防止投屏中被系统杀死）。

> 投屏授权（MediaProjection）在首次点击"测试投屏"时由系统弹窗确认。

### 4. 使用流程

1. 打开 App，确认投屏方式为"应用投屏"；
2. 在下方列表选择要投屏的 App；
3. 系统弹窗确认投屏授权；
4. 仪表盘自动切屏并显示画面（设置中开启"叠加透明仪表元素"时，画面上层叠加仪表数据）；
5. 点击"停止投屏"恢复原厂仪表。

---

## 三种投屏方式

首页默认只显示当前选中的投屏方式，点"更多投屏方式 ›"进入设置切换（选完首页同步生效，App 被杀死后恢复原厂方式）。

| 方式 | 说明 |
|---|---|
| **应用投屏** | 切屏 + 镜像任意 App 画面；可合并透明仪表浮层 |
| **仪表盘投屏** | 自绘特斯拉风格数字仪表（车速/转速/档位/灯光/地图），先预览后投物理屏 |
| **仪表地图（AVM）** | 官方 OneOS API 通道，把原车地图渲染到仪表盘（验证官方通道） |

> 三种方式共用底层物理切屏能力（ECARX XUI AdaptAPI `0x20320500` 优先，VHAL `0x21408636` 兜底），页面底部提供"物理切屏自测"可单独验证。

---

## 仪表管理

设置 → 仪表管理，可实时预览并调整：

- **叠加开关**：应用投屏时是否在画面上层叠加透明仪表元素；
- **元素显示/隐藏**：指示灯条、顶栏信息、车速大字、档位、续航、总里程、地图区、转速、油量、水温，逐个开关；
- **位置偏移**：每个元素 X/Y 各 -100% ~ +100%（相对默认位置），滑块调整即时生效并持久化。

配置保存在 `cluster_dashboard_prefs`，下次投屏自动生效。

---

## 技术原理

```text
应用投屏：
  选择应用 → 物理切屏（ECARX AdaptAPI → 仪表显示源切到 Android）
           → MediaProjection 捕获中控画面 → GL 镜像渲染到仪表 Display 窗口
           → （可选）透明仪表元素层置顶叠加
仪表盘投屏：
  物理切屏 → Presentation 承载自绘 ClusterDashboardView（1920×532 基准）
仪表地图（AVM）：
  OneOSApiService（service 34 / NaviManager type=1）→ avmSetDisplaySurface / naviAddSurface
```

### 物理切屏（关键链路）

- **主路径**：`ECARX XUI AdaptAPI` —— `Car.create()` → `getICarFunction` → `isFunctionSupported(0x20320500, GLOBAL)` → `setFunctionValue(0x20320500, GLOBAL, 1)`（三屏模式，仪表显示 Android 显示源）；
- **兜底路径**：`VHAL HIDL V2_0` —— `set 0x21408636 = 3`（叠加模式），回读 `0x21708B0F` 确认；
- `disable()` / `set 0` 恢复原厂 QNX 仪表。

### 车辆信号（仪表数据源）

`ClusterVehicleTelemetry` 通过 VHAL 轮询 21 个属性：车速、转速、档位、续航、油量、总里程、环境温度、水温、左右转向、近/远光、位置灯、后雾灯、驻车制动、安全带、巡航/ACC 状态与设定速度等（快档 250ms / 慢档 1.5s）。

### 仪表绘制

`ClusterDashboardView` 为纯 Canvas 自绘（零三方依赖），1920×532 基准坐标系缩放适配任意尺寸；日/夜双主题；100ms 定时刷新车辆数据；元素级可见性 + 位置偏移由 `DashboardConfig` 持久化控制。

---

## ⚠️ QNX / 车机系统高风险警告

### 本项目代码实际做了什么

本项目**不修改 QNX 侧的任何代码、分区或配置**，只通过车机 Android 系统**厂商已提供的接口**切换仪表显示源：

| 模块 | 调用的系统能力 | 作用 |
|---|---|---|
| `ClusterProjectionSwitch` | ECARX XUI AdaptAPI：`isFunctionSupported(0x20320500)` / `setFunctionValue(0x20320500)` | 把仪表显示源从 QNX 原厂画面切到 Android（主路径） |
| `ClusterProjectionSwitch` | VHAL HIDL V2_0：`set 0x21408636 = 3`，回读 `0x21708B0F` | 兜底路径：叠加/切屏模式 |
| `AvmServiceClient` / `ClusterMapHost` | 厂商 OneOS API（`OneOSApiService`）：`avmSetDisplaySurface` / `naviAddSurface` | 仪表地图（AVM）官方通道 |
| `ClusterApp` | 进程启动时自动检测 | 若投屏状态残留，自动恢复原厂仪表 |

恢复原厂 = `setFunctionValue(0x20320500, 0)` 或 VHAL `set 0`，**全程不写 QNX 分区、不刷机、不绕过系统签名**。

### 风险边界（务必阅读）

- **QNX 侧代码、系统分区不可随意修改**：QNX 是仪表盘安全关键系统，承载仪表、报警、辅助驾驶等安全信息；
- 不当修改可能导致**仪表黑屏、车机无法启动、安全功能失效，甚至需要更换车机硬件**；
- 切换显示源属于对车机系统服务的深度调用，**实车验证前请完整阅读文末[免责声明](#免责声明)**；
- 若切屏后未正确恢复（App 被强杀、系统服务异常等），仪表可能停留在非原厂画面，**请使用 App 内"手动切屏/恢复原厂"或重启车机恢复**；
- 本项目**不提供、不建议**任何修改 QNX、刷机、绕过系统签名、获取 HAB 密钥或底层调试通道的操作；
- 任何 QNX 级开发都必须在具备厂商授权、完整工具链和救砖方案的条件下进行，否则后果自负。

---

## FX11 显示参数（实测记录）

| 项目 | 参数 |
|---|---|
| 车型 | 吉利博越 L 2022 款 / FX11 |
| 系统 | 银河 NOS 2.3 |
| Android | 11，`RQ3A.211001.001` |
| 平台 | 亿咖通 ecarx |
| 中控屏 | Display 0，`1440×1920@160dpi` |
| 首选仪表屏 | Display 4，`1920×532@320dpi` |
| 备选仪表屏 | Display 3，`1920×532@320dpi` |

---

## 工程结构

```text
cluster-projector/
├── app/
│   └── src/main/
│       ├── java/org/clusterprojector/app/
│       │   ├── MainActivity.java              # 首页：单模式布局、应用列表、图层控制、切屏自测
│       │   ├── SettingsActivity.java          # 设置：投屏方式切换、仪表管理入口、打赏、关于
│       │   ├── DashboardManageActivity.java   # 仪表管理：元素开关 + 位置滑块 + 实时预览
│       │   ├── DashboardActivity.java         # 仪表盘预览（全屏横屏 + 日/夜切换）
│       │   ├── ClusterScrollView.java         # 首页滚动容器（列表区域不拦截触摸）
│       │   ├── ScreenCastService.java         # MediaProjection 前台服务
│       │   ├── ClusterApp.java                # Application：启动恢复原厂检查、日志初始化
│       │   ├── CrashHandler.java / AppLog.java # 崩溃捕获与本地日志
│       │   └── ...（无障碍、开机、日志分享等）
│       └── res/
│
├── clusterdisplay/          # 零三方依赖 Android Library
│   └── src/main/java/org/clusterprojector/display/
│       ├── CastController.java                # 投屏控制器（切屏 + 镜像 + 合并）
│       ├── ClusterProjectionSwitch.java       # ECARX/VHAL 物理切屏
│       ├── ClusterVehicleTelemetry.java       # VHAL 车辆信号轮询
│       ├── ClusterDashboardView.java          # 特斯拉风格自绘仪表（元素级配置）
│       ├── DashboardConfig.java               # 仪表元素配置持久化
│       ├── ClusterDashboardHost.java          # 仪表盘投物理屏（切屏 + Presentation）
│       ├── OverlayWindowHost.java             # 仪表 Display 窗口宿主（支持合并叠加）
│       ├── AvmServiceClient.java / ClusterMapHost.java  # 仪表地图（AVM 官方通道）
│       ├── DisplayEnumerator.java / Fx11DisplayProfile.java # Display 枚举与 FX11 参数
│       └── MirrorGlSurfaceView.java / WindowGeometry.java  # GL 镜像渲染 / 窗口几何
│
├── docs/support_qr.png      # 赞赏码
└── README.md
```

---

## 赞赏支持

如果这个项目对你有帮助，欢迎赞赏一杯咖啡 ☕

![赞赏码](docs/support_qr.png)

> 代码开源，咖啡自费。所有赞赏仅用于支持本项目继续维护。

---

## 免责声明

请在安装、编译、修改或分发本项目前仔细阅读本节。使用本项目即表示你已知晓并接受以下条款。

### 1. 高风险警告（务必阅读）

- 本项目通过调用车机底层接口（ECARX XUI / VHAL / OneOS API）将仪表盘显示源从原厂 QNX 切换到 Android，属于对车机系统的深度调用；
- **任何因使用本项目导致的车机黑屏、功能异常、系统损坏、数据丢失、保修失效等问题，与作者无关，风险由使用者自行承担**；
- QNX 是仪表盘安全关键系统，承载仪表、报警、辅助驾驶等安全信息；**QNX 侧代码与系统分区不可随意修改**；
- 不当修改可能导致**仪表黑屏、车机无法启动、安全功能失效，甚至需要更换车机硬件**。

### 2. 适配范围

- 本项目仅适配**吉利博越 L（FX11）· 银河 NOS 2.3**；
- 其他系统、车型、版本未经验证，请自行适配，适配过程中出现的任何问题与作者无关。

### 3. 合法使用与设备授权

- 本项目仅可用于你本人拥有、已获得合法授权的车辆和设备，用途限于学习与机制验证；
- 不得用于未经授权的车辆、设备、应用或系统，不得用于规避厂商访问控制、安全策略或法律法规要求。

### 4. 驾驶安全

- 实车调试请在停车且确保安全的环境下进行，请勿在驾驶过程中安装、授权或调整；
- 仪表画面若出现黑屏、延迟、错位、遮挡、闪退或与原厂信息不一致，必须立即停止使用并恢复原厂状态；
- 车速、电量、报警、辅助驾驶、导航提示等关键信息必须以车辆原厂仪表和实际道路为准。

### 5. 功能与兼容性限制

- 本软件按"原样"提供，不附带任何明示或暗示的适销性、特定用途适用性、稳定性或准确性担保；
- 不同车型、系统版本、厂商策略和 Display 拓扑可能导致行为不同；手机环境验证结果不代表实车结果；
- 升级车机系统后，本项目功能可能失效。

### 6. 数据与隐私

- 本项目不会主动上传车辆、应用列表、日志或媒体内容到作者服务器；
- 应用列表、系统信息和诊断日志仅在本机展示或写入本地文件；分享日志或 APK 前，请自行删除车辆识别码、账号、IP、位置、联系人、导航记录等敏感信息。

### 7. 分发与修改风险

- 你可以依据 MIT License 修改和再分发，但不得冒用吉利、亿咖通、高德、ICCOA/CarLink 等公司或相关权利人的名义进行背书；
- 再分发源码或二进制时，请保留许可与免责声明，并明确说明你的修改内容和适用风险。

### 8. 责任限制

在适用法律允许的最大范围内，作者和贡献者不对因安装、编译、修改、使用或无法使用本项目造成的任何直接或间接损失承担责任，包括但不限于车辆功能异常、数据丢失、设备不可用、交通事故、商业损失、第三方索赔或法律责任。

### 9. 商标声明

本项目与吉利、Geely、银河、亿咖通、ecarx、高德、Amap、ICCOA、CarLink 等公司、产品或生态没有官方合作、认证或授权关系。相关名称、标识和商标归各自权利人所有，文中仅作设备说明使用。

---

## License

[MIT License](LICENSE)
