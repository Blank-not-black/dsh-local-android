# DSH for Android 当前技术设计

> 状态：当前实现规范。本文描述 `dsh-local-android`，不是上游
> `dsh-mobile-apk` 的原始设计文档。

## 1. 目标与边界

本项目是独立的 Android 本地 DSH 发行版。它以
[dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk) 的 Android 壳和内嵌运行时为底座，
再接入 dsh-Remote 的 gateway 与 Web UI。上游基线、复制路径和许可证信息集中记录在
[`UPSTREAM.md`](../UPSTREAM.md)。

目标是让用户在 Android 本地完成 DSH 的安装、启动、文件访问和 Web UI 交互，同时保持现有 DSH
API/WebSocket 协议可复用。第一阶段不追求把 WebView 与后端重写成一套新的 Native Bridge 协议。

当前边界：

- Engine、Local Gateway 和 UI 是三个独立运行层；任何一层不得越权管理另一层；
- 所有网络通信仅走 `127.0.0.1`，不把本地版本作为远程控制服务暴露；
- Android 壳负责进程、权限、生命周期和系统文件选择；gateway 负责协议代理和 Web UI 资源；
- 不承诺所有桌面 DSH 插件都能在 Android 运行，插件兼容性需要单独验证；
- 运行时快照不提交 Git，构建时必须使用与设备 ABI 匹配的快照。

默认交付的是 minimal profile。它只包含 DSH 后台启动、终端命令执行、Web 兼容、响应式 UI、默认模型
和系统目录选择所需的运行时内容。编译工具链、脚本语言、包管理器、ADB、编辑器、附件格式/OCR、市场、
撤销和外部文件打开等内容作为后续 capability pack 处理，不进入基础四层的启动条件。

## 2. 四层架构

```text
┌──────────────────────────────────────────┐
│ Install / detection                      │
│ 快照、ABI、迁移、权限、首启状态            │
└──────────────────┬───────────────────────┘
                   │ InstallReady
┌──────────────────▼───────────────────────┐
│ DSH Engine                               │
│ dsh web :3080、EngineService、watchdog    │
└──────────────────┬───────────────────────┘
                   │ EngineReady
┌──────────────────▼───────────────────────┐
│ Local Gateway                            │
│ dsh-Remote gateway/UI host、API/WS proxy  │
│ :8787，仅回环                              │
└──────────────────┬───────────────────────┘
                   │ GatewayReady
┌──────────────────▼───────────────────────┐
│ DSH for Android UI                       │
│ WebView、用户交互、Android bridge          │
└──────────────────────────────────────────┘
```

### 2.1 安装 / 检测层

入口是 `AndroidInstallBackend`。它负责：

- 检查运行时快照是否存在、完整且与当前 ABI 匹配；
- 解压或迁移应用私有运行时目录；
- 准备用户明确授予的文件访问能力；
- 返回可供 Engine 使用的运行时目录。

该层只准备资源和权限，不启动 Engine 或 Gateway。失败时停留在检测界面，错误归因必须指向安装层。

### 2.2 DSH Engine 层

入口是 `AndroidEngineBackend`，生命周期实现分布在 `EngineManager.kt`、`EngineService.kt`、
`EngineProbe.kt` 和 `WatchdogV2.kt`。它负责启动：

```text
dsh web --port 3080 --no-open
```

Engine 只监听 `127.0.0.1:3080`，由前台服务和看门狗负责保活、重启与 `engine.log`。Engine 不感知
Local Gateway 的实现，也不加载 WebView 页面。

嵌入式可执行文件统一经过 `EmbeddedProcess.kt` 启动：优先直接执行，在 Android 对应用私有 ELF
有限制的环境下回退到 `/system/bin/linker64`。Engine 与 Gateway 使用同一启动基础设施，但使用
不同的管理器和日志文件。

### 2.3 Local Gateway 层

入口是 `LocalGatewayManager.kt`。它将 `gateway/` 下的 gateway、统计模块和 `public/` UI 部署到
应用私有目录，然后以本地模式启动 `gateway.js`：

```text
HOST=127.0.0.1
PORT=8787
DSH_UPSTREAM=http://127.0.0.1:3080
DSH_REMOTE_LOCAL=1
```

Local Gateway 负责：

- 承载 dsh-Remote Web UI；
- 将 DSH API 和 WebSocket 代理到 Engine；
- 提供受本地工作区约束的文件接口；
- 提供 `/health`，供 `GatewayProbe` 判断 UI 是否可以接管；
- 写入独立的 `gateway.log`。

本地模式会关闭远程服务器、多服务器 Token 配置、网络轮询以及公网公告/更新检查。保留会话、实时消息、
审批、工作区和统计等 UI 所需能力。网关层不负责启动 DSH Engine，也不直接调用 Android 权限 API。

### 2.4 UI 层

`MainActivity.kt` 在 `GatewayReady` 之后才加载：

```text
http://127.0.0.1:8787/?local=1
```

`gateway/public/app.js` 根据 `local=1` 隐藏远程配置和网络相关入口，并连接本地 gateway。UI 只能通过
现有 Web API 和 `window.androidBridge` 使用后端能力，不得自行拉起 DSH 进程或绕过 gateway 访问内部端口。

模型配置继续遵循这条边界。设置页使用 `llm.providers`、`settings.describe`、`credentials.*`、
`settings.mutate` 和 `llm.discoverModels` 完成提供方、凭据、API 地址和模型目录管理；API 密钥只以脱敏状态
回显，不能进入 localStorage、URL 或日志。Android 壳不解析 DSH 配置文件，也不代替 Engine 执行配置变更。

### 2.5 Minimal profile 与可选能力

`runtime/minimal/cordis.patch.yml` 是默认 profile 的唯一基线。它关闭基础 bundle 中会与 Android 适配层冲突的
四个入口，再启用：

- `@dsh-android/dsh-shell-termux`；
- `@dsh-android/dsh-host-web-compat`；
- `@dsh-android/dsh-client-ui-responsive`；
- DSH 默认模型和系统目录选择入口。

这四个关闭项（`bash-sandbox`、`ui-layout`、`agent-default-model`、`directory-picker`）不是可选能力，不能
在 minimal 裁剪时删除；它们用于避免重复注册和保持本地目录选择的单一实现。

完整快照通过 `scripts/build-minimal-snapshot.sh` 生成 minimal 快照。脚本同时移除编译器/链接器、
Python/Perl/Ruby、npm/pnpm、ADB/device image 工具、编辑器/网络诊断以及 profile 中的附件、OCR、
市场、撤销、Android 管理等插件。脚本保留 Node、bash、核心文件工具、TLS/HTTP 所需内容和 DSH 全局
模块；不要通过删除基础运行库来进一步追求体积。

可选 pack 必须满足三条规则：只能在安装/升级阶段叠加，不能改变 INSTALL → ENGINE → GATEWAY → UI
顺序；自身依赖与许可证必须单独登记；必须有静态边界测试和启用后的运行测试。这样删除可选能力不会让
基础 DSH 后台失去启动能力。具体能力包清单见 [`OPTIONAL_COMPONENTS.md`](OPTIONAL_COMPONENTS.md)。

## 3. 启动状态机

`BackendSupervisor` 是唯一的启动编排器，顺序固定为：

```text
INSTALL
  │ success
  ▼
ENGINE ── probe 127.0.0.1:3080 ── success
  │
  ▼
GATEWAY ── probe /health?probe=live ── success
  │
  ▼
UI
```

失败会产生带有 `BackendStage` 的结果，guide view 根据阶段展示对应日志。重试从失败阶段重新执行，
而不是让 UI 直接重启全部服务。启动成功后，EngineService/WatchdogV2 只维持 Engine；前台监控会分别
探测 Engine 和 Gateway，任一层失效时回到 guide view。Gateway 的重新进入仍通过四层启动流程处理，
不应通过修改 EngineService 把两者重新耦合。

## 4. 进程与数据布局

```text
MainActivity / WebView
        │
        ├── LocalGatewayManager
        │     └── node gateway.js (:8787)
        │             └── DSH_UPSTREAM (:3080)
        │
        └── EngineService / EngineManager
              └── embedded node dsh web (:3080)
```

主要日志和运行时数据均位于应用私有 `filesDir`：

- Engine 日志：`engine.log`；
- Gateway 日志：`dsh-local-gateway/gateway.log`；
- 快照解压目录：上游运行时管理器使用的应用私有目录；
- Gateway 部署目录：`filesDir/dsh-local-gateway`；
- 本地 gateway 的文件根：由本地运行时工作区和 Android 授权共同约束。

应用重启时不依赖远程服务或公网连接。Gateway 的 token、端口和上游地址由本地启动配置生成，不能把
dsh-Remote 远程 token 作为 Android 用户配置要求。

## 5. Android 系统边界

- WebView 只访问 loopback gateway；
- 文件访问优先使用 SAF 选择结果，特殊的 All Files Access 由系统设置页明确授权；
- 通知、前台服务、屏幕常亮、沉浸式状态栏和调试日志通过 `androidBridge` 暴露；
- 内置控制台是排障入口，不属于 Engine/Gateway 的通信协议；
- Manifest 不注册绕过 `BackendSupervisor` 的直接启动路径；
- `MainActivity` 不在 Gateway 未就绪时加载 Web UI。

## 6. 源码映射

| 责任 | 当前实现 |
| --- | --- |
| 四层编排 | `app/src/main/java/com/dshmobile/shell/BackendSupervisor.kt` |
| 公共进程启动 | `app/src/main/java/com/dshmobile/shell/EmbeddedProcess.kt` |
| Engine 启动和探测 | `EngineManager.kt`、`EngineService.kt`、`EngineProbe.kt` |
| Gateway 启动和资源部署 | `LocalGatewayManager.kt` |
| Gateway 健康检查 | `GatewayProbe.kt` |
| UI 接管 | `MainActivity.kt`、`gateway/public/app.js` |
| 本地 gateway 适配 | `gateway/gateway.js`、`gateway/public/index.html` |
| 模型配置 UI | `gateway/public/app.js`、`gateway/public/index.html`、`tests/model-settings.test.mjs` |
| 分层日志 | `LogCollector.kt`、`engine.log`、`gateway.log` |
| 本地模式回归测试 | `tests/local-gateway.test.mjs`、`tests/ui-local-mode.test.mjs` |

## 7. 测试门禁

代码变更至少执行与变更层对应的测试：

```sh
# Kotlin / Android 层
GRADLE_USER_HOME="$PWD/.gradle-home" JAVA_HOME=/home/blank/Android/jdk21 \
  ./gradlew testDebugUnitTest --no-daemon

# Gateway / UI 层
node --test tests/*.test.mjs
node --check gateway/gateway.js
node --check gateway/public/app.js

# 所有文本和补丁
git diff --check

# APK 构建
GRADLE_USER_HOME="$PWD/.gradle-home" JAVA_HOME=/home/blank/Android/jdk21 \
  ./gradlew assembleDebug --no-daemon
```

设备验收顺序：干净安装 → 首启解压 → Engine 健康 → Gateway 健康 → WebView 接管 → 会话/实时消息 →
SAF 文件访问 → 引擎或 Gateway 单独崩溃恢复。没有连接 Android 设备时，只能报告构建和单元测试结果，
不能把 APK 构建通过当作真机启动通过。

当前实体手机构建使用 arm64 minimal 快照；x86_64 模拟器必须使用同 ABI 的 minimal 快照。默认快照不
加载 PDF.js；如果启用附件 pack，`@napi-rs/canvas`、`DOMMatrix`、`ImageData` 和 `Path2D` 警告仍然
只代表可选渲染路径，是否影响启动要以 Engine 与 Gateway 的分层健康检查和日志为准。

## 8. 上游与许可证

Android 壳和运行时改动必须以 `UPSTREAM.md` 记录的 dsh-mobile-apk 提交为基线，保留原作者版权和 MIT
许可。Gateway/UI 的导入文件来自 dsh-Remote，保留 `LICENSES/dsh-remote-MIT.txt`。运行时快照中的
第三方组件继续遵守各自许可证，声明位于 `app/src/main/assets/licenses/`。

任何新增复制代码、资源或依赖，都应在对应文档和许可证声明中说明来源；不要把上游历史文档中的版本号、
目录结构或构建脚本直接当作本项目当前事实。
