# DSH for Android

DeepSeek Harness（DSH）的独立 Android 本地版：在手机本地运行 DSH，并使用
dsh-Remote 的界面进行交互。

本仓库明确基于两个上游内容开发：

- Android 壳、内嵌运行时生命周期、快照解压、SAF 文件访问、前台服务、看门狗和原生桥，基于
  [dsh-mobile-apk](https://github.com/kelai141/dsh-mobile-apk)。固定基线、复制文件和许可证说明见
  [UPSTREAM.md](UPSTREAM.md)。
- 本地 gateway 和 Web UI 来自
  [dsh-Remote](https://github.com/Blank-not-black/dsh-Remote)，并针对 Android 回环运行方式做了适配。

本项目不是任一上游项目的官方发行版。Android 运行时的改动应能追溯到
dsh-mobile-apk 基线；gateway 和 UI 的改动应能追溯到 dsh-Remote。开始开发前请先阅读
[AGENTS.md](AGENTS.md)。

## 当前架构

启动流程严格分为四层：

```text
安装 / 检测层
      │
      ▼
DSH Engine  ── http://127.0.0.1:3080
      │
      ▼
Local Gateway ── http://127.0.0.1:8787
      │
      ▼
DSH for Android UI（WebView）
```

`BackendSupervisor` 只负责顺序、就绪检查和失败归因，各层职责如下：

1. 安装 / 检测层：校验 ABI、准备运行时快照、处理解压迁移和 Android 权限。
2. DSH Engine 层：启动 `dsh web`，负责引擎进程、前台服务、看门狗和 `engine.log`。
3. Local Gateway 层：承载 dsh-Remote UI，代理 DSH API/WebSocket，并提供本地文件接口；只监听
   回环地址，写入 `gateway.log`。
4. UI 层：只有 gateway 健康检查成功后才加载 WebView，不直接启动或管理后端进程。

Engine 与 gateway 之间保留回环 HTTP/WebSocket，是为了复用现有 DSH 协议。由于运行范围是本机，
远程服务器列表、Token 配对、网络轮询和公网公告/更新检查在本地模式中关闭，不属于 Android 本地运行时契约。

## 仓库结构

```text
dsh-local-android/
├── app/
│   └── src/main/
│       ├── java/com/dshmobile/shell/
│       │   ├── BackendSupervisor.kt       # 四层启动协调器
│       │   ├── EmbeddedProcess.kt         # Android ELF / linker 启动辅助
│       │   ├── EngineManager.kt            # DSH 引擎生命周期
│       │   ├── EngineService.kt            # 引擎前台服务和看门狗
│       │   ├── LocalGatewayManager.kt      # 本地 gateway 生命周期和资源部署
│       │   ├── MainActivity.kt              # 检测界面和 WebView 接管
│       │   ├── GatewayProbe.kt              # 127.0.0.1:8787 健康检查
│       │   └── ...                          # 上游壳层与 Android 桥
│       └── assets/                          # 运行时快照和许可证
├── gateway/
│   ├── gateway.js                          # dsh-Remote gateway，本地模式
│   ├── gateway-stats.cjs
│   └── public/                             # dsh-Remote UI 快照
├── tests/
│   ├── local-gateway.test.mjs
│   └── ui-local-mode.test.mjs
├── docs/LOCAL_ARCHITECTURE.md               # 当前架构契约
├── docs/design.md                           # 当前技术设计
├── UPSTREAM.md                              # 上游基线和署名说明
└── AGENTS.md                                # 开发规则和测试门禁
```

## 构建

当前工程编译使用 Android API 36，运行目标为 API 34；本地 Gradle 使用 JDK 21。运行时快照较大，
不提交到 Git，构建前请将匹配的快照放到
`app/src/main/assets/snapshot.tar.xz`。

当前面向实体手机的开发构建固定使用 arm64 快照。x86_64 模拟器必须替换成 x86_64 快照及其哈希，
不能混用不同 ABI 的 Node/运行时。

```sh
export JAVA_HOME=/home/blank/Android/jdk21
export GRADLE_USER_HOME="$PWD/.gradle-home"

./gradlew testDebugUnitTest --no-daemon
node --test tests/*.test.mjs
node --check gateway/gateway.js
node --check gateway/public/app.js
./gradlew assembleDebug --no-daemon
```

APK 产物：

```text
app/build/outputs/apk/debug/app-debug.apk
```

当前快照构建应使用 arm64 真机验证；arm64 内嵌 Node 运行时不能在 x86_64 模拟器上运行。

## 分层诊断

应用按层显示启动失败，不把所有问题合并成一个笼统错误。控制台可分别查看：

- `engine.log`：运行时解压、内嵌 Node 和 `dsh web` 启动日志；
- `gateway.log`：本地 gateway 启动、上游探测和 gateway 请求日志。

下面的警告通常不会导致引擎启动失败：

```text
Cannot load "@napi-rs/canvas" package
Cannot polyfill DOMMatrix / ImageData / Path2D
```

它来自 `pdfjs-dist` 的可选 PDF 渲染能力。Engine 失败与 gateway 失败是两个独立问题，应先查看对应日志，
再决定是否需要修改依赖。正常端点为：

```text
DSH Engine：    http://127.0.0.1:3080
Local Gateway： http://127.0.0.1:8787/health?probe=live
```

## 权限与本地文件

Android 壳保留上游 SAF 桥，通过系统文件选择器获得目录或文件访问能力。文件访问必须遵循 Android 授予的
URI 或用户明确选择的本地工作区；本地 gateway 不能因为运行在回环地址就扩大为无约束的设备文件访问。

## 许可证与署名

本仓库使用 MIT 许可证。源自 dsh-mobile-apk 的 Android 壳和运行时保留上游版权及 MIT 声明；导入的
dsh-Remote gateway/UI 在 `LICENSES/dsh-remote-MIT.txt` 中保留其 MIT 声明；其他运行时组件的许可证位于
`app/src/main/assets/licenses/`。

具体的上游提交基线、复制路径和署名规则以
[UPSTREAM.md](UPSTREAM.md) 为准。
