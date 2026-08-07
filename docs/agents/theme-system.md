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

## 遗留 View 主题

`lib/theme/` 仍存在遗留 View 主题（供未迁移界面如 `ReadBookActivity` 使用）。
