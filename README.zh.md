# dsh-mobile-apk — DeepSeek Harness 安卓壳 APK

![DeepSeek Harness](https://img.shields.io/badge/DeepSeek_Harness-blue?style=flat&logo=DeepSeek&logoSize=auto&color=%232D5F9E)
![Android](https://img.shields.io/badge/Android-blue?style=flat&logo=Android&logoSize=auto&color=%2397CA00)


> **dsh-mobile 生态** · [dsh-shell-termux](https://github.com/kelai141/dsh-shell-termux)（shell）· [dsh-client-ui-responsive](https://github.com/kelai141/dsh-client-ui-responsive)（移动 UI）· [dsh-host-web-compat](https://github.com/kelai141/dsh-host-web-compat)（浏览器兼容）· [dsh-mobile](https://github.com/kelai141/dsh-mobile)（协调仓库，private）

[DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness) 的安卓壳：WebView UI 覆盖
**内嵌 Termux 运行时快照**（解压即跑，无需 Termux app）、SAF 目录桥、保活前台服务、引擎看门狗、
运行时在线更新。一个 APK 装完即用：完整的 dsh web agent，且能真实执行 bash。

## 功能

- **内嵌运行时**：随包 ~70MB xz 快照（node + bash + coreutils + dsh + 插件）；首启约 10 秒解压、
  从应用自身目录启动引擎；完全离线；
- **移动 UI**：系统 WebView 加载 `http://127.0.0.1:3080`，配响应式插件（手机端抽屉/sheet）；
- **内置控制台**：独立 bash 交互终端（`assets/console.html` + 快照内嵌 Termux），引擎未运行也可用于排查；
- **保活**：前台服务（"dsh 引擎运行中"）+ 5 秒看门狗（引擎崩溃自动重启）+ 3 秒 UI 监控轮询；
- **在线更新**：manifest 驱动的快照热替换（下载 → sha256 → 原子切换 → 自动重启），
  运行时可自更新而无需更新 APK；
- **SAF 桥**：`pickDirectory` 把所选目录映射为真实路径（`/storage/emulated/0/…`）；
- **设备权限**：所有文件访问、Shizuku 保活（可选）。

## 构建

要求：JDK 17+、Android SDK（compileSdk 36）；Gradle 8.11.1 由 wrapper 提供。

```sh
# 1. 准备运行时快照（必须，约 70MB，作为 Release 资产分发）
#    从 GitHub Releases 下载 snapshot-x86_64.tar.xz（按需选择架构/页大小变体）
mkdir -p app/src/main/assets
cp snapshot/snapshot.tar.xz app/src/main/assets/snapshot.tar.xz

# 2. 构建（缺快照会构建失败并提示）
./gradlew assembleDebug
# 产物: app/build/outputs/apk/debug/app-debug.apk
```

## 桥协议 v1（`window.androidBridge`）

应用名 `DeepCode`，包名 `com.dsharnessmobile.shell`。`androidBridge.version` 返回应用版本号
（当前 `0.12.4`），页面按它做 feature-detect。

**同步返回**

| 方法 | 签名 | 说明 |
|---|---|---|
| `version` | () → string | 应用版本号（`0.12.4`），feature-detect 用 |
| `getSystemDark` | () → boolean | 系统深色模式（绕过部分厂商 WebView `matchMedia` 失效，首帧主题用） |
| `checkEngine` | () → string | 探测 127.0.0.1:3080；JSON `{running, latencyMs, error?}` |
| `hasAllFilesAccess` | () → boolean | 是否已授予「所有文件访问」权限（外部工作区要求） |
| `getPickToken` | () → string | 目录选择桥的一次性会话 token（引擎侧 pick 端点校验） |
| `copyText` | (text) → boolean | 写入系统剪贴板（WebView `clipboard.writeText` 被拒时的回退） |
| `getDevLogEnabled` | () → boolean | dev 日志开关状态 |

**命令**

| 方法 | 签名 | 说明 |
|---|---|---|
| `keepScreenOn` | (enable) | 屏幕常亮开关 |
| `showNotification` | (title, text) | 通知测试通道（POST_NOTIFICATIONS） |
| `pickDirectory` | (callbackId) | SAF 目录选择；结果经 `window.__dshBridge.onDirectoryPicked(callbackId, path)` 异步回传 |
| `pickImage` | (callbackId) | SAF 图片选择；结果同上异步回传 |
| `setTextZoom` | (percent) | WebView 字体缩放（50–200，设置页滑杆） |
| `setImmersiveMode` | (enable) | 沉浸式状态栏开关（true = 状态栏常隐） |
| `downloadDebugLogs` | () | 导出引擎日志 + 环境信息（压缩包，走系统分享/下载） |
| `requestAllFilesAccess` | () | 打开系统「所有文件访问」授权页（特殊权限） |
| `restartEngine` | () | 重启引擎进程（EngineService 看门狗拉起） |
| `shutdownToGuide` | () | 停引擎并回退到测试界面（不自动重启） |
| `reloadWebUI` | () | 重新加载 Web UI |
| `openConsole` | () | 打开内置控制台 |
| `setDevLogEnabled` | (enabled) | 设置 dev 日志开关（开启后日志写入 `dshdata/log/`） |

桥协议让 APK 与 dsh 版本解耦：页面按 `androidBridge.version` 做特性检测。

## 在线更新协议

1. App 拉取 `manifest.json`：`{url, sha256, size}`（默认 `http://10.0.2.2:8899/manifest.json`
   供模拟器测试；生产指向发布服务器）；
2. 下载快照 → 校验 SHA-256 → 解压到 staging（不碰线上目录）→ 原子切换 `usr` → 杀掉旧引擎 →
   看门狗用新运行时重启。

测试触发：`adb shell am start -n com.dsharnessmobile.shell/.MainActivity -a com.dsharnessmobile.shell.action.UPDATE`；
状态写入 `files/update-status.txt`。测试服务器：本地起 HTTP 服务提供 `manifest.json` 与快照文件
（默认指向 `http://10.0.2.2:8899/manifest.json`，模拟器映射宿主机）。

## 权限

| 权限 | 用途 |
|---|---|
| `INTERNET` | WebView + 引擎探测 |
| `POST_NOTIFICATIONS` | 通知通道（API 33+ 运行时请求） |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` | 保活前台服务 |
| `MANAGE_EXTERNAL_STORAGE` | 「所有文件访问」（外部工作区要求；特殊权限，用户手动授予） |

SAF 目录/图片选择无需权限。

## ABI 与页大小

x86_64 快照已端到端验证；arm64 快照由官方 Termux aarch64 仓库组装（见 docs/design.md §ABI）；
16KB 页构建需在 16KB 设备上产出。APK 按 ABI 分发（内含快照与架构绑定）。

## License

MIT。第三方组件按各自许可（见依赖声明）。设计文档：`docs/design.md`。
