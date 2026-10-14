# FX11 实车日志提交指南（银河 NOS / LocalSend）

本文面向吉利博越 L / FX11 / 银河 NOS 2.3 实车测试。目标是让车机里的完整诊断日志以 **`.log` 文件**发送到手机，而不是只发出一段文字。

## 1. 需要安装的软件

从本仓库 Release：[v1.2.8-fx11-log-tools](https://github.com/limrbrown74-lab/cluster-projector/releases/tag/v1.2.8) 下载。

| 安装位置 | 软件 | Release 附件 | 包名 / 版本 | 用途 |
|---|---|---|---|---|
| 车机 | 仪表投屏 | `cluster-projector-v1.2.8-fx11-log-tools-debug.apk` | `org.clusterprojector.app` / 1.2.8 | 主程序 |
| 车机 | 银河 OS 系统设置 | `fx11-galaxy-nos-settings-boyue-l.apk` | `com.android.settings` / 11 | 银河 NOS 阉割设置入口，方便开启悬浮窗、安装未知应用、存储等权限 |
| 车机 | LocalSend | `localsend-1.18.2-android-arm64v8.apk` | `org.localsend.localsend_app` / 1.18.2 | 车机把日志文件发到手机 |
| 手机 | LocalSend | Android 可用同一个 APK；iPhone 从 App Store 安装 | 同上 | 接收车机日志 |
| 可选车机 | 权限狗 | `fx11-permission-dog-0.1.0.45.apk` | `com.web1n.permissiondog` / 0.1.0.45 | 设置页缺失时辅助授权 |
| 可选车机 | 开发者选项 | `fx11-developer-options-1.058.apk` | `options.developer.com.developeroptions` / 1.058 | 打开开发者/调试相关入口 |
| 可选车机 | 竖屏应用管家 | `fx11-app-manager-1.8.1-portrait.apk` | `com.yunpan.appmanage` / 1.8.1 | 管理应用、权限和安装包 |

> 这些辅助 APK 是第三方调试工具，只用于自有车辆/自有设备测试。安装前建议对照 Release 里的 `SHA256SUMS-v1.2.8.txt` 校验文件。仪表投屏主程序不会静默安装 APK，也不会通过这些工具绕过 Android 权限确认。

## 2. 安装顺序

1. 车机安装 `fx11-galaxy-nos-settings-boyue-l.apk`。
2. 车机安装 `localsend-1.18.2-android-arm64v8.apk`。
3. 手机安装 LocalSend；手机和车机连接同一个 Wi-Fi/热点网络。
4. 车机安装仪表投屏 `cluster-projector-v1.2.8-fx11-log-tools-debug.apk`。
5. 如果权限入口仍然缺失，再安装权限狗、开发者选项或应用管家。

## 3. 推荐授权

打开仪表投屏后，先处理首页权限卡片：

- 显示在其他应用上层 / 悬浮窗：必需；
- 录屏/投射：点击测试或应用时由系统弹窗授权；
- 通知权限：建议允许；
- 应用列表/查询已安装应用：需要允许，否则看不到高德地图、CarLink 等系统应用；
- 存储/文件访问：建议允许，方便 LocalSend 读取 `Download` 目录。

目标包名：

| 目标 | 包名 |
|---|---|
| 地图 | `com.geely.map`，入口 `com.geely.map/.activity.MainActivity` |
| ICCOA CarLink | `com.geely.carlink`，入口 `com.geely.carlink/.MainActivity` |
| 图库/USB 相关组件 | `com.geely.gallery`，不是地图主包名 |

## 4. 导出日志的标准流程

1. 在车机上打开仪表投屏 v1.2.8。
2. 选择 Display 3 或 Display 4（FX11 仪表为 `1920×532@320dpi`）。
3. 先点击“先预检仪表，再测试投屏”，复现问题。
4. 问题出现后不要强退 App，立刻点击首页“查看日志文件”。
5. 点击“分享完整日志”。
6. App 会生成类似下面的文件：

```text
clusterprojector-diagnostic-20261014-203045.log
```

7. 系统分享面板里选择 **LocalSend**，确认发送的是文件，不是纯文本。
8. 在手机端 LocalSend 接收该 `.log` 文件。
9. 把收到的 `.log` 文件发回，不要只发截图或一句话。

## 5. 如果系统分享面板里没有 LocalSend

v1.2.8 会额外把同一个诊断包复制到：

```text
内部存储/Download/clusterprojector/
```

可以这样操作：

1. 在车机打开 LocalSend。
2. 选择“发送文件”。
3. 进入：

```text
Download/clusterprojector/
```

4. 选择最新的：

```text
clusterprojector-diagnostic-*.log
```

5. 发送到手机端 LocalSend。

如果 LocalSend 看不到该目录，请到系统设置或应用管家里给 LocalSend 授予文件/存储访问权限。

## 6. 怎样确认发对了

正确的提交物：

- 文件扩展名：`.log`；
- 文件名前缀：`clusterprojector-diagnostic-`；
- 内容开头包含：`===== ClusterProjector 诊断日志 =====`；
- 文件内可以看到设备信息、Display 列表、权限状态、`ClusterProbe`、`ClusterOverlay`、`ClusterCast`、`SecurityException` 等记录。

错误的提交物：

- 只有一句 `ClusterProjector FX11 诊断日志`；
- 只有系统分享文字；
- 只有截图；
- 只发 `/sdcard/Download/clusterprojector/app-日期.log` 但没有完整诊断包（它通常也有帮助，但优先发送完整诊断包）。

## 7. 实车测试重点

- 如果官方 Presentation 预检色块都无法显示在仪表盘，普通第三方 App 的副屏窗口很可能被 ecarx 仪表合成层拦截。
- 如果预检色块可见但正式测试画面不可见，问题在 Surface/VirtualDisplay 链路。
- `com.geely.map` 或 `com.geely.carlink` 是系统软件，并不代表本 App 能继承它们的系统 UID或跨 Display 启动权限；直出仍可能返回 `SecurityException`，这是 Android/车机策略限制。
- 镜像模式下应用会先在中控前台运行，再由 MediaProjection 捕获并送到仪表；只有日志确认真实捕获帧后，才代表仪表收到画面。
