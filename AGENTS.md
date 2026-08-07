# AGENTS.md

This file provides guidance to Codex (Codex.ai/code) when working with code in this repository.

## Coding Guidelines

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

### Think Before Coding

- State assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them — don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

### Simplicity First

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

### Surgical Changes

- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it — don't delete it.
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

### Settings Gateway Conventions

- Ordinary settings gateways mutate state through `update { current -> current.copy(...) }`.
- Do not introduce `*SettingsUpdate` dispatch types or `updateAll` on settings gateways.
- Submit related multi-field changes in one `copy(...)` transform so the SSOT can apply them atomically.
- Keep specialized APIs such as `ReadStyleMutation`, `ThemePackageSettingsGateway.applyAndAwait`,
  `ThemeStateTransaction`, and `AppUiConfigurationGateway` in their dedicated shapes.

### Goal-Driven Execution

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

## Build / Test / Run

```bash
# Quick compile check (Kotlin only, no dex/package — fastest for verifying code compiles)
.\gradlew.bat :app:compileAppDebugKotlin

# Assemble all variants
./gradlew assembleAppRelease

# Assemble without R8 (for crash debugging — no minification/shrinking)
./gradlew assembleAppNoR8

# Debug build
./gradlew assembleAppDebug

# Run unit tests (JVM, local)
./gradlew test

# Run a single test class
./gradlew test --tests "io.legado.app.model.cache.CacheDownloadQueueTest"

# Run connected Android tests
./gradlew connectedAndroidTest

# Lint
./gradlew lint

# Update Cronet (after changing CronetVersion in gradle.properties)
./gradlew app:downloadCronet
```

The project uses JDK 21 for development (set in `build.gradle.kts` via `jvmToolchain`). CI uses JDK 17 for building.

Gradle properties: 8 GB heap, configuration cache disabled (`gradle.properties:31`), non-transitive R classes, precise resource shrinking enabled.

## Architecture

This is a Material Design 3 fork of [Legado](https://github.com/gedoor/legado). `app/src/main/java/io/legado/app/` uses **Clean Architecture** with three layers:

| Layer | Package | Role |
|---|---|---|
| Data | `data/` | Room DB (`AppDatabase`, version 85, ~22 DAOs, ~25 entities), repository implementations |
| Domain | `domain/` | Gateway interfaces, use cases (14), domain models — no framework dependencies |
| UI | `ui/` | Jetpack Compose screens, Navigation 3 routes, ViewModels |

Additional top-level packages:
- **`help/`** — Infrastructure "glue": HTTP (OkHttp + Cronet), book content processing, backup/WebDAV, JS engine, config
- **`model/`** — Runtime state coordinators (not entities): `ReadBook`, `AudioPlay`, `CacheBook`, `BookCover`, etc.
- **`service/`** — Android foreground/background services (audio playback, TTS, download, web server)
- **`web/`** — Embedded HTTP server (Ktor) for remote bookshelf/source editing
- **`lib/`** — Third-party library wrappers (MOBI parser, WebDAV client, legacy View theme system, cronet)
- **`base/`** — Abstract Activity/Fragment/ViewModel base classes
- **`utils/`** — Extension functions and utility classes (~70 files)

Modules: `:app`, `:modules:book` (epub/TXT parsing, namespace `me.ag2s`), `:modules:rhino` (Rhino JS wrapper, namespace `com.script`). There is also a Vue 3 web frontend in `modules/web/` (pnpm, separate from the Android build).

## Dependency Injection (Koin)

Two modules loaded in `App.onCreate()`:

```kotlin
startKoin {
    modules(appDatabaseModule, appModule)
}
```

- **`di/appDatabaseModule.kt`** — Singleton `AppDatabase` + factory bindings for all 22 DAOs
- **`di/appModule.kt`** — Singletons (repositories, use cases, gateways, Coil `ImageLoader`), `viewModelOf` / `viewModel { }` for all ViewModels, some parameterized definitions

Gateways are bound to their repository implementations explicitly (e.g., `single<LocalBookGateway> { LocalBookRepository(get()) }`), not through `singleOf`.

## Navigation

Uses **Jetpack Navigation 3** (`androidx.navigation3`) with type-safe `@Serializable` sealed interfaces for route keys:

```kotlin
@Serializable
private sealed interface MainRoute : NavKey
@Serializable
private data object MainRouteHome : MainRoute
@Serializable
private data class MainRouteCache(val groupId: Long) : MainRoute
```

`MainActivity` holds a single `NavDisplay` with `entryProvider { ... }` defining all composable entries. `Launcher0` through `LauncherW` extend `MainActivity` to provide multiple launcher icon alias entries. Separate activities handle the reader (`ReadBookActivity` — still View-based), book info, source management, replace rules, file manager, QR scanner, etc.

## Theme System

A multi-engine theming system in `ui/theme/`:

1. **Material 3 Expressive** (default): Uses `MaterialExpressiveTheme` with `MotionScheme.expressive()`
2. **Miuix** (alternative): Uses `top.yukonga.miuix.kmp` theming engine

14 theme modes (`AppThemeMode` enum) — Dynamic (Monet), 12 named presets, Custom (MaterialKolor seed-color generation), Transparent. `CustomColorScheme` wraps `com.materialkolor` with configurable `PaletteStyle` (TonalSpot, Neutral, Vibrant, Expressive, Rainbow, etc.) and `ColorSpec` (2021 vs 2025).

Legacy View-based theme still exists in `lib/theme/` (used by non-migrated screens like `ReadBookActivity`).

## Hybrid Compose + View

The app is mid-migration from Views to Compose. View-based screens (reader, book info, source management) coexist with Compose screens (main tabs, settings, search, RSS, cache management). XML layouts, `viewBinding`, and traditional Activities are still heavily used. The `viewBinding` build feature is enabled but Compose screens are the target.

## Jetpack Compose Requirements (new screens MUST follow)

All **new** UI screens must be implemented in Jetpack Compose following the patterns below. Do **not
** create new View-based Activities/Fragments/XML layouts. Existing View-based screens can remain
until migrated.

### MVI/UDF Architecture

Every Compose screen follows a strict **Model-View-Intent** pattern with three artifacts defined in
a `*Contract.kt` file:

```
ui/{feature}/
├── XxxContract.kt      // UiState, Intent, Effect (and optionally Sheet/Dialog)
├── XxxViewModel.kt     // ViewModel
├── XxxScreen.kt        // Screen composable
└── XxxRouteScreen.kt   // (optional) outer wrapper for activity results / lifecycle
```

**Contract definitions:**

```kotlin
// @Stable data class — all screen state in one place
@Stable
data class XxxUiState(
    val loading: Boolean = false,
    val items: ImmutableList<ItemUi> = persistentListOf(),
    val activeSheet: XxxSheet? = null,
    val activeDialog: XxxDialog? = null,
)

// sealed interface — every user action is an Intent
sealed interface XxxIntent {
    data class LoadData(val id: Long) : XxxIntent
    data object Refresh : XxxIntent
}

// sealed interface — one-shot side effects (navigation, toast, etc.)
sealed interface XxxEffect {
    data class ShowToast(val message: String) : XxxEffect
    data class NavigateTo(val route: MainRoute) : XxxEffect
}

// (optional) sealed interface for multi-sheet/dialog scenarios
sealed interface XxxSheet { data object Filter : XxxSheet }
sealed interface XxxDialog { data class Confirm(val msg: String) : XxxDialog }
```

**Naming rules:**

- State: `{Feature}UiState` — `@Stable data class`
- Intent: `{Feature}Intent` — `sealed interface` with `data class` / `data object` members
- Effect: `{Feature}Effect` — `sealed interface`
- Sheet/Dialog: `{Feature}Sheet`, `{Feature}Dialog` — `sealed interfaces` stored in UiState

### ViewModel

```kotlin
class XxxViewModel(/* injected dependencies */) : ViewModel() {

    private val _uiState = MutableStateFlow(XxxUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<XxxEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    fun onIntent(intent: XxxIntent) {
        when (intent) {
            is XxxIntent.LoadData -> loadData(intent.id)
            is XxxIntent.Refresh -> refresh()
        }
    }

    private fun loadData(id: Long) {
        // Use viewModelScope, update _uiState via update { it.copy(...) }
    }
}
```

Key rules:

- Extend `ViewModel()` directly (not `BaseViewModel`).
- `_uiState` is `MutableStateFlow`, exposed as `StateFlow` via `.asStateFlow()`.
- `_effects` is `MutableSharedFlow(extraBufferCapacity = 16)`, exposed via `.asSharedFlow()`.
- Emit effects via `_effects.tryEmit(...)`.
- Single `onIntent()` entry point, dispatched via `when`.

### Screen Composable

```kotlin
// Stateless screen — ViewModel wired in entry provider or RouteScreen
@Composable
fun XxxScreen(
    state: XxxUiState,
    onIntent: (XxxIntent) -> Unit,
    effects: Flow<XxxEffect>,                   // one-shot effects from ViewModel
    onBack: () -> Unit,
    onNavigateToYyy: (YyyRoute) -> Unit,
) {
    // Collect effects
    LaunchedEffect(Unit) {
        effects.collectLatest { effect ->
            when (effect) {
                is XxxEffect.ShowToast -> { /* ... */ }
                is XxxEffect.NavigateTo -> onNavigateToYyy(effect.route)
            }
        }
    }

    AppScaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = { Text("Title") },
                scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior(),
                navigationButton = { TopBarNavigationButton(onBack) },
            )
        },
    ) { contentPadding ->
        // UI content, no business logic here
    }
}
```

Key rules:

- Screen is **stateless** — receives `state`, `onIntent`, `effects`, never accesses ViewModel
  directly.
- Effects collected in `LaunchedEffect(Unit) { ... }` using `collectLatest`.
- Alternatively, effects can be collected in the outer `RouteScreen` or entry provider if the screen
  doesn't need them directly.
- Use project custom widgets: `AppScaffold`, `AppText`, `AppIcon`, `AppIcons`, `AppAlertDialog`,
  `AppModalBottomSheet`, `NormalCard`, `GlassMediumFlexibleTopAppBar`, `TopBarNavigationButton`,
  `TopBarActionButton`, etc.
- No business logic, no direct DB/network calls in composables.

Two input patterns are acceptable:

- **Stateless (preferred for new screens):** `state: XxxUiState` + `onIntent: (XxxIntent) -> Unit` —
  ViewModel wired in entry provider or RouteScreen.
- **ViewModel as default param:** `viewModel: XxxViewModel = koinViewModel()` — simpler for
  standalone screens.

### Stability

- All `UiState` and UI item data classes **must** be annotated with `@Stable`.
- Use `ImmutableList` (from `kotlinx.collections.immutable`) for list properties in state classes,
  not `List` or `MutableList`.
- Prefer `persistentListOf()` / `toImmutableList()` for default values.

### Navigation

Uses **Navigation 3** (`androidx.navigation3`). Routes are `@Serializable` sealed interfaces:

```kotlin
// In MainNavKey.kt
@Serializable
data class MainRouteXxx(val id: Long) : MainRoute
```

Entry registered in `MainNavGraph.kt`:

```kotlin
entry<MainRouteXxx> { route ->
    val viewModel = koinViewModel<XxxViewModel>()
    XxxScreen(
        state = viewModel.uiState.collectAsStateWithLifecycle().value,
        onIntent = viewModel::onIntent,
        onBack = { onNavigateBack() },
        onNavigateToYyy = { onNavigateToRoute(it) },
    )
}
```

Key rules:

- Screens **never** reference the navigator directly — receive `onBack`, `onNavigateToXxx` lambdas.
- Navigation is callback-based, wired by the entry provider.
- New routes added to the `MainRoute` sealed interface in `MainNavKey.kt`.

### Koin DI

- Register ViewModels in `di/appModule.kt` with `viewModelOf(::XxxViewModel)`.
- Inject in Compose via `koinViewModel()` (default param or explicit in entry provider).
- For keyed ViewModels (e.g. per-book): `koinViewModel<XxxViewModel>(key = route.bookUrl)`.
- Repositories/gateways/use cases registered as `singleOf(::...)`.

### Activity Base Class

New standalone Compose activities extend `BaseComposeActivity`:

```kotlin
class XxxActivity : BaseComposeActivity() {
    @Composable
    override fun Content() {
        // Screen content — AppTheme is already applied by the base class
    }
}
```

### RouteScreen Wrapper

For screens needing activity result handling, lifecycle observation, or permission requests, use a
two-layer pattern:

- Outer `XxxRouteScreen`: handles `ActivityResultLauncher`, lifecycle callbacks, file pickers,
  permission requests. Wires ViewModel.
- Inner `XxxScreen`: pure UI, stateless with `state` + `onIntent`.

### Material 3 vs Miuix

The project supports two Compose theme engines. If a screen needs engine-specific UI, branch on:

```kotlin
if (ThemeResolver.isMiuixEngine(LegadoTheme.composeEngine)) {
    // Miuix implementation
} else {
    // Material 3 implementation
}
```

For detailed Compose review conventions and migration patterns, see
`.Codex/skills/legado-compose-review/`.

## Rhino JavaScript Engine

Book sources, RSS sources, and HTTP TTS use JavaScript rules. `initRhino()` in `App.kt` registers `NativeBaseSource` wrappers for `BookSource`, `RssSource`, `HttpTTS` (writable JS objects) and `ReadOnlyJavaObject` wrappers for rule entities. Rule parsing logic lives in `help/source/` and `model/analyzeRule/`.

## Important Constraints

- **Do not update jsoup** beyond 1.16.2 — a breaking change in newer versions (see [jsoup#2017](https://github.com/jhy/jsoup/pull/2017)) affects `AnalyzeByJSoup.kt` and the JsoupXpath library
- **Do not update hutool** beyond 5.8.22 — pinned in `libs.versions.toml:42`
- Package name discrepancy: code namespace is `io.legado.app` but `applicationId` is `io.legato.kazusa`
- Min SDK 26, target SDK 37, compile SDK 37
- Release builds enable R8 minification + resource shrinking; `noR8` variant disables both for crash debugging
- APK is split by ABI (`armeabi-v7a`, `arm64-v8a`, plus universal)
- Firebase Analytics and Performance are included; `google-services` plugin applied

## Web Frontend

Located in `modules/web/` — a Vue 3 + TypeScript + Vite project for remote bookshelf and source editing. Must connect to the app's built-in HTTP server (started via `WebService` in the main activity settings). Commands:

```bash
cd modules/web
pnpm install
pnpm dev       # dev server
pnpm build     # production build
```

Set `VITE_API` in `.env.development` to the app's web service IP.



## 编码前思考
- 明确假设，不确定时询问而非猜测。
- 存在歧义时，列出多种解释，不默默选定单一方案。
- 如果任务有明显更简单的做法，直接指出优化思路。
- 发现代码矛盾、逻辑不一致时及时暂停，请求信息澄清。

## 简洁优先
- 用最少的代码解决问题，拒绝冗余实现。
- 不为一次性需求创建抽象层、复杂架构。
- 不盲目增加扩展性、可配置性，应对“未来可能用到”的场景。
- 若代码可大幅精简，主动重写优化。
- 校验标准：以资深工程师视角判断，代码若过于复杂，立即简化。

## 精准修改
- 仅修改与当前任务直接相关的代码内容。
- 不顺手优化相邻代码、注释、排版格式。
- 不重构原本可以正常运行的代码模块。
- 严格匹配项目现有代码风格，保留原有编码习惯。
- 因本次修改产生的无效导入、废弃变量，可直接删除。
- 发现项目中原有的死代码、冗余内容，仅做文字提醒，不擅自删除。

## 目标驱动执行
- 执行任务前，定义清晰、可落地的成功标准。
- 将“修复Bug”转化为：编写用例复现问题，再调试至用例正常通过。
- 将“新增校验功能”转化为：针对异常输入编写测试用例，保证全部通过。
- 将“代码重构”转化为：完成重构后，确保原有所有测试用例正常运行。
- 多步骤复杂任务，先输出简短执行计划，同时标注每一步的验证方式。

## 提示
- maven仓库路径在 E:\DevelopSoft\JetBrains\mavenRepository
- 统一中文回复
- 读写文件统一utf-8格式
- gradle缓存和项目放到同一个盘中，文件夹名字gradle-home

## 番茄小说集成任务（feature/fanqie-sync 分支）

已完成并实机验证的三个修复，当前分支提交即此任务的交付物：

1. **阅读进度不同步**：阅读器是 MainActivity 内 Compose 导航路由（`MainRouteReadBook`），BACK 退出时 MainActivity 不 stop，原挂 `onActivityStopped` 的 flush 实际不触发。已在 `ReadBookViewModel.onCleared()`（`ui/book/read/ReadBookViewModel.kt`）调用 `FanqieProgressSyncer.flush()`——阅读会话真正结束时触发；`FanqieFeature.kt` 中 `onActivityStopped` 仍作退后台兜底。
2. **番茄书架页进度 off-by-one**：`FanqieScreen.kt` 显示章号改为 `readChapterIndex + 1`（索引 0-based，章号 1-based）。
3. **App 启动慢**：`FanqieFeature.kt` 移除启动时全量云同步，仅 `refreshLoginState()` + 12h 循环；实测冷启动 68928ms → 3820ms/1208ms。

调试日志保留：`FanqieProgressSyncer`（TAG=`FanqieProgress`，逐条件打日志）+ `FanqieApi.updateProgress`（bookId/itemId/index/fraction），实机日志见 work log。

番茄索引语义：`durChapterIndex`/`readChapterIndex` 为 0-based 目录索引；显示章号 `readChapterIndex + 1`；readTs 秒、durChapterTime 毫秒。

关键环境（沿用 build 说明）：调试包 `io.legato.kazusa.debug`；设备序列号 `872d5417`；adb 在 `E:\DevelopSoft\AndroidSdk\platform-tools\adb.exe`；构建需设 `JAVA_HOME=E:\DevelopSoft\Java\jdk-21`、`GRADLE_USER_HOME=E:\gradle-home`，前台运行 gradlew 并把输出 `Out-File` 到临时文件。

`fanqie_app.json` 是番茄书源定义，用户决定**不提交**到仓库，勿 add。

实机操作备忘（继续开发时用）：
- 上滑翻页 `input swipe 900 1300 200 1300 300`；点击正文 `tap 540 1300` 不翻页。
- 番茄分组 tab `tap 353 464`，十日终焉封面 `tap 195 800`。
- uiautomator dump 在 MIUI 先打印 theme_compatibility.xml ENOENT 但 dump 仍成功；空 dump（len=2810）表示页面转场中需等待。
- 休眠陷阱：设备自动锁屏（`mDreamingLockscreen=true`）后 `wm dismiss-keyguard` 无效，需用户手动解锁。