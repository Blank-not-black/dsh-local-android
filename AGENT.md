# AGENT.md — dsh-mobile-apk 开发地图

> **⚠️ AI 主动更新条款（必须最先执行）**：本文件面向人类与 AI 开发助手，是唯一权威的仓库开发地图。**任何代码变更导致本文件描述失真（文件作用、函数签名、桥协议、构建命令、关键实现落点）时，AI 必须在本轮同步更新本文件，并在文末「更新记录表」登记（时间 + 版本号）。** 变更未触及本文件描述范围时无需更新（避免无意义改写）。若发现本文件与源码不一致，以源码为准并当场修正本文件——不要忽略。
>
> **⚠️ 过期风险声明**：代码演进可能快于文档更新，本文件内容可能过时；一切以源码为准。本项目处于 0.13.0 大版本开发期，文件变动频繁。

---

## 1. 仓库概览与技术栈

- **角色**：DeepSeek Harness 安卓壳应用（英文名 dsh-mobile-apk，包名 `com.dsharnessmobile.shell`）。
- **职责边界**：只保留安卓平台权能与桥——前台服务、看门狗、WebView、存储访问框架（SAF）桥、快照解压与校验、在线更新、崩溃标记与测试界面、内置控制台、日志收集与导出。**AI 可见能力全部来自 DeepSeek Harness 插件**（插件仓库见下方兄弟仓库）。
- **运行时形态**：壳应用进程树内嵌 Termux 运行时快照（`assets/snapshot.tar.xz`，解压至 `files/usr` + `files/home`）；引擎进程（Node.js `@deepseek-ai/dsh`，**基线 0.1.1-rc.2**）监听 `127.0.0.1:3080`；WebView 加载该引擎 Web UI。
- **构建链**：minSdk 26 / **targetSdk 34**（保持 34 以保留应用数据目录内二进制直接执行，见 build.gradle.kts 注释）/ compileSdk 36；Kotlin 2.0.21；AGP 8.8.2；Java 17。
- **依赖库**：androidx.activity-ktx 1.10.1、androidx.core-ktx 1.15.0、commons-compress 1.28.0、xz 1.10、Shizuku api/provider 13.1.5（仅阶段 1 检测，无提权动作）。
- **兄弟仓库**（本协调仓库 D:\coding\dsh-mobile 下的子目录）：`dsh-shell-termux`（Termux bash 执行器插件）、`dsh-client-ui-responsive`（移动形态注入层）、`dsh-host-web-compat`（页面注入脚本）、主仓库 `kelai141/dsh-mobile`（构建脚本/文档/发布；快照构建与门禁见其 `scripts/`）。
- **上游** `deepseek-ai/deepseek-harness`（本地 checkout 于 `dsh/`）：只读参考，**零改动**；一切适配以补丁层/插件/壳侧实现。

## 2. 构建与验证命令（在 dsh-mobile-apk 目录执行）

```powershell
# 本地完整构建（需要 assets/snapshot.tar.xz；缺失时构建失败并给指引）
./gradlew :app:assembleDebug --no-daemon -PversionNameSuffix=""   # 正式版（默认无后缀）
# 快照测试版：-PversionNameSuffix="-SN-1-RC8"（versionCode 不变，产物名带后缀）

# 快照资产（由主仓库 scripts/ 提供）：
#   build-snapshot-arm64.mjs   主机侧组装 arm64 快照（Termux 官方源 .deb + readelf 验证）
#   make-snapshot.sh           设备侧打包（Termux 内执行，含 5 段产物级补丁）
#   assemble-arm64.py          A(arm64 原生层)+B(js 层)+C(dsh 层) 合成 arm64 快照
#   inject-snapshot.py         字节级 tar 流注入插件（保留 symlink 元数据）
#   ci-verify-snapshot.py      CI 门禁：ELF 架构/敏感内容/插件一致性

# 门禁与自检（主仓库 scripts/ 或本项目内）：
#   elf-check.mjs / check-contract.mjs / t0-check.ps1 / check-snapshot-secrets.ps1
```

**设备验证链路**（真机 arm64 vivo V2425A + MuMu x86_64 127.0.0.1:16416/7555）：
- `adb -s <serial> install -r -t app-debug.apk`（固定 debug.keystore 签名，升级兼容）
- 引擎探活：`adb forward tcp:9308 tcp:3080` → `http://127.0.0.1:9308/`
- 应用私有目录访问：`adb shell run-as com.dsharnessmobile.shell sh -c '...'`（MuMu 可 `adb root` 后直读）
- 网页调试：`adb shell "cat /proc/net/unix | grep webview_devtools_remote"` → `adb forward tcp:9225 localabstract:webview_devtools_remote_<pid>`（pid 每次重启变化）
- 主仓库提供：`device-smoke.ps1`、`e2e-phone-test.ps1`、`t0-check.ps1`、`dump-*.ps1` 系列。

## 3. 目录与源文件作用（每个文件一段；关键函数带代码位置）

`app/src/main/java/com/dshmobile/shell/`：

| 文件 | 作用 | 关键点（2026-08-21，版本 0.13.0） |
|---|---|---|
| **EngineManager.kt**（589 行） | 引擎进程总管：快照解压触发、指纹校验、环境注入（Termux 动态库/预加载）、进程启动终止、**运行补丁（applyRuntimePatches）** | `applyRuntimePatches()` L366：把 `assets/patched/*` 覆盖到快照对应位置（内容指纹，非固定标记）；`applyAssetPatch` L392：目标包缺席时跳过（依赖树变化安全）；`startEngine()` L432：LD_PRELOAD 缺失时大声失败；`shellEnv`：引擎环境注入（PATH/LD_LIBRARY_PATH/HOME/DSH_HOME/TERMUX_*，与 make-snapshot.sh 环境一致）；补丁清单见方法注释 L349-365 |
| **EngineService.kt**（104 行） | 前台服务（notification id=2）＋引擎看门狗 | `ensureEngine()` L61：**F2 缺口——引擎已在运行时直接 return、不装看门狗（0.13.0 要修）**；看门狗 5s 周期 L67；`requestShutdown()` L52 用户主动停机 |
| **MainActivity.kt**（1595 行） | 壳主界面：WebView 承载引擎 UI；启动/测试双态界面（解压进度、崩溃横幅）；SAF 目录选择桥；图片选择桥；下载；导出；沉浸式/字体；前台监控 | `onCreate` L381；`configureWebView()` L566；`pickDirectoryWithPermissionCheck` L709；`pickImageForBridge` L281；`downloadToDownloads` L774；`downloadDebugLogs` L944；`applyImmersive` L496；前台监控 3s 轮询 L81；页面启动看门狗（45s 冻结重载）与页面心跳 L147 |
| **UpdateManager.kt**（128 行） | 运行快照在线更新（第一版）：manifest{url,sha256,size} → 下载校验 → 暂存解压 → `usr→usr-old` 两步切换 → 杀引擎（看门狗重启）→ 写 `.snapshot-fingerprint` | `checkAndApply()` L28；**0.13.0 要改：切换后立即 `deleteRecursively(old)`（L67）→ 应保留旧代、探活确认后清理** |
| **SnapshotExtractor.kt**（99 行） | 共享快照解压（x-zip tar → filesDir，owner-only 权限、symlink 保留）＋ 可执行文件 `security.android.exec` 属性戳印 | `extract()` L30（bundled 与在线更新共用）；`stampExecAttribute` L81（setfattr 批量 64） |
| **AndroidBridge.kt**（183 行） | JS 桥 `window.androidBridge`（协议 v1）：目录选择、图片选择、字体/沉浸式、剪贴板、通知、调试日志、全文件访问、引擎重启、控制台、外部打开 | `resolvePickedPath()` L164（OpenDocumentTree → 真实路径映射，含 `..` 拒绝）；`getPickToken()` L106（一次性 token 鉴权）；`showNotification()` L52（F0.3 通知桥已存在雏形） |
| **EngineProbe.kt**（33 行） | `127.0.0.1:3080` HTTP 探活（{running, latencyMs, error}，800ms 超时） | `check()` L17 |
| **ShizukuSupport.kt**（32 行） | Shizuku 阶段 1：仅绑定检测与状态文案；**无任何提权动作**（0.13.0 以内嵌 ADB 客户端替代） | `isAvailable()` L16 |
| **LogCollector.kt**（163 行） | 开发调试日志收集（设置 → 开发者选项开关，默认关）：logcat(本进程 pid)+engine.log 增量尾 → 日文件（5MB 轮转） | `start/stop` L41/L52；`log()` L64 关键事件直写；输出到 Documents/dshdata/log/ 或 filesDir/log/ |
| **ConsoleActivity.kt** / **ConsoleSession.kt** | 内置终端控制台：快照 bash 交互（引擎死机时也可用） | ConsoleSession：环境与引擎一致（PATH/LD_LIBRARY_PATH/HOME/DSH_HOME/TERMUX_*），进程随 Activity 死亡 |
| **GuideChrome.kt**（430 行） | 启动/测试界面 UI 组件库（v0.11+ teal 双主题 token、gauge 输入流动画组件） | 解压进度条、崩溃横幅渲染 |
| **DsUi.kt**（68 行） | UI 基元工具（渐变/ripple/进度条 drawable 等） | — |

`app/src/main/assets/`：`snapshot.tar.xz`（运行时快照，架构相关）、`snapshot.sha256`（快照指纹，构建时写）、`patched/*`（运行补丁：primitives/attachment-local/fs-local/session-persistence-jsonl/web-frontend 覆盖文件）、`console.html`。

## 4. 链接桥说明（壳应用 ↔ 引擎/页面）

| 层 | 通道 | 鉴权/语义 |
|---|---|---|
| 页面 → 壳（`window.androidBridge`） | 目录选择（pickDirectory+pickToken 一次性鉴权）、图片选择、字体/沉浸式、剪贴板、通知、调试日志、全文件访问、引擎重启、控制台、外部打开（openNativePath） | 目录/图片回调带 callbackId；**失败关闭**：引擎未运行时返回错误而非空转 |
| 壳 → 引擎（HTTP 127.0.0.1:3080） | 引擎 RPC（pick 端点 `/api/android/dir-pick/*` 等） | pick token 校验；仅本机回环 |
| 壳 ↔ 插件（服务面） | host-web-compat 注入 `window.__dshBridge`（onDirectoryPicked 等）；ui-responsive 注入 CSS/JS | 幂等守卫 `if(window.__dshBridge){return}`；注入脚本模板字符串必须双写反斜杠（见 §6） |

桥协议总原则：**桥只做能力，策略在插件**（洋葱原则）；平台桥最小化、鉴权、失败关闭。

## 5. 关键实现细节

- **保活看门狗**：前台服务（START_STICKY）+ 5s 引擎探活（EngineService.kt L67）+ 页面冻结看门狗（45s 判冻结重载，MainActivity.kt L147，进程内仅一次）+ 启动 30s 超时 + 3s 前台监控（MainActivity L81）。
- **崩溃标记**：MainActivity L74 未捕获异常摘要写标记文件，下次启动测试界面提示（不吞异常）。
- **指纹重解压**：assets/snapshot.sha256 对比 `.snapshot-fingerprint`（在线更新写入）；不一致 → 重新解压 assets 快照。
- **在线更新原子切换**：UpdateManager `usr → usr-old → 暂存→usr` 两步改名；0.13.0 改保留旧代。
- **运行补丁（重要概念）**：`assets/patched/*` 用**内容指纹**（非 marker 字符串）判断是否应用——asset 更新即重打，修复 v1→v2 补丁不生效的坑（0.12.5-fx-1）。
- **Android 限制**：app 域禁止 link(2)（SELinux）→ fs-local/session-persistence-jsonl 补丁回退 rename；快照解压后执行文件必须带 `security.android.exec` 属性（API 35+ 检查）。

## 6. 常见坑与修复记录（本仓库相关）

1. **模板字符串反斜杠**（host-web-compat，本仓库不涉及但联调常见）：注入脚本模板内 `\n`/`\/` 会在 bundle 求值时被吞，必须双写 `\\n`/`\\/`；验证对"模板求值后"的内容做语法检查。
2. **run-as 引号地狱**：PowerShell 双引号内 `$var` 会被本地展开；设备端 sh 脚本用单引号包裹；`$U=...` 赋值在 PowerShell 需 `$` 前缀。
3. **adb 二进制传输**：PowerShell 管道会破坏二进制；用 WSL `adb exec-out ... > file`（二进制安全）或 base64 中转。
4. **链接失败 EACCES**（#77）：commitPreparedImageFile 的 link(2) 在 app 私有目录被 sepolicy 拒绝 → lstat 确认目标不存在后 rename 兜底。
5. **签名一致性**：必须用仓库 `keystore/debug.keystore`（CI 与本地同签名），否则覆盖安装失败（INSTALL_FAILED_UPDATE_INCOMPATIBLE）。
6. **快照与补丁布局漂移**：rc.8→rc.2 迁移时 dsh-client-ui-primitives 从依赖树消失——补丁应用器对缺包自动跳过（EngineManager L396）。
7. **MuMu 与真机差异**：MuMu 可 `adb root` 直读 app 私有目录；真机必须 run-as；MuMu 无 SELinux 限制、内核不检查 exec 属性。

## 7. 0.13.0 进行中事项（本文件随代码变更同步更新）

- EngineService L62（引擎已运行不装看门狗）→ 修复：任何状态都装看门狗（PRD F2-4）。
- UpdateManager L67（切换后立即删旧代）→ 保留旧代、探活确认后清理，纳入最后已知良好状态语义（PRD F3.2/F1.10）。
- 新增：初始配置向导、ADB 授权桥（三道门）、审计、进度通知桥（F0.3）、日志一键导出（F6.1）、开机自启接收器、前台唤醒锁、深度探活、熔断退避、文件直达意图过滤器（F5）、引擎更新 UI（F1.10）。
- 兄弟插件仓库（D:\coding\dsh-mobile 子目录）为 0.13.0 新增与改造对象。

---

## 更新记录表

| 时间 | 版本 | 更新内容 | 更新者 |
|---|---|---|---|
| 2026-08-21 | 0.13.0 | 首版创建：AGENT.md 规范落地（PRD F6）；逐文件职责与代码位置截至 dsh-mobile-apk main@5679e59（0.12.5-fx-1） | AI 开发助手 |
