# AGENTS.md — dsh-local-android 开发指南

## 1. 项目定位

`dsh-local-android` 是 DSH 的 Android 本地发行版，应用名称为 `DSH for Android`，包名为
`com.dsharnessmobile.shell`。

本项目的 Android 运行时和壳层开发明确基于
[`kelai141/dsh-mobile-apk`](https://github.com/kelai141/dsh-mobile-apk) 的源码与实现，当前基线为：

```text
main @ 23f9cbb49aae2381c4820c4d1230780f2a7d5776
```

本项目不是上游项目的官方发行版。上游源码按 MIT 许可证使用，保留原版权声明和许可证；
快照中的第三方组件继续遵守各自许可证。完整来源记录见 `UPSTREAM.md`。

本项目同时导入 dsh-Remote 的 gateway 与移动 UI，使启动后的用户界面由我们维护：

```text
安装 / 检测 UI
      ↓ BackendSupervisor
DSH Engine（127.0.0.1:3080）
      ↓
Local Gateway（127.0.0.1:8787）
      ↓
DSH for Android UI（WebView）
```

四层职责必须保持分离：

- 安装层只负责快照、数据、权限和首启检测；
- Engine 层只负责 DSH 后台进程、看门狗、回滚和 `engine.log`；
- Gateway 层只负责本地 API/WS、文件接口、鉴权和 `gateway.log`；
- UI 层只负责 WebView 页面和用户交互，不直接启动任何后台进程。

`BackendSupervisor` 只负责按 INSTALL → ENGINE → GATEWAY → UI 顺序协调和归因失败。

默认运行时是 minimal profile：只保留 DSH shell、Web 兼容层、响应式 UI、默认模型和系统目录选择。
编译器/链接器、Python/Perl/Ruby、npm/pnpm、ADB 管理、编辑器、OCR/PDF/Office、市场、撤销和外部文件
打开属于后续 capability pack，不得作为默认启动依赖。裁剪脚本和 profile 基线分别位于
`scripts/build-minimal-snapshot.sh` 与 `runtime/minimal/cordis.patch.yml`。
可选能力包的依赖、许可证、体积和测试要求见 `docs/OPTIONAL_COMPONENTS.md`。

## 2. 目录与职责

```text
app/src/main/java/com/dshmobile/shell/
  MainActivity.kt              启动页、WebView、Android UI 桥
  BackendSupervisor.kt        四层启动契约与 Android 适配器
  EmbeddedProcess.kt           嵌入式 ELF 的 direct exec/linker64 回退
  EngineManager.kt             DSH 快照、环境和进程管理
  EngineService.kt             DSH 前台服务与 Engine 看门狗
  EngineProbe.kt               DSH 3080 探活
  LocalGatewayManager.kt       Gateway 资源部署与进程管理
  GatewayProbe.kt              Gateway 8787 探活
  LogCollector.kt              分层日志收集
  AndroidBridge.kt             window.androidBridge
  SnapshotExtractor.kt         快照解压和路径安全
  UpdateManager.kt              运行时更新
  ...                           SAF、通知、控制台、ADB、回滚等壳能力

gateway/
  gateway.js                   dsh-Remote gateway 本地模式导入
  gateway-stats.cjs            gateway 统计模块
  public/                      dsh-Remote UI 快照及其本地模式入口

tests/
  local-gateway.test.mjs       gateway 本地模式、健康、鉴权测试
  ui-local-mode.test.mjs       UI 本地模式静态契约测试
  minimal-profile.test.mjs     minimal profile 静态边界测试

app/src/test/
  .../BackendSupervisorTest.kt 安装、Engine、Gateway、UI handoff 测试

docs/
  LOCAL_ARCHITECTURE.md        当前四层架构与里程碑
  design.md                    当前 Android 壳与构建设计
```

`gateway/` 由 Gradle 作为 Android asset source 打包，运行时部署到
`filesDir/dsh-local-gateway/`，不直接从 APK 资产目录运行。

## 3. 启动契约

1. `AndroidInstallBackend` 检查快照指纹、解压运行时并部署急救脚本；失败时不得启动后台服务。
2. `AndroidEngineBackend` 启动 `dsh web --port 3080 --no-open`，等待 `EngineProbe` 就绪。
3. `AndroidGatewayBackend` 部署并启动 gateway，固定监听 `127.0.0.1:8787`，等待 `GatewayProbe` 就绪。
4. 只有前两层后端均就绪，`MainActivity` 才将 WebView 加载到本地 UI endpoint。
5. Engine 和 Gateway 的进程启动都必须经过 `EmbeddedProcess`，保持 Android app-private ELF 回退语义一致。

开机接收器不注册，避免绕过安装检测直接启动 `EngineService`。如果以后恢复开机后台运行，必须让
BootReceiver 启动协调器，而不是直接启动 EngineService。

## 4. 构建与测试

环境要求：JDK 17+、Android SDK API 36、Gradle 8.11.1 wrapper。当前开发机使用 JDK 21：

```bash
GRADLE_USER_HOME="$PWD/.gradle-home" \
JAVA_HOME=/home/blank/Android/jdk21 \
./gradlew testDebugUnitTest --no-daemon

node --test tests/*.test.mjs
node --check gateway/gateway.js
node --check gateway/public/app.js
git diff --check

GRADLE_USER_HOME="$PWD/.gradle-home" \
JAVA_HOME=/home/blank/Android/jdk21 \
./gradlew assembleDebug --no-daemon
```

Debug APK：`app/build/outputs/apk/debug/app-debug.apk`。

当前仓库 pin 的快照是实体手机用 arm64 minimal 版本，`app/src/main/assets/snapshot.tar.xz` 被
`.gitignore` 忽略，其哈希记录在 `app/src/main/assets/snapshot.sha256`。x86_64 模拟器必须换用匹配的
minimal 快照和哈希，不能混用。完整快照不随仓库提交；需要增补 capability pack 时，以完整快照作为输入
重新裁剪，不直接修改已生成的 minimal 产物。

本地机器的 `local.properties`、`.gradle-home/`、`app/build/` 和快照不提交。

## 5. 设备验证

设备验证必须和构建验证分开记录：

```bash
/home/blank/Android/Sdk/platform-tools/adb devices -l
/home/blank/Android/Sdk/platform-tools/adb install -r -t <arm64-apk>
```

安装后验证顺序：

1. 首启安装/解压页面；
2. Engine 是否监听 3080；
3. Gateway 是否监听 8787；
4. WebView 是否切换到我们的 UI；
5. 会话列表、消息实时流、审批和文件访问；
6. 杀掉 Engine/Gateway 后的恢复和日志归因。

没有 ADB 设备时，只报告 Node、JVM、APK 和资源打包验证，不报告真机通过。

## 6. 日志与故障归因

- DSH Engine 输出：`filesDir/engine.log`，启动页显示标签 `[dsh-engine]`；
- Local Gateway 输出：`filesDir/dsh-local-gateway/gateway.log`，启动页显示标签 `[local-gateway]`；
- Android 壳和生命周期事件：由 `LogCollector` 按天写入诊断日志；
- 若启用 PDF 可选包，`@napi-rs/canvas`、`DOMMatrix`、`ImageData`、`Path2D` 属于 PDF.js 运行时警告，除非 Engine 进程退出，不能直接判定为启动失败；默认 minimal 快照不加载该路径；
- Gateway 启动失败必须优先检查 `gateway.log`，再检查 Engine 是否真的监听 3080。

诊断导出不得包含 API Key、Token、凭据文件或不必要的完整会话内容。

## 7. 代码与许可证规则

- Android 壳层的新增实现必须注明其层归属，不把安装、Engine、Gateway 和 UI 逻辑混写；
- 复用 `dsh-mobile-apk` 的源码时保留 MIT 版权和许可证；
- 复用 dsh-Remote 文件时保留 `LICENSES/dsh-remote-MIT.txt` 及原文件声明；
- 新增运行时依赖时更新 `app/src/main/assets/licenses/` 和第三方说明；
- 不提交运行时快照、密钥、设备令牌和本地构建缓存；
- 修改本文件描述的文件、函数、构建命令或协议时，同一轮更新本文件并在文末更新记录登记。

## 更新记录

| 时间 | 版本 | 内容 |
|---|---|---|
| 2026-08-26 | 0.1.0-local | 从 dsh-mobile-apk 建立独立 Android 本地仓库，导入 dsh-Remote gateway/UI。 |
| 2026-08-26 | 0.1.1-local | 增加 BackendSupervisor 四层启动契约、分层日志和对应测试。 |
| 2026-08-26 | 0.1.2-local | 修复 Local Gateway 的 linker64 回退，统一 Engine/Gateway 嵌入式 Node 启动语义。 |
| 2026-08-26 | 0.1.3-local | 文档口径切换为 dsh-local-android：明确上游基础、四层结构、测试和设备验证边界。 |
| 2026-08-26 | 0.1.4-local | 增加可重复的 arm64 minimal 快照裁剪，默认 profile 与可选 capability pack 边界明确。 |
