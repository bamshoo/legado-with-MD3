# AGENTS.md

本文件为在此仓库中工作的 AI 编程代理（如 Codex、opencode 等）提供指引。**编码规则与约束见下文，项目详细说明见 `docs/agents/` 参考文档。**

## 项目概览

Legado 的 **Material Design 3 分支**（阅读器 App）。三层 Clean Architecture（Data/Domain/UI），View 向 Compose 迁移中。功能详情见下表文档：

| 主题 | 文档 |
|---|---|
| 架构（三层、顶层包、模块、混合 Compose+View、Rhino JS 引擎） | `docs/agents/architecture.md` |
| 依赖注入（Koin） | `docs/agents/koin-di.md` |
| 导航（Navigation 3） | `docs/agents/navigation.md` |
| 主题系统（M3 Expressive / Miuix） | `docs/agents/theme-system.md` |
| Compose 界面开发规范（MVI/UDF 模板与示例代码） | `docs/agents/compose-guidelines.md` |
| 番茄小说云同步集成（feature/fanqie-sync 分支） | `docs/agents/fanqie-sync.md` |

## 编码指南

**权衡原则：** 以下准则偏向谨慎而非速度。对琐碎任务可用判断力。

### 编码前思考

- 明确假设，不确定时询问而非猜测。
- 存在歧义时，列出多种解释，不默默选定单一方案。
- 如果任务有明显更简单的做法，直接指出优化思路。
- 发现代码矛盾、逻辑不一致时及时暂停，请求信息澄清。

### 简洁优先

- 用最少的代码解决问题，拒绝冗余实现。
- 不为一次性需求创建抽象层、复杂架构。
- 不盲目增加扩展性、可配置性，应对"未来可能用到"的场景。
- 若代码可大幅精简，主动重写优化。
- 校验标准：以资深工程师视角判断，代码若过于复杂，立即简化。

### 精准修改

- 仅修改与当前任务直接相关的代码内容。
- 不顺手优化相邻代码、注释、排版格式。
- 不重构原本可以正常运行的代码模块。
- 严格匹配项目现有代码风格，保留原有编码习惯。
- 因本次修改产生的无效导入、废弃变量，可直接删除。
- 发现项目中原有的死代码、冗余内容，仅做文字提醒，不擅自删除。

### Settings Gateway 约定

- 普通设置 Gateway 通过 `update { current -> current.copy(...) }` 变更状态。
- 不要引入 `*SettingsUpdate` 分发类型或 settings gateway 上的 `updateAll`。
- 相关联的多字段变更在一个 `copy(...)` 变换中提交，让 SSOT 原子应用。
- 保持专用 API 的既有形态：`ReadStyleMutation`、`ThemePackageSettingsGateway.applyAndAwait`、`ThemeStateTransaction`、`AppUiConfigurationGateway`。

### 目标驱动执行

- 执行任务前，定义清晰、可落地的成功标准。
- 将"修复Bug"转化为：编写用例复现问题，再调试至用例正常通过。
- 将"新增校验功能"转化为：针对异常输入编写测试用例，保证全部通过。
- 将"代码重构"转化为：完成重构后，确保原有所有测试用例正常运行。
- 多步骤复杂任务，先输出简短执行计划，同时标注每一步的验证方式。

## 构建 / 测试 / 运行

```bash
# 快速编译检查（仅 Kotlin，不打 dex/包 — 验证代码可编译最快）
.\gradlew.bat :app:compileAppDebugKotlin

# 构建全部变体
./gradlew assembleAppRelease

# 不带 R8 构建（崩溃调试用 — 无混淆/收缩）
./gradlew assembleAppNoR8

# Debug 构建
./gradlew assembleAppDebug

# 运行单元测试（JVM，本地）
./gradlew test

# 运行单个测试类
./gradlew test --tests "io.legado.app.model.cache.CacheDownloadQueueTest"

# 运行连接设备的 Android 测试
./gradlew connectedAndroidTest

# Lint
./gradlew lint

# 更新 Cronet（修改 gradle.properties 中 CronetVersion 后）
./gradlew app:downloadCronet
```

- 开发用 JDK 21（`build.gradle.kts` 中 `jvmToolchain` 设置）；CI 用 JDK 17 构建。
- Gradle 属性：8 GB 堆、配置缓存关闭（`gradle.properties:31`）、非传递 R 类、精确资源收缩启用。

## 重要约束

- **jsoup 不要升级超过 1.16.2** — 新版本的破坏性变更（见 [jsoup#2017](https://github.com/jhy/jsoup/pull/2017)）影响 `AnalyzeByJSoup.kt` 和 JsoupXpath 库。
- **hutool 不要升级超过 5.8.22** — 锁定在 `libs.versions.toml:42`。
- 包名不一致：代码命名空间是 `io.legado.app`，但 `applicationId` 是 `io.legato.kazusa`。
- Min SDK 26，target SDK 37，compile SDK 37。
- Release 构建启用 R8 混淆 + 资源收缩；`noR8` 变体两者皆关，用于崩溃调试。
- APK 按 ABI 拆分（`armeabi-v7a`、`arm64-v8a`，外加 universal）。
- 包含 Firebase Analytics 与 Performance；应用了 `google-services` 插件。

## Compose 界面开发规则（新界面强制遵循）

**所有新界面必须用 Jetpack Compose 实现。** 禁止新建 View 系 Activity/Fragment/XML 布局；既有 View 界面可在迁移完成前保留。详细模板与示例代码见 `docs/agents/compose-guidelines.md`，核心规则如下：

- **MVI/UDF**：每个界面按 `XxxContract.kt`（`@Stable` 的 `XxxUiState`、`sealed interface XxxIntent/XxxEffect`，可选 `XxxSheet/XxxDialog`）+ `XxxViewModel.kt` + `XxxScreen.kt` 组织。
- **ViewModel**：直接继承 `ViewModel()`；`_uiState` 为 `MutableStateFlow` 经 `.asStateFlow()` 暴露；`_effects` 为 `MutableSharedFlow(extraBufferCapacity = 16)` 经 `.asSharedFlow()` 暴露；`tryEmit` 发副作用；单一 `onIntent()` 入口 `when` 分发。
- **Screen 无状态**：接收 `state`/`onIntent`/`effects`，不直接访问 ViewModel；副作用在 `LaunchedEffect` 中用 `collectLatest` 收集；无业务逻辑、无直接 DB/网络调用；优先使用项目自定义组件（`AppScaffold`、`AppText`、`AppAlertDialog`、`AppModalBottomSheet`、`NormalCard`、`GlassMediumFlexibleTopAppBar` 等）。
- **稳定性**：所有 UiState/UI item 数据类标注 `@Stable`；列表属性用 `ImmutableList`（`kotlinx.collections.immutable`），默认值 `persistentListOf()`/`toImmutableList()`。
- **导航**：Navigation 3，路由为 `@Serializable` sealed interface（加入 `MainNavKey.kt` 的 `MainRoute`）；界面不直接引用 navigator，通过 `onBack`/`onNavigateToXxx` 回调，由 entry provider 接线（`MainNavGraph.kt`）。
- **Koin DI**：ViewModel 在 `di/appModule.kt` 用 `viewModelOf(::XxxViewModel)` 注册；界面用 `koinViewModel()` 注入（按 key：`koinViewModel<XxxViewModel>(key = route.bookUrl)`）；仓库/Gateway/用例用 `singleOf(::...)`。
- **Activity 基类**：独立 Compose Activity 继承 `BaseComposeActivity`，重写 `Content()`。
- **RouteScreen 包装层**：需要 ActivityResult/生命周期/权限的界面分两层——外层 `XxxRouteScreen` 处理副作用并接线 VM，内层 `XxxScreen` 纯 UI。
- **M3 vs Miuix**：需要引擎专属 UI 时用 `ThemeResolver.isMiuixEngine(LegadoTheme.composeEngine)` 分支。
- 评审约定与迁移模式：`.Codex/skills/legado-compose-review/`、`.agents/skills/legado-compose-migration/SKILL.md`。

## 番茄小说集成（feature/fanqie-sync 分支）

番茄小说云书架 + 阅读进度双向同步。代码在 `app/src/main/java/io/legado/app/fanqie/`（`help` 平级基础设施包，直接操作 `appDb`/`ReadBook`/`CookieStore`）。**完整说明见 `docs/agents/fanqie-sync.md`**，要点：

### 集成点（勿随意改动）

- `App.kt`：`FanqieFeature.init()`（番茄注释标记处）。
- `di/appModule.kt`：`viewModelOf(::FanqieViewModel)`。
- `ui/main/MainNavKey.kt`/`MainNavGraph.kt`/`MainNavigator.kt`：`MainRouteFanqie` 路由；`MainScreen`/`BookshelfScreen` 传 `onNavigateToFanqie`。
- `ui/main/bookshelf/`：番茄同步按钮、番茄分组空态登录引导、`FanqieLoginBanner`；`BookshelfViewModel`/`BookshelfUiState` 加 `fanqieLoginState`。
- 私有组保护：`BookGroupRepository.flowSelect` 过滤番茄组；`BookshelfViewModel`、`GroupManageSheet`、`GroupEditDialog`、`GroupEditSheet` 用 `FanqieGroup.isFanqieGroup()` 排除/禁编辑。
- `domain/usecase/ChangeBookSourceUseCase.kt`：换源到番茄书时写 `fanqieBookId` 变量。
- `help/storage/Restore.kt`：备份恢复后调 `FanqieGroup.afterRestore()`。
- `ui/book/read/ReadBookViewModel.kt` `onCleared()`：调 `FanqieProgressSyncer.flush()`。

### 语义约定（容易踩坑）

- `durChapterIndex`/`readChapterIndex` 为 **0-based** 目录索引，显示章号须 `+1`。
- 云端 `readTimestamp` 单位**秒**；本地 `durChapterTime` 单位**毫秒**，换算 `*1000L`。
- 进度 `fraction` 按 `(chapterIndex + 0.5) / chapterCount` 计算，非真实章节内百分比。
- 番茄书本地 `bookUrl` = `https://fanqienovel.com/page/{bookId}`，`origin` = `https://fanqienovel.com`，并写入 `variableMap["fanqieBookId"]`。

## 环境提示

- maven 仓库路径在 `E:\DevelopSoft\JetBrains\mavenRepository`
- 统一中文回复
- 读写文件统一 utf-8 格式
- gradle 缓存和项目放到同一个盘中，文件夹名字 `gradle-home`
- 实机调试备忘（设备序列号、adb 路径、UI 点击坐标等）见 `docs/agents/fanqie-sync.md`
