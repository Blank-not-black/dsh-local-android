# DSH for Android

DeepSeek Harness（DSH）的独立 Android 本地版：在手机本地运行 DSH，并使用
dsh-Remote 的界面进行交互。

## 上游基础与本项目改动

本仓库只把上游作为实现底座，不把上游项目的产品结构当作本项目契约：

- `dsh-mobile-apk` 提供 Android 壳、内嵌 Node/DSH 运行时生命周期、快照解压、SAF、前台服务、看门狗和原生桥；
- `dsh-Remote` 提供 gateway 与 Web UI 的可复用基础。

我们在此基础上做了本地化改造：将安装检测、DSH Engine、Local Gateway、WebView UI 拆成四层；让 Engine 和
gateway 固定走本机回环；删去远程 Token、轮询和公网更新链路；由 Android 系统处理文件选择；默认只交付 minimal
运行时，其他能力通过后续可选组件加入。上游基线、复制范围和许可证见 [UPSTREAM.md](UPSTREAM.md)。

本项目不是任一上游项目的官方发行版。开始开发前请先阅读 [AGENTS.md](AGENTS.md)。

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

### 默认最小运行时与可选组件

当前 APK 内置 arm64 minimal 快照。默认 profile 只加载 DSH 启动所需的 shell、Web 兼容层、响应式 UI、默认模型
配置和系统目录选择入口。编译器/链接器、Python/Perl/Ruby、npm/pnpm、ADB 管理、编辑器、OCR/PDF/Office、
市场、撤销和外部文件打开不进入默认启动链。

minimal 快照由 `scripts/build-minimal-snapshot.sh` 从完整快照可重复生成，profile 基线位于
`runtime/minimal/cordis.patch.yml`。后续可选组件必须单独声明依赖、许可证、体积和测试，不得修改四层启动顺序。
组件拆分清单见 [`docs/OPTIONAL_COMPONENTS.md`](docs/OPTIONAL_COMPONENTS.md)。

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
│   ├── ui-local-mode.test.mjs
│   └── minimal-profile.test.mjs
├── runtime/minimal/
│   └── cordis.patch.yml                   # 默认最小 profile
├── scripts/
│   └── build-minimal-snapshot.sh           # 从完整快照可重复生成 minimal 快照
├── docs/LOCAL_ARCHITECTURE.md               # 当前架构契约
├── docs/design.md                           # 当前技术设计
├── docs/OPTIONAL_COMPONENTS.md              # 后续可选能力包边界
├── UPSTREAM.md                              # 上游基线和署名说明
└── AGENTS.md                                # 开发规则和测试门禁
```

## 构建

当前工程编译使用 Android API 36，运行目标为 API 34；本地 Gradle 使用 JDK 21。运行时快照较大，
不提交到 Git，构建前请将匹配的快照放到
`app/src/main/assets/snapshot.tar.xz`。

当前面向实体手机的开发构建固定使用 arm64 minimal 快照。x86_64 模拟器必须替换成 x86_64 minimal 快照及其
哈希，不能混用不同 ABI 的 Node/运行时。快照本体不提交 Git，哈希记录在 `app/src/main/assets/snapshot.sha256`。

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

如果后续启用附件格式可选组件，下面的警告通常不会导致引擎启动失败：

```text
Cannot load "@napi-rs/canvas" package
Cannot polyfill DOMMatrix / ImageData / Path2D
```

它来自 `pdfjs-dist` 的可选 PDF 渲染能力；默认 minimal 快照不加载该路径，因此正常情况下不应出现。Engine 失败
与 gateway 失败是两个独立问题，应先查看对应日志，再决定是否需要修改依赖。正常端点为：

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
