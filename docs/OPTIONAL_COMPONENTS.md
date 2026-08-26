# Optional capability packs

`dsh-local-android` 的默认安装项是 minimal profile。这个文件记录后续版本可恢复的能力边界，避免把非启动依赖
重新混入基础运行时。

| 能力包 | 主要内容 | 默认状态 | 恢复时必须补齐 |
| --- | --- | --- | --- |
| `scripting-dev` | Python、Perl、Ruby，以及对应脚本工具 | 关闭 | 运行时目录、命令探测、体积和脚本测试 |
| `compiler-toolchain` | clang/gcc、LLVM、binutils、make、Android 交叉编译器 | 关闭 | 编译器二进制、头文件/库、ABI 说明和编译测试 |
| `device-management` | ADB、fastboot、设备镜像工具、Android 管理 UI | 关闭 | ADB 工具、`dsh-android-bridge`/`dsh-android-manage`、权限和真机测试 |
| `attachments` | PDF、Office、OCR、压缩包和表格处理 | 关闭 | `dsh-attachment-formats` 及其依赖、许可证和文件回归测试 |
| `workspace-extra` | 撤销保存点、外部文件打开、市场/运行时安装 | 关闭 | 对应 DSH 插件、包管理器和独立升级/回滚测试 |

minimal 默认保留的内容只有：

- DSH 全局核心包和嵌入式 Node；
- bash、核心文件工具、`rg`、TLS/HTTP 所需的基础命令；
- `dsh-shell-termux`、`dsh-host-web-compat`、`dsh-client-ui-responsive`；
- 默认模型配置和系统目录选择入口；
- 本地 gateway、四层启动协调和我们的 Web UI。

编译器不是 DSH 启动依赖，但用户通过 DSH shell 执行 C/C++、Rust 或 Android 构建任务时可能需要它。因此
它应该作为“开发工具包”提供，而不是作为基础安装内容隐式存在。类似地，PDF.js 的
`@napi-rs/canvas`、`DOMMatrix`、`ImageData` 和 `Path2D` 只属于附件包；minimal 版不应为这些渲染能力承担
依赖和启动警告。

## Pack 约束

每个 capability pack 必须：

1. 只在安装/升级阶段叠加，不改变 `INSTALL → ENGINE → GATEWAY → UI` 顺序；
2. 列出运行时依赖、许可证、预估体积和与其他 pack 的冲突；
3. 有静态 profile 边界测试和启用后的功能测试；
4. 能被移除而不影响 minimal DSH 后台启动与本地 gateway；
5. 不把远程 Token、网络轮询或公网服务重新引入本地回环模式。
