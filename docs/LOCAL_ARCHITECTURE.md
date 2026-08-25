# dsh-local-android 架构路线

## 目标

本仓库是一个独立的 Android 本地 DSH 发行版：复用 `dsh-mobile-apk` 的 Android 运行时底座，同时使用 dsh-Remote 的 gateway 和移动端 UI。dsh-Remote 主线继续负责远程控制电脑上的 DSH，本仓库不把本地运行时实验反向混入主线。

## 第一阶段链路

```text
Android EngineService
  └─ embedded Node + DSH Web (127.0.0.1:3080)
       └─ local gateway (127.0.0.1:8787)
            └─ dsh-Remote UI (WebView)
```

第一阶段保留 HTTP/WebSocket 作为 WebView 与 gateway 的进程间通信方式。它们只在 Android 回环接口上传输，不承担远程访问职责，因此可以移除远程连接相关的复杂度，而不必立即重写前端协议。

## 模块边界

### Android 底座

沿用上游壳层的以下能力：

- 快照解压与 ABI 校验；
- DSH 引擎启动、停止、前台服务和看门狗；
- WebView 生命周期、系统栏、文件选择和通知；
- SAF 目录桥和用户目录映射；
- 运行时更新、日志和崩溃恢复。

上游来源：`https://github.com/kelai141/dsh-mobile-apk`。复制的源码保留其 MIT 许可；运行时快照中的第三方许可证继续放在 `app/src/main/assets/licenses/`。

### 本地 gateway

`gateway/` 保存 dsh-Remote 的 gateway 和 UI 基线。后续增加 `DSH_REMOTE_LOCAL=1` 本地模式：

- 上游固定为 `127.0.0.1:3080`；
- 监听地址固定为 `127.0.0.1`；
- 关闭远程设备、多服务器、Tailscale、后台轮询和公网公告等能力；
- 保留会话、实时消息、审批、工作区、统计和本地文件 API；
- 本地权限由 Android 壳层和 SAF 授权目录决定。

本地模式不会把文件根目录默认为整个设备。默认使用用户明确选择并持久授权的目录；是否启用 All Files Access 由 Android 设置页单独控制。

### UI

`gateway/public/` 是 dsh-Remote UI 的独立快照。接入本地模式后，前端默认连接本地 gateway，并隐藏远程服务器、令牌配对和网络测速入口。保留同一套会话、文件、审批和统计交互。

## 里程碑

1. **仓库基线**：上游 Android 壳、dsh-Remote gateway/UI、许可证和上游说明齐全。
2. **本地 gateway 启动**：Android 进程能够在 DSH Web 启动后拉起 gateway，并探测 `8787/health`。
3. **WebView 接入**：WebView 访问本地 gateway，能完成首页、会话列表和历史读取。
4. **实时闭环**：实时 mux/host、消息发送、审批和提问可用。
5. **文件闭环**：SAF 授权目录映射到 DSH 工作区，支持浏览、上传、下载和工作区使用。
6. **生命周期验收**：冷启动、后台恢复、引擎崩溃、gateway 崩溃和系统重启后自动恢复。
7. **云端同步**：静态检查和可用 ABI 构建通过后，创建 `Blank-not-black/dsh-local-android` 并推送初始版本。

## 暂不做

- 不把 dsh-Remote 的远程 Token 当作用户配置；本地 gateway 只保留最小进程内能力凭证或受限本地认证。
- 不直接复用上游预构建 APK、签名密钥或品牌资源。
- 不在第一阶段把 WebView 与 gateway 改成 Native Bridge；先保持协议复用，降低验证面。
- 不承诺所有桌面 DSH 插件在 Android 上可用；插件兼容性需要单独清单和测试。
