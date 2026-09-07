# 主题系统

`ui/theme/` 下的多引擎主题系统。

## 引擎

1. **Material 3 Expressive**（默认）：`MaterialExpressiveTheme` + `MotionScheme.expressive()`
2. **Miuix**（备选）：`top.yukonga.miuix.kmp` 主题引擎

## 主题模式

`AppThemeMode` 枚举共 14 种模式：Dynamic（Monet）、12 个命名预设、Custom（MaterialKolor 种子色生成）、Transparent。

`CustomColorScheme` 封装 `com.materialkolor`，支持可配置的：

- `PaletteStyle`：TonalSpot、Neutral、Vibrant、Expressive、Rainbow 等
- `ColorSpec`：2021 版 / 2025 版

## 墨水屏模式（E-Ink）

- 判定：`ThemeSettings.isEInkMode`（`appTheme == "4"`），定义于 `domain/model/settings/ThemeSettings.kt:78`。
- 生效范围：
  - `HazeStyle.kt`：`responsiveHazeSource` / `responsiveHazeEffect` / `responsiveHazeEffectFixedStyle` / `regularHazeEffect` 全部在 `enableBlur` 基础上追加 `!isEInkMode` 条件，墨水屏下彻底屏蔽 Haze GPU 毛玻璃 Shader，避免残影与卡顿。
  - `AppScaffold.kt` 的毛玻璃背景与 `LocalHazeState` 注入依赖上述 Modifier，无需单独改。
  - `DialogExtensions.kt` 与 `lib/prefs/` 对话框：新增 `applyEInkBorderIfNeeded()`，并在 `EditTextPreferenceDialog`、`ListPreferenceDialog`、`MultiSelectListPreferenceDialog` 中统一调用，确保墨水屏设置对话框具有清晰高对比度边框。

## 遗留 View 主题

`lib/theme/` 仍存在遗留 View 主题（供未迁移界面如 `ReadBookActivity` 使用）。
