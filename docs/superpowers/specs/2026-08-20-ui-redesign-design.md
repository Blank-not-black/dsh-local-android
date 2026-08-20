# UI 重构设计 — 高端极简 + teal 点缀 + 双主题

日期:2026-08-20
状态:已批准(Web 界面暂不处理,范围仅原生界面 + 控制台)

## 背景

当前原生 UI 存在以下问题(审计结论):

- **P0**:深色模式下原生界面文本不可读(浅色主题文本色 + 深色背景)
- **P1**:无设计系统(无 colors/dimens/样式资源,魔数遍布)
- **P1**:无品牌色体系,按钮/进度条全系统默认样式
- **P1**:程序化布局,无横屏/大屏适配
- **P2**:启动界面信息架构失衡,诊断信息与操作按钮权重混淆

控制台 console.html 调性尚可,但缺交互反馈与统一 token。

## 范围

**重构**:原生启动/测试界面(guide view)、console.html、设计 token 体系(双主题)
**不做**:Web 主界面(上游产品,暂不打补丁)、应用图标

## 设计 Token

中性暖灰单一家族(杜绝冷暖灰混用);非纯黑/纯白;全 UI 仅一个高饱和点缀色 teal。

### 颜色(res/values/colors.xml + values-night/colors.xml)

| Token | 浅色 | 深色 |
|---|---|---|
| ds_bg | `#FAFAF9` | `#0C0C0E` |
| ds_surface | `#FFFFFF` | `#141416` |
| ds_border | `#14000000`(黑 8%) | `#1AFFFFFF`(白 10%) |
| ds_text_primary | `#18181B` | `#E7E7E8` |
| ds_text_secondary | 主文本 55% | 主文本 55% |
| ds_text_tertiary | 主文本 35% | 主文本 35% |
| ds_accent | `#0D9488` | `#2DD4BF` |
| ds_accent_pressed | accent 深一档 | accent 浅一档 |
| ds_danger | `#DC2626`(仅警示条,降饱和) | `#F87171` |

### 字体与排版

- 中文:系统字体栈 `sans-serif`,标题 `sans-serif-medium`
- 等宽 `monospace`(日志摘要、状态数值),数字 `FontFeatureSettings` 启用 tabular
- 基准字号:12/13/14/16/20

### 布局(新建 res/values/dimens.xml)

8pt 网格:4/8/12/16/24/32/40。圆角:内嵌 6、卡片 16、胶囊 999。

## 原生启动/测试界面重构

三层视觉层级替代平铺堆叠:

1. **品牌区**(顶部):小图标 32dp + "DeepCode" 字标(20sp medium),克制、靠上不居中
2. **状态卡**(中部 surface 卡片,圆角 16,边框 border):
   - 状态文本(16sp primary)
   - 进度条(6dp,teal 实色;背景 border 色)
   - 进度文本(13sp secondary)
   - 崩溃横幅:卡内警示条(danger 色系、圆角 8),不再纯红刺眼
   - 日志摘要:等宽 11sp tertiary,置于卡内次级区
3. **操作区**(底部):主按钮「重试」实心 teal(白字);次按钮「打开控制台」「检查运行时更新」幽灵(透明底、边框 border、主色文字);按钮带 pressed 反馈(scale 0.97 + 加深)

### 实现方式

- 保持程序化构建(不引入 XML 布局),所有颜色/尺寸引用 `R.color`/`R.dimen` 资源
- 消灭硬编码魔数
- 深色模式由 `values-night` 限定符自动切换,所有 TextView/按钮显式设色(修复 P0)

## 控制台 console.html 重构

- 统一 token:扩展 CSS 变量 `--ds-*`(与原生同命名:bg/surface/border/text/accent)
- 状态栏:胶囊状态点 + 等宽引擎状态;输入行吸底,surface 分区 + 上边框
- 输入框:focus ring(teal 2px);发送按钮 teal 实心 + `:active` 缩放反馈
- 输出:等宽字体,行距 1.5,保留 maxLines 裁剪逻辑

## 验证

- `./gradlew :app:compileDebugKotlin` 编译通过
- 深/浅色两套资源存在且文本对比度达标(4.5:1 以上)
- 手工验证:启动态、崩溃态、更新进度态、控制台交互