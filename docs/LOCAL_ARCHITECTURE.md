# dsh-local-android 架构路线

## 目标

本仓库是一个独立的 Android 本地 DSH 发行版：复用 `dsh-mobile-apk` 的 Android 运行时底座，同时使用 dsh-Remote 的 gateway 和移动端 UI。dsh-Remote 主线继续负责远程控制电脑上的 DSH，本仓库不把本地运行时实验反向混入主线。

## 四层链路

```text
Install / detection screen
  └─ BackendSupervisor
       ├─ DSH Engine (127.0.0.1:3080)
       ├─ Local Gateway (127.0.0.1:8787)
       └─ UI handoff (WebView)
```

四层之间只通过明确的阶段契约传递状态。安装层不启动网络服务，Engine 层不管理 Gateway，Gateway 层不渲染 UI，UI 层不直接启动 DSH 进程。`BackendSupervisor` 只负责顺序、就绪等待和失败归因。两个后台进程共用 `EmbeddedProcess` 的启动基础设施，但不互相调用管理器。

第一阶段保留 HTTP/WebSocket 作为 WebView 与 gateway 的进程间通信方式。它们只在 Android 回环接口上传输，不承担远程访问职责，因此可以移除远程连接相关的复杂度，而不必立即重写前端协议。

默认构建使用 minimal arm64 运行时。基础 profile 只负责 DSH 启动、shell、Web 兼容、响应式 UI、默认模型
和系统目录选择；编译器、脚本语言、包管理器、ADB、编辑器、附件/OCR、市场、撤销和外部文件打开均属于
后续可选 capability pack。`scripts/build-minimal-snapshot.sh` 负责从完整快照生成可审计的 minimal 产物，
不把可选组件混入四层启动链；能力包边界见 [`OPTIONAL_COMPONENTS.md`](OPTIONAL_COMPONENTS.md)。

## 模块边界

### 1. 安装 / 检测层

负责快照完整性、ABI 对应、数据迁移、运行时解压、权限和首启状态。对应
`AndroidInstallBackend`。失败只归因到安装层，不启动 DSH 或 Gateway。

### 2. DSH 后台运行层

负责 `dsh web --port 3080 --no-open`、EngineService、看门狗、回滚和 `engine.log`。
它不启动 Gateway，也不决定 WebView 页面。

### 3. Local Gateway 层

负责把 DSH API/WS 代理到本地 `127.0.0.1:8787`、本地文件接口、鉴权和 `gateway.log`。
它只在 DSH Engine 就绪后由 `BackendSupervisor` 启动。

### 4. DSH for Android UI 层

负责启动成功后的 WebView 页面和用户交互。只有 Gateway 就绪后才接管页面；任何前置阶段失败
都停留在安装/检测界面，并显示对应层的诊断。

### 我们对 Android 底座的使用

沿用上游壳层的以下能力：

- 快照解压与 ABI 校验；
- DSH 引擎启动、停止、前台服务和看门狗；
- WebView 生命周期、系统栏、文件选择和通知；
- SAF 目录桥和用户目录映射；
- 运行时更新、日志和崩溃恢复。

上游来源：`https://github.com/kelai141/dsh-mobile-apk`。复制的源码保留其 MIT 许可；运行时快照中的第三方许可证继续放在 `app/src/main/assets/licenses/`。

Android 壳中保留的控制台、ADB 桥、更新、撤销和外部文件处理代码属于可复用底座，但 minimal profile 不
默认加载对应 DSH 插件。后续恢复某项能力时，需同时恢复其运行时依赖、UI 入口和阶段测试。

### 本地 gateway

`gateway/` 保存 dsh-Remote 的 gateway 和 UI 基线，当前已通过 `DSH_REMOTE_LOCAL=1` 接入本地模式：

- 上游固定为 `127.0.0.1:3080`；
- 监听地址固定为 `127.0.0.1`；
- 关闭远程设备、多服务器、Tailscale、后台轮询和公网公告等能力；
- 保留会话、实时消息、审批、工作区、统计和本地文件 API；
- 本地权限由 Android 壳层和 SAF 授权目录决定。

本地模式不会把文件根目录默认为整个设备。默认使用用户明确选择并持久授权的目录；是否启用 All Files Access 由 Android 设置页单独控制。

### UI

`gateway/public/` 是 dsh-Remote UI 的独立快照。接入本地模式后，前端默认连接本地 gateway，并隐藏远程服务器、令牌配对和网络测速入口。保留同一套会话、文件、审批和统计交互。

模型配置是 UI 层的用户交互，不越过 gateway 直接读取 Engine 私有文件：

- 通过 `llm.providers` 与 `settings.describe` 获取可配置提供方和设置元数据；
- 通过 `credentials.describe/set/unset` 管理 API 密钥，前端只接收脱敏状态；
- 通过 `settings.mutate` 保存 API 地址、协议和模型目录，通过 `llm.discoverModels` 查询可用模型；
- 生效时机使用 DSH 返回的 `applies` 字段，不由 Android 壳或 gateway 自行重启 Engine。

这样模型配置仍然属于“WebView → Local Gateway → DSH Engine”的既有边界，不把凭据、DSH 配置文件或 Engine
进程控制逻辑耦合进 Android UI。

## 里程碑

1. **仓库基线**：上游 Android 壳、dsh-Remote gateway/UI、许可证和上游说明齐全。
2. **四层协调**：`BackendSupervisor` 只按安装 → Engine → Gateway → UI 顺序推进并归因失败。
3. **本地 gateway 启动**：Android 进程能够在 DSH Web 启动后拉起 gateway，并探测 `8787/health`。
4. **WebView 接入**：WebView 访问本地 gateway，能完成首页、会话列表和历史读取。
5. **实时闭环**：实时 mux/host、消息发送、审批和提问可用。
6. **文件闭环**：SAF 授权目录映射到 DSH 工作区，支持浏览、上传、下载和工作区使用。
7. **生命周期验收**：冷启动、后台恢复、引擎崩溃、gateway 崩溃和系统重启后自动恢复。
8. **最小运行时**：生成并验证 minimal arm64 快照，确认可选组件未进入默认 profile。
9. **云端同步**：静态检查和可用 ABI 构建通过后，推送独立仓库的对应分支。

## 暂不做

- 不把 dsh-Remote 的远程 Token 当作用户配置；本地 gateway 只保留最小进程内能力凭证或受限本地认证。
- 不直接复用上游预构建 APK、签名密钥或品牌资源。
- 不在第一阶段把 WebView 与 gateway 改成 Native Bridge；先保持协议复用，降低验证面。
- 不承诺所有桌面 DSH 插件在 Android 上可用；插件兼容性需要单独清单和测试。
- 不把编译器、Python/Perl/Ruby、npm/pnpm、ADB、附件/OCR、市场、撤销和编辑器作为默认安装项；它们必须以可选 pack 形式单独维护。
