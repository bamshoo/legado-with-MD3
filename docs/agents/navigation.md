# 导航

使用 **Jetpack Navigation 3**（`androidx.navigation3`），类型安全的 `@Serializable` sealed interface 作为路由键：

```kotlin
@Serializable
private sealed interface MainRoute : NavKey
@Serializable
private data object MainRouteHome : MainRoute
@Serializable
private data class MainRouteCache(val groupId: Long) : MainRoute
```

## 结构

- `MainActivity` 持有单个 `NavDisplay`，`entryProvider { ... }` 定义所有 composable 条目。
- `Launcher0` ~ `LauncherW` 继承 `MainActivity`，提供多个桌面图标别名入口。
- 独立 Activity 处理：阅读器（`ReadBookActivity`，仍是 View 系）、书籍详情、书源管理、替换规则、文件管理器、二维码扫描等。
