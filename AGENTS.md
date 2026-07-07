# Agent 指南

## 语言规则

- Agent 与用户沟通时默认使用中文。
- 后续新增或修改的文档、说明、提交描述草稿、代码注释、用户可见文案默认使用中文。
- 代码标识符、包名、类名、方法名、参数名、Gradle 任务名、协议字段、第三方 API 名称等保持项目和生态惯例，不为了中文化而破坏可读性或兼容性。
- 引用上游原文、错误日志、命令输出、官方 API 名称时可以保留原语言。

## 项目概览

vFlow 是一个 Kotlin Android 自动化应用。当前项目包含两个 Gradle 模块：

- `app`：Android 应用主体，包含工作流编辑器、模块注册表、触发器、服务、权限、UI、OCR、集成能力，以及应用侧 Core 桥接。
- `core`：JVM/Kotlin 后端，会构建为 `app/src/main/assets/vFlowCore.dex`，用于更高权限或独立进程隔离的系统能力。

重要入口：

- 应用命名空间：`com.chaomixian.vflow`
- 内建工作流模块：`app/src/main/java/com/chaomixian/vflow/core/workflow/module/`
- 内建模块注册表：`app/src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt`
- 触发器处理器：`app/src/main/java/com/chaomixian/vflow/core/workflow/module/triggers/handlers/`
- Core 服务端代码：`core/src/main/java/com/chaomixian/vflow/server/`
- 开发文档：`README.md`、`docs/CONTRIBUTION.md`、`docs/vFlow_App_Architecture.md`、`docs/vFlow_Core_Architecture.md`

## Fork 隔离规则

当前仓库是 fork 项目。为了方便后续同步上游更新，新增的 fork 专属代码和功能应尽量与上游源码目录隔离。

新增 fork 代码优先使用以下包根目录：

- App 侧 Kotlin：`app/src/main/java/com/vflow/fork/`
- Core 侧 Kotlin：`core/src/main/java/com/vflow/fork/`
- App 单元测试：`app/src/test/java/com/vflow/fork/`
- Instrumented 测试：`app/src/androidTest/java/com/vflow/fork/`

除非是接入现有上游入口所需的最小桥接改动，否则不要把新的功能类直接添加到 `app/src/main/java/com/chaomixian/vflow/` 或 `core/src/main/java/com/chaomixian/vflow/` 下。

允许的最小上游入口改动包括：

- 在 `ModuleRegistry` 中注册 fork 模块。
- 在 `TriggerHandlerRegistry` 中接入 fork 触发器处理器。
- 添加 Android 平台必须感知的 Manifest 条目。
- 添加 fork 代码必需的 Gradle 依赖、source set、构建任务或打包规则。
- 添加 fork 代码必需的资源。

必须修改上游入口时，保持改动尽量小，并委托给 fork 包内的类，例如：

```kotlin
ForkModuleRegistry.registerAll(context)
```

## 命名约定

- fork 模块 ID 使用 `fork.` 或 `vflow.fork.` 前缀，避免与上游模块 ID 冲突。
- fork Android 资源使用 `fork_` 前缀。
- 持久化的枚举值、字符串参数值必须稳定；不要持久化本地化展示文案。
- 除非用户明确要求，不要重命名上游包名、类名、资源名、applicationId 或 namespace。

## 新增工作流模块

新增工作流模块时：

1. 在 `app/src/main/java/com/vflow/fork/workflow/module/...` 下实现模块。
2. 复用上游抽象，例如 `BaseModule`、`BaseBlockModule`、`InputDefinition`、`OutputDefinition`、`ExecutionContext` 和已有变量类型。
3. 添加 fork 注册类，例如 `com.vflow.fork.workflow.module.ForkModuleRegistry`。
4. 只在上游 `ModuleRegistry.initialize()` 中添加一处很小的注册调用。
5. 自定义 UI provider、辅助类和数据模型放在同一个 fork 包树下。
6. 涉及逻辑、解析、兼容性或执行行为变化时，在 fork 测试包下添加聚焦测试。

新增触发器模块时，模块和处理器实现都放在 fork 包下，只在上游触发器注册表中添加必要的注册调用。

## 资源与资产

- fork 资源放在常规 Android 资源目录中，但使用 `fork_` 前缀。
- 除非功能确实需要，不要把生成物或大型二进制资产加入源码。
- 除非任务明确要求 Core 行为或发布打包，不要覆盖 `vFlowCore.dex`、OCR 模型、native binary、图标或截图等上游资产。

## 构建与验证

根据改动范围选择最小验证命令：

- App Kotlin 编译：`./gradlew :app:compileDebugKotlin`
- Core Kotlin 编译：`./gradlew :core:compileKotlin`
- App 单元测试：`./gradlew :app:testDebugUnitTest`
- 完整 debug 构建：`./gradlew :app:assembleDebug`

App 构建依赖 `:core:buildDex`，该任务会更新 `app/src/main/assets/vFlowCore.dex` 和 `app/src/main/assets/vFlowCore.version`。除非任务明确涉及 Core 行为或发布打包，否则不要提交重新生成的 Core 资产。

## 工作区纪律

- 编辑前检查 `git status --short`。
- 不要回滚或清理不是自己改动的文件。
- fork 功能改动应限制在 fork 包和必要的最小上游桥接点内。
- 优先做小而清晰、便于 review 的改动，避免无关重构。
