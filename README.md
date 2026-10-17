# 仪表投屏 Cluster Projector（已归档 / ARCHIVED）

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
![Android](https://img.shields.io/badge/Android-6.0%2B-brightgreen.svg)
![Gradle](https://img.shields.io/badge/Gradle-8.4-007396.svg)

> ⚠️ **本项目已停止维护并归档（ARCHIVED）**，仅保留源码供学习参考。
>
> **最终技术结论：吉利博越 L / FX11 的仪表盘物理屏不由 Android Display 合成链路接管，Android 层的 Overlay / Presentation / MediaProjection / VirtualDisplay 都无法真正把画面合成到原厂仪表图层之上。想要向仪表盘输出内容，必须走车机底层 QNX 原生程序 + HAB（Hypervisor Application 分区/专用合成通道），这超出了普通第三方 Android 应用的能力边界。**

---

## 🛑 重要：为什么停更

经过多轮实车验证（v1.0.0 → v1.4.1，含 Overlay、Presentation、直出 VirtualDisplay、无编码 GL 镜像、厂商同款 SurfaceView 直连等多种链路），确认：

1. **Android 窗口系统到不了原厂仪表**
   - 仪表 Display（1920×532，Display 3/4）在 Android 侧"逻辑可见"，但原厂仪表画面由 **QNX + 独立合成器（WFD/专用 layer）** 渲染；
   - 普通应用创建的窗口（无论 Overlay 还是 Presentation）无法进入 QNX 合成通道，物理仪表上不会有任何输出；
   - 车机自带的"应用管家-同步显示"能工作，是因为它是 **系统签名 + 平台白名单 + 专属显示通道**，普通第三方应用无法复刻。

2. **正确路径是 QNX 原生程序 + HAB**
   - 需要运行在车机 QNX 侧的原生程序（C/C++），通过亿咖通/吉利定义的 HAB 接口或专用显示服务把内容送入仪表合成器；
   - 这属于**车机底层固件/系统级开发**，不是 Android 应用工程；
   - 需要厂商开发文档、签名密钥和底层调试通道，普通用户无法自行完成。

3. **风险提示（务必阅读）**
   - **QNX 侧代码 / 系统分区不可随意修改**：QNX 是仪表盘安全关键系统，承载仪表、报警、辅助驾驶等安全信息；
   - 不当修改可能导致 **仪表黑屏、车机无法启动、安全功能失效，甚至需要更换车机硬件**；
   - 本项目**不提供、不建议**任何修改 QNX、刷机、绕过系统签名或 HAB 相关操作；
   - 任何 QNX 级开发都必须在具备厂商授权、完整工具链和救砖方案的条件下进行。

---

## 项目定位（归档前）

面向 Android 多屏车机的仪表盘投屏实验工程，曾用于验证：

- 吉利 FX11 / 博越 L 2022 款，银河 NOS 2.3，Android 11（`RQ3A.211001.001`），亿咖通 ecarx 定制系统；
- 多种 Android 侧投屏链路（Overlay、Presentation、直出、GL 镜像）在仪表屏上的可行性。

**结论：Android 侧投屏到仪表盘的实验已完成，结论为"不可行（普通应用）"，因此项目归档。**

---

## 当前代码状态

```text
applicationId = org.clusterprojector.app
versionCode   = 15
versionName   = 1.4.1-fx11-overlay-mirror
minSdk        = 23
targetSdk     = 28
```

仓库保留：

- `app/` — 控制面板 Demo（应用列表、权限卡片、图层控制、诊断日志）；
- `clusterdisplay/` — 零三方依赖的 Android Library（Display 枚举、投屏控制器、GL 镜像渲染）；
- `docs/` — FX11 日志提交流程等说明。

> 代码仅供学习 Android 多屏、MediaProjection、Display 枚举与窗口宿主等机制参考，**不建议再投入实车投屏测试**。

---

## 目录

1. [为什么停更](#-重要为什么停更)
2. [技术结论与正确方向](#技术结论与正确方向)
3. [FX11 显示参数（实验记录）](#fx11-显示参数实验记录)
4. [工程结构](#工程结构)
5. [编译与打包](#编译与打包)
6. [免责声明](#免责声明)
7. [License](#license)

---

## 技术结论与正确方向

### 已验证的 Android 侧链路（均无法合成到原厂仪表）

| 链路 | 结果 |
|---|---|
| `TYPE_APPLICATION_OVERLAY` + GL 镜像 | 逻辑可见，物理仪表无输出 |
| 标准 `Presentation` + SurfaceView | 逻辑可见，物理仪表无输出 |
| 直出 `VirtualDisplay` + `setLaunchDisplayId` | Activity 可能被静默放回中控 |
| 厂商同款 Presentation + MediaProjection 直连 | 复刻"应用管家"形态，仍无法进入 QNX 合成 |

### 正确的实现方向（超出本项目范围）

```text
第三方 App（Android）
      │  通过厂商定义的 IPC / HAB 服务提交内容
      ▼
QNX 原生程序（C/C++，运行在 QNX 侧）
      │  调用亿咖通/吉利专用显示接口（HAB / 合成通道）
      ▼
仪表合成器（WFD / 专用 layer）
      ▼
物理仪表屏
```

### 关键约束

- 需要亿咖通/吉利提供的 **HAB 开发文档、SDK、签名密钥**；
- 属于**安全关键系统**开发，必须走厂商授权流程；
- 普通 Android 应用**无法**通过任何方式自行完成此链路。

---

## FX11 显示参数（实验记录）

| 项目 | 参数 |
|---|---|
| 车型 | 吉利博越 L 2022 款 / FX11 |
| 系统 | 银河 NOS 2.3 |
| Android | 11，`RQ3A.211001.001` |
| 平台 | 亿咖通 ecarx |
| 中控屏 | Display 0，`1440×1920@160dpi` |
| HUD/小屏 | Display 1，`800×480@320dpi`（已排除） |
| 副驾/娱乐屏 | Display 2，`2880×1080@160dpi`（已排除） |
| 首选仪表屏 | Display 3，`1920×532@320dpi`（Android 侧可见，QNX 合成） |
| 备选仪表屏 | Display 4，`1920×532@320dpi` |

---

## 工程结构

```text
cluster-projector/
├── app/
│   └── src/main/
│       ├── java/org/clusterprojector/app/
│       │   ├── MainActivity.java              # 控制面板、权限卡片、Display/应用选择
│       │   ├── ScreenCastService.java         # MediaProjection 前台服务
│       │   ├── CastAccessibilityService.java  # 预留，核心链路不依赖
│       │   ├── TestCastActivity.java          # 仪表链路测试画面
│       │   ├── AppLog.java                    # 本地诊断日志
│       │   ├── LogShareProvider.java          # 只读日志分享 Provider
│       │   ├── ClusterApp.java                # Application，初始化日志
│       │   ├── CrashHandler.java              # 崩溃记录
│       │   └── BootUpReceiver.java            # 可选开机入口
│       ├── res/
│       └── AndroidManifest.xml
│
├── clusterdisplay/
│   └── src/main/java/org/clusterprojector/display/
│       ├── CastController.java                # 投屏控制器（直出/镜像/厂商链路）
│       ├── ClusterPresentationProbe.java      # Presentation 预检
│       ├── DisplayEnumerator.java             # Display 枚举与评分
│       ├── DisplayTarget.java                 # Display 描述对象
│       ├── Fx11DisplayProfile.java            # FX11 参数、评分、默认窗口
│       ├── OverlayWindowHost.java             # Overlay/Presentation 窗口宿主
│       ├── VirtualScreenHost.java             # 直出 VirtualDisplay
│       ├── MirrorCaptureHost.java             # 中控 Display 0 捕获
│       ├── MirrorGlSurfaceView.java           # 无编码 OpenGL 镜像渲染
│       ├── VendorMirrorPresentation.java      # 厂商同款 Presentation 链路
│       ├── ClusterLog.java                    # 核心库本地日志
│       └── WindowGeometry.java                # X/Y/宽/高数据
│
├── docs/
└── README.md
```

`clusterdisplay` 是零三方依赖的 Android Library，仅使用 Android Framework。

---

## 编译与打包

### 环境要求

- JDK 17
- Android SDK Platform 34
- Gradle 8.4
- Android Gradle Plugin 8.1.4

### 构建

```bash
./gradlew clean :app:assembleRelease :clusterdisplay:assembleRelease
```

产物：

```text
app/build/outputs/apk/release/app-release.apk
clusterdisplay/build/outputs/aar/clusterdisplay-release.aar
```

> 源码仅供学习参考，**不建议实车安装测试**（见免责声明）。

---

## 免责声明

请在安装、编译、修改或分发本项目前仔细阅读本节。使用本项目即表示你已知晓并接受以下条款。

### 1. 已停止维护

本项目已停止维护并归档，仅保留源码供学习 Android 多屏机制参考。作者不再提供实车适配、问题修复或新版本支持。

### 2. QNX / 车机系统高风险警告（务必阅读）

- 本项目的最终结论是：向仪表盘输出内容需要 **QNX 原生程序 + HAB（厂商专用显示通道）**，这属于车机**安全关键系统**开发；
- **QNX 侧代码、系统分区、HAB 配置不可随意修改**：QNX 承载仪表、报警、辅助驾驶等安全关键信息；
- 不当修改可能导致：**仪表黑屏、车机无法启动、安全功能失效、车辆功能异常，甚至需要更换车机硬件**；
- 本项目**不提供、不建议**任何修改 QNX、刷机、绕过系统签名、获取 HAB 密钥或底层调试通道的操作；
- 任何 QNX 级开发都必须在具备**厂商授权、完整工具链、救砖方案**的条件下进行，否则后果自负。

### 3. 合法使用与设备授权

- 本项目仅可用于你本人拥有、已获得合法授权的车辆和设备，用途限于学习与机制验证。
- 不得用于未经授权的车辆、设备、应用或系统，不得用于规避厂商访问控制、安全策略或法律法规要求。

### 4. 驾驶安全

- 实车调试请在停车且确保安全的环境下进行，请勿在驾驶过程中安装、授权或调整。
- 仪表画面若出现黑屏、延迟、错位、遮挡、闪退或与原厂信息不一致，必须立即停止使用并恢复原厂状态。
- 车速、电量、报警、辅助驾驶、导航提示等关键信息必须以车辆原厂仪表和实际道路为准。

### 5. 功能与兼容性限制

- 本软件按"原样"提供，不附带任何明示或暗示的适销性、特定用途适用性、稳定性或准确性担保。
- 不同车型、系统版本、厂商策略和 Display 拓扑可能导致行为不同；手机环境验证结果不代表实车结果。
- 升级车机系统后，本工程功能可能失效。

### 6. 数据与隐私

- 本工程不会主动上传车辆、应用列表、日志或媒体内容到作者服务器。
- 应用列表、系统信息和诊断日志仅在本机展示或写入本地文件；分享日志或 APK 前，请自行删除车辆识别码、账号、IP、位置、联系人、导航记录等敏感信息。

### 7. 分发与修改风险

- 你可以依据 MIT License 修改和再分发，但不得冒用吉利、亿咖通、高德、ICCOA/CarLink 等公司或相关权利人的名义进行背书。
- 再分发源码或二进制时，请保留许可与免责声明，并明确说明你的修改内容和适用风险。

### 8. 责任限制

在适用法律允许的最大范围内，作者和贡献者不对因安装、编译、修改、使用或无法使用本项目造成的任何直接或间接损失承担责任，包括但不限于车辆功能异常、数据丢失、设备不可用、交通事故、商业损失、第三方索赔或法律责任。

### 9. 商标声明

本项目与吉利、Geely、银河、亿咖通、ecarx、高德、Amap、ICCOA、CarLink 等公司、产品或生态没有官方合作、认证或授权关系。相关名称、标识和商标归各自权利人所有，文中仅作设备说明使用。

---

## License

[MIT License](LICENSE)
