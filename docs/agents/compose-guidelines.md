# Compose 开发规范（详细）

> 新界面的完整 MVI/UDF 代码模板与详细规则。AGENTS.md 仅保留核心要点，以本文档为准。

## MVI/UDF 架构

每个 Compose 界面遵循严格 **Model-View-Intent** 模式，三个产物定义在 `*Contract.kt` 文件中：

```
ui/{feature}/
├── XxxContract.kt      // UiState, Intent, Effect (以及可选的 Sheet/Dialog)
├── XxxViewModel.kt     // ViewModel
├── XxxScreen.kt        // Screen composable
└── XxxRouteScreen.kt   // (可选) 外层包装：处理 Activity 结果 / 生命周期
```

### Contract 定义

```kotlin
// @Stable data class — 全部界面状态集中一处
@Stable
data class XxxUiState(
    val loading: Boolean = false,
    val items: ImmutableList<ItemUi> = persistentListOf(),
    val activeSheet: XxxSheet? = null,
    val activeDialog: XxxDialog? = null,
)

// sealed interface — 每个用户操作都是一个 Intent
sealed interface XxxIntent {
    data class LoadData(val id: Long) : XxxIntent
    data object Refresh : XxxIntent
}

// sealed interface — 一次性副作用（导航、Toast 等）
sealed interface XxxEffect {
    data class ShowToast(val message: String) : XxxEffect
    data class NavigateTo(val route: MainRoute) : XxxEffect
}

// (可选) 多 Sheet/Dialog 场景
sealed interface XxxSheet { data object Filter : XxxSheet }
sealed interface XxxDialog { data class Confirm(val msg: String) : XxxDialog }
```

### 命名规则

- State：`{Feature}UiState` — `@Stable data class`
- Intent：`{Feature}Intent` — `sealed interface`，成员为 `data class` / `data object`
- Effect：`{Feature}Effect` — `sealed interface`
- Sheet/Dialog：`{Feature}Sheet`、`{Feature}Dialog` — `sealed interface`，存入 UiState

## ViewModel

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

规则：

- 直接继承 `ViewModel()`（不用 `BaseViewModel`）。
- `_uiState` 为 `MutableStateFlow`，经 `.asStateFlow()` 暴露为 `StateFlow`。
- `_effects` 为 `MutableSharedFlow(extraBufferCapacity = 16)`，经 `.asSharedFlow()` 暴露。
- 用 `_effects.tryEmit(...)` 发射副作用。
- 单一 `onIntent()` 入口，`when` 分发。

## Screen Composable

```kotlin
// 无状态界面 — ViewModel 在 entry provider 或 RouteScreen 中接线
@Composable
fun XxxScreen(
    state: XxxUiState,
    onIntent: (XxxIntent) -> Unit,
    effects: Flow<XxxEffect>,                   // ViewModel 的一次性副作用
    onBack: () -> Unit,
    onNavigateToYyy: (YyyRoute) -> Unit,
) {
    // 收集副作用
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
        // UI 内容，无业务逻辑
    }
}
```

规则：

- Screen **无状态** — 接收 `state`、`onIntent`、`effects`，不直接访问 ViewModel。
- 副作用在 `LaunchedEffect(Unit) { ... }` 中用 `collectLatest` 收集。
- 也可在外部 `RouteScreen` 或 entry provider 收集副作用（当界面本身不需要时）。
- 使用项目自定义组件：`AppScaffold`、`AppText`、`AppIcon`、`AppIcons`、`AppAlertDialog`、`AppModalBottomSheet`、`NormalCard`、`GlassMediumFlexibleTopAppBar`、`TopBarNavigationButton`、`TopBarActionButton` 等。
- composable 中禁止业务逻辑、禁止直接 DB/网络调用。

两种输入模式：

- **无状态（新界面首选）：** `state: XxxUiState` + `onIntent: (XxxIntent) -> Unit` — ViewModel 在 entry provider 或 RouteScreen 接线。
- **ViewModel 默认参数：** `viewModel: XxxViewModel = koinViewModel()` — 独立界面更简单。

## 稳定性

- 所有 `UiState` 与 UI item 数据类必须标注 `@Stable`。
- 状态类中的列表属性用 `ImmutableList`（`kotlinx.collections.immutable`），不要用 `List` / `MutableList`。
- 默认值优先 `persistentListOf()` / `toImmutableList()`。

## 导航

使用 **Navigation 3**（`androidx.navigation3`）。路由是 `@Serializable` sealed interface：

```kotlin
// 在 MainNavKey.kt
@Serializable
data class MainRouteXxx(val id: Long) : MainRoute
```

在 `MainNavGraph.kt` 注册条目：

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

规则：

- 界面**永不直接引用** navigator — 接收 `onBack`、`onNavigateToXxx` lambda。
- 导航基于回调，由 entry provider 接线。
- 新路由加入 `MainNavKey.kt` 中的 `MainRoute` sealed interface。

## Koin DI

- 在 `di/appModule.kt` 用 `viewModelOf(::XxxViewModel)` 注册 ViewModel。
- Compose 中用 `koinViewModel()` 注入（默认参数或 entry provider 显式注入）。
- 按 key 的 ViewModel（如每本书）：`koinViewModel<XxxViewModel>(key = route.bookUrl)`。
- 仓库/Gateway/用例注册为 `singleOf(::...)`。

## Activity 基类

独立 Compose Activity 继承 `BaseComposeActivity`：

```kotlin
class XxxActivity : BaseComposeActivity() {
    @Composable
    override fun Content() {
        // Screen content — AppTheme 已由基类应用
    }
}
```

## RouteScreen 包装层

需要 Activity 结果处理、生命周期观察或权限请求的界面，使用两层模式：

- 外层 `XxxRouteScreen`：处理 `ActivityResultLauncher`、生命周期回调、文件选择器、权限请求，并接线 ViewModel。
- 内层 `XxxScreen`：纯 UI，无状态（`state` + `onIntent`）。

## Material 3 vs Miuix

项目支持两套 Compose 主题引擎。需要引擎专属 UI 时分支判断：

```kotlin
if (ThemeResolver.isMiuixEngine(LegadoTheme.composeEngine)) {
    // Miuix 实现
} else {
    // Material 3 实现
}
```

## 相关参考

- Compose 评审约定与迁移模式：`.Codex/skills/legado-compose-review/`
- Compose 迁移指南：`.agents/skills/legado-compose-migration/SKILL.md`
