# AGENT.md — dsh-mobile-apk 开发地图

> **⚠️ AI 主动更新条款（必须最先执行）**：本文件面向人类与 AI 开发助手，是唯一权威的仓库开发地图。**任何代码变更导致本文件描述失真（文件作用、函数签名、桥协议、构建命令、关键实现落点）时，AI 必须在本轮同步更新本文件，并在文末「更新记录表」登记（时间 + 版本号）。** 变更未触及本文件描述范围时无需更新（避免无意义改写）。若发现本文件与源码不一致，以源码为准并当场修正本文件——不要忽略。
>
> **⚠️ 过期风险声明**：代码演进可能快于文档更新，本文件内容可能过时；一切以源码为准。

---

## 1. 仓库概览与技术栈

- **角色**：DeepSeek Harness 安卓壳应用（包名 `com.dsharnessmobile.shell`）。
- **职责边界**：只保留安卓平台权能与桥——前台服务、看门狗、WebView、SAF 桥、快照解压与更新、崩溃回退闸门（UndoGate）、ADB 授权原生写面（AdbState）、审计、内置控制台、日志。**AI 可见能力全部来自插件**。
- **运行时形态**：壳内嵌 Termux 运行时快照（`assets/snapshot.tar.xz` → `files/usr` + `files/home`）；引擎（Node.js `@deepseek-ai/dsh`，基线 0.1.1-rc.2）监听 `127.0.0.1:3080`；WebView 加载引擎 Web UI。
- **构建链**：minSdk 26 / targetSdk 34 / compileSdk 36；Kotlin 2.0.21；AGP 8.8.2；Java 17。
- **依赖**：androidx.activity-ktx / core-ktx、commons-compress、xz；Shizuku 零依赖反射（ShizukuSupport.kt，仅探活示例）。
- **兄弟仓库**（协调仓库 `kelai141/dsh-mobile` 下的子目录）：`dsh-shell-termux`（Termux 执行器）、`dsh-client-ui-responsive`（移动 UI 注入层 + F5 消费端）、`dsh-host-web-compat`（页面注入/兼容）、`plugins/`（dsh-android-bridge / -manage / -linux-env / -file-open，协调仓库内）、`vendor/`（dshmarketplace-plugin、dsh-undo-savepoint 固化副本 + PATCHES.md）。
- **上游** `deepseek-ai/deepseek-harness`（本地 checkout `dsh/`）：只读参考，**零改动**；一切适配以补丁层/插件/壳侧实现。

## 2. 构建与验证命令

```powershell
# 一键双 ABI（协调仓库根；快照→注入→门禁→gradle→out/）：
pwsh -File scripts\build-apk-013.ps1 -Suffix ""          # 产物 out\v0.13.0\dsh-mobile-apk-v<ver>-<abi>.apk
# 快照（Termux 源 + TARGETS 预装 + licenses + pnpm 装配 + 瘦身 + 归档）：
node scripts\build-snapshot-013.mjs <arm64|x86_64>
# 插件单测/冒烟：
node scripts\smoke-bridge.mjs                             # bridge 18 断言
cd ..\dsh-client-ui-responsive && npm test && npm run build
cd ..\plugins\dsh-android-<pkg> && npm run build
```

**门禁（build-apk-013.ps1 内）**：marketplace 修复校验（patch-marketplace.mjs）→ undo 移动端裁剪校验（patch-undo-mobile.mjs）→ 快照注入（inject-snapshot.py/inject-external-plugins.py）→ 权威 patch 覆盖（update-snapshot-patch.py）→ 挂载集⊇注入集（check-patch-mounts.mjs）→ 🔒机密（check-snapshot-secrets.ps1）→ **第三方合规（check-third-party.mjs，GPL 义务）** → elf-check → 许可资产拷贝（LICENSES → assets/licenses）→ gradle。

**设备验证链路**（真机 arm64 vivo V2425A `10AF2B0GN0001F2`；模拟器 MuMu x86_64 `127.0.0.1:16416/7555`）：
- 安装：`adb -s <serial> install -r -t out\v0.13.0\...apk`（同签名 debug.keystore；**指纹变更触发 refreshSnapshot 全量重解压 ≈2-4 分钟，勿在解压中杀进程**）。
- 引擎探活：`adb -s <serial> forward tcp:23080 tcp:3080` → `http://127.0.0.1:23080/`。
- WebView 调试：`adb shell "cat /proc/net/unix | grep webview_devtools"` → `forward tcp:29225 localabstract:webview_devtools_remote_<pid>`（**每次重启 pid 变**）→ CDP ws 连接后 Runtime.evaluate 驱动（例子脚本见 `.deploy-tmp/cdp-*.mjs`；断言注意 input placeholder 不在 innerText 里）。
- 远程 RPC（测试面）：POST `/api/<method>`，body 必须全信封 `{"type":"client-request","rpcId":"r1","method":"session.list","payload":{}}`；`session.prompt` 拒绝 live 会话（被 UI 打开的）——直接 API 测代理需先用 session.create 建全新会话。

## 3. 目录与源文件作用（关键函数带代码位置；文件行数随版本变化，以函数名为准）

`app/src/main/java/com/dshmobile/shell/`：

| 文件 | 作用 | 关键点（0.13.0 定稿） |
|---|---|---|
| **AdbState.kt** | ADB 授权单一事实来源 + **真实通道**（内嵌 termux android-tools adb 36） | `pairWithCode(code,pairPort,connectPort)`：真执行 `adb pair 127.0.0.1:<port> <code>`（**码值只进 argv**，审计只记 codeLength；配对成功才写 paired+端口）；`revokePair`：disconnect+删 adbkey+清 paired（系统侧授权需无线调试重开才彻底清除——设置页文案说明）；`adbShellExecute` 真实 shell（uid=2000，失败关闭+幂等重连）；prefs 键 allowSwitch/paired/pairPort/connectPort/connected；`runAdb` 用 engine.shellEnv()+OPENSSL_CONF 覆盖（同 UndoGate 修复） |
| **FileIncoming.kt** | F5 文件直达：校验/净化/拷贝/元数据/清理 | `copyIn` **200MB 有界拷贝**（R17）；`sanitizeName/uniqueName/validate`；`tmpWorkspace=files/home/.dsh/workspaces/incoming`；`cleanupTmp` 生命周期礼仪 |
| **UndoGate.kt** | 崩溃自动回退（F3）：看门狗连续失败→急救 CLI restore-last-good | `runCli` **必须注入 `OPENSSL_CONF=<usr>/etc/tls/openssl.cnf`**（快照 node 编译期 cnf 路径不可读→无输出→误判无快照）；幂等标记 `.undo-auto-done` |
| **EngineManager.kt** | 引擎总管：解压/指纹/环境/进程/补丁 | `shellEnv()`：PATH/LD_LIBRARY_PATH/HOME/DSH_HOME/TMPDIR/LD_PRELOAD(+termux-exec force)/TERMUX__PREFIX/SSL_CERT_FILE/DSH_ADB_*/DSH_ADB_FULLACCESS（=壳侧 fullAccess() 同源）/密钥注入；`refreshSnapshot` 指纹差异→备份→重解压→还原用户数据；`killExistingEngine`（destroyForcibly+pkill bin.js）；90s 冷却窗探活绕过 |
| **EngineService.kt** | 前台服务 + 看门狗 | watchdog 5s 探活 + UndoGate 触发 + 唤醒锁续期/释放 + onTaskRemoved 清理（F5 生命礼仪） |
| **MainActivity.kt** | 主界面/桥接线/意图处理 | `maybeProcessIncoming`（VIEW/SEND→FileIncoming→POST /api/android/file-incoming）；AndroidBridge 接线含 `onSetAdbPair={code,pairPort,connectPort->AdbState.pairWithCode}`；`onRevokeAdbPair`、`onAdbShell` |
| **AndroidBridge.kt** | `window.androidBridge` 协议 v1 | `setAdbPair(code,pairPort,connectPort):Boolean`（**3 参**）、`getAdbState()`、`adbShell(cmd)`、`requestAllFilesAccess/hasAllFilesAccess`、`pickToken` 鉴权、`openNativePath`（FileProvider 白名单） |
| **SnapshotExtractor.kt** | tar 解压（x-zip→filesDir、symlink、exec 属性戳印）+ **zip-slip 防护**（resolveEntry 拒绝 .. / 绝对路径 / 越界 symlink） | `extract()` |
| **UpdateManager.kt** | 在线快照更新（第一版） | usr→usr-old 两步切换 + 指纹写 |
| **WatchdogV2.kt** | 引擎看门狗（v2） | 连续失败熔断；boot 恢复用户同意状态 |
| **ShizukuSupport.kt** | Shizuku 反射探活（仅示例；真实通道走 adb 二进制路线，Shizuku 源码作参考存主仓库 .deploy-tmp/shizuku-adb/） | — |
| **ConsoleActivity/ConsoleSession** | 内置终端 | 环境与引擎一致 |
| **LogCollector.kt** | 调试日志收集 | 日文件轮转；审计另见 AdbAudit（files/audit/audit.ndjson） |

`app/src/main/assets/`：`snapshot.tar.xz`、`snapshot.sha256`、`undo-emergency.mjs`（急救 CLI，UndoGate 用）、`licenses/`（LICENSES 标准文本 + THIRD_PARTY_NOTICES.md，GPL 合规 A2）、`console.html`。

## 4. 桥与通道说明

| 层 | 通道 | 语义 |
|---|---|---|
| 页面 → 壳 | `window.androidBridge` | ADB 授权变更**唯一**入口（setAdbAllow/setAdbPair/revokeAdbPair——被提权方不得自改授权，Shizuku 对照）；目录/图片 pick（token）；全文件访问；重启/控制台 |
| 壳 → 引擎 | HTTP 127.0.0.1:3080 | 文件直达 POST；pick 端点；**只读**状态端点（/api/android/privilege/status） |
| 引擎 → 插件 | cordis 服务面 | androidPrivilege（状态机/execAdbShell/execAdbLine/gateFor 会话级 danger）；dsh-shell-termux 执行器 |
| 插件 → 页面 | dsh.client 模块 + slots | ui-responsive（AppFrame/DevSection/settings.dev.item/F5 消费端轮询）；bridge client（AdbAuthSection 双端口配对 UI）；undo/marketplace 注册 |

**授权模型（定稿）**：引擎级 = 门1 All Files Access（DSH_ADB_FULLACCESS，重启生效）+ 门2 允许开关（live prefs）+ 门3 真实配对（adb pair 握手）；会话级 = `gateFor(exec.agent.session)` 实时 resolve，**ADB 能力（含观察类）仅 danger-full-access**，自动审批不参与；写面唯一在壳侧原生 AdbState（桥/引擎只读 live `dsh-adb.xml`）。

## 5. 关键实现细节与坑（每次踩坑必须登记）

1. **realpath 前缀混用（B7 运行时表现，已修）**：Android 上 `/data/user/0` 可能是 `/data/data` 的软链——只把「文件侧」realpath 后再与未 realpath 的 ws 比较必拒。修复：`safeResolveInside` **两侧都 realpath**（ws 侧失败按原样参与），symlink 目标按 `dirname(rel)` 解析（`../../LICENSES` 从 `doc/<pkg>/` 出发 = `share/LICENSES`）。**新路径校验代码一律双侧规范化。**
2. **会话 header meta 白名单**：`Session.create(meta)` 的 `origin` 只允许 `"subagent"`——自定义值报 `session header origin must be "subagent"`；只写白名单键（如 `cwd`）。
3. **surface 事件必须带 surfaceOp**：`user/message` 等 surface-eligible 事件 append 需第 3 参 `{surfaceOp:'append'}`，否则 `requires a surfaceOp marker`。
4. **pnpm 是市场安装的硬依赖（已固化进快照构建）**：`dsh plugin add` spawns `pnpm`（apps/cli plugin.ts）；快照缺 pnpm → `pnpm not found on PATH`；且**陈旧 pnpm 状态记录**（base-dsh 里的 `.modules.yaml`/`.pnpm-workspace-state`/`pnpm-lock`）指向旧 store → `ERR_PNPM_UNEXPECTED_STORE`——build-snapshot-013.mjs 已装配 pnpm 10.12.1 standalone（npm tgz + `usr/bin/pnpm` shim）并清理这三种记录。**市场目录里的部分插件（如 humanizer-ru）不在 npm，安装 404 属上游目录数据，不是本链路缺陷。**
5. **快照 node 需要 OPENSSL_CONF**：运行快照内 node 时务必与 UndoGate 同样注入（否则 OpenSSL config error 静默吞 CLI 输出）。
6. **Windows 侧读 WSL 9p 文件 = EACCES**：stage 文件不可直 stat/read；校验类代码走 `--tar`（wsl tar -tvf 带大小）视图；wsl.exe 输出前有 localhost 代理噪音行，解析时过滤。
7. **npm registry 元数据可能缺 dist.sha512**：pnpm tgz 完整性校验「在场则严格，缺席降级警告」。
8. **run-as 引号地狱 / adb 二进制传输**：PowerShell 双引号内 `$var` 本地展开；二进制经 `adb exec-out`/push 传输。
9. **template 字符串反斜杠**（页面注入）：`\n` 双写。
10. **签名一致性**：debug.keystore 固定，否则覆盖安装失败。
11. **CDP 断言注意**：input placeholder 不在 innerText（查 `[placeholder]`）；「live 会话禁止 API prompt」；`session.list` 的 stats 字段（turns/llmMs）判断代理是否真跑。

## 6. GPL 合规（2026-08-23 定稿）

- 快照包：`usr/share/doc/<pkg>/copyright`（多数为软链 → `usr/share/LICENSES/<fam>.txt`）或 COPYING* 实体文件；`licenses` 包在 TARGETS 显式锁定（x86_64 曾漏带）。
- 仓库：`LICENSES/`（GPL-2.0/3.0、LGPL-2.1/3.0 全文）+ `THIRD_PARTY_NOTICES.md`（80 组件矩阵，含源码要约与再加工工具清单）+ `scripts/third-party-licenses.json` + `scripts/check-third-party.mjs`（矩阵覆盖 + copyleft 全文在场，三形态判定）；门禁接入 build-apk-013.ps1，缺失即拒打包。
- APK：`assets/licenses/`（LICENSES + notices，随包分发）。
- 声明文：`docs/RELEASE.md §7`（D 章合规声明 + 源码要约 + 修改工具）。

## 7. 待办与已知缺口（非本轮范围，记录防止再探）

- F2「T1 授权豁免自动升级」未落地（电池白名单仅引导 Intent；指数退避仅日志不改调度）——涉及系统策略写面，不自动执行。
- F1.10 引擎更新通道未实现；F0.3 引擎事件桥未实现。
- 子代理 PRD 评审完整清单见协调仓库 `docs/review-0.13.0-20260823.md §九` 与 `.deploy-tmp/prd-gap-review.md`（U4/U5、A4/A6/A8、B4/B5/B7、F4、P4 未修项）。

---

## 更新记录表

| 时间 | 版本 | 更新内容 | 更新者 |
|---|---|---|---|
| 2026-08-21 | 0.13.0 | 首版创建：AGENT.md 规范落地（PRD F6）；逐文件职责与代码位置截至 dsh-mobile-apk main@5679e59（0.12.5-fx-1） | AI 开发助手 |
| 2026-08-23 | 0.13.0 | **重构为便利开发维护版**：真实 ADB 通道（AdbState 配对/端口/密钥/审计 + OPENSSL_CONF 坑）、F5 消费端（FileIncoming 200MB 上限）、构建链全景（快照 TARGETS/licenses/pnpm + 全门禁清单 + APK 产物路径）、合规 D 章、11 条坑记录（realpath/pnpm/header 白名单/surfaceOp/9p 权限/信封式 RPC 等） | AI 开发助手 |
