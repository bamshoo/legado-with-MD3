# 架构说明

> 面向在此仓库工作的 AI 代理与开发者的架构参考文档。

本仓库是 [Legado](https://github.com/gedoor/legado) 的 **Material Design 3 分支**。`app/src/main/java/io/legado/app/` 使用 **Clean Architecture** 三层架构。

## 三层架构

| 层 | 包 | 职责 |
|---|---|---|
| Data | `data/` | Room DB（`AppDatabase`，版本 85，约 22 个 DAO、约 25 个实体）、仓库实现 |
| Domain | `domain/` | Gateway 接口、用例（14 个）、领域模型——无框架依赖 |
| UI | `ui/` | Jetpack Compose 界面、Navigation 3 路由、ViewModels |

## 顶层包

| 包 | 职责 |
|---|---|
| `help/` | 基础设施"胶水"：HTTP（OkHttp + Cronet）、书籍内容处理、备份/WebDAV、JS 引擎、配置 |
| `model/` | 运行时状态协调器（非实体）：`ReadBook`、`AudioPlay`、`CacheBook`、`BookCover` 等 |
| `service/` | Android 前台/后台服务（音频播放、TTS、下载、Web 服务器） |
| `web/` | 内嵌 HTTP 服务器（Ktor），用于远程书架/书源编辑 |
| `lib/` | 第三方库封装（MOBI 解析、WebDAV 客户端、遗留 View 主题系统、cronet） |
| `base/` | Activity/Fragment/ViewModel 抽象基类 |
| `utils/` | 扩展函数与工具类（约 70 个文件） |

## 模块

- `:app` — 主应用
- `:modules:book` — epub/TXT 解析（命名空间 `me.ag2s`）
- `:modules:rhino` — Rhino JS 封装（命名空间 `com.script`）
- `modules/web/` — Vue 3 web 前端（pnpm，独立于 Android 构建）

## 混合 Compose + View 现状

应用正处于 View 向 Compose 迁移中期。View 系界面（阅读器、书籍详情、书源管理）与 Compose 界面（主 Tab、设置、搜索、RSS、缓存管理）并存。XML 布局、`viewBinding`、传统 Activity 仍大量使用。`viewBinding` 构建特性已启用，但 Compose 界面是迁移目标。

## Rhino JavaScript 引擎

书源、RSS 源、HTTP TTS 使用 JavaScript 规则。`initRhino()` 在 `App.kt` 中注册：

- `NativeBaseSource` 包装：`BookSource`、`RssSource`、`HttpTTS`（可写 JS 对象）
- `ReadOnlyJavaObject` 包装：规则实体

规则解析逻辑位于 `help/source/` 和 `model/analyzeRule/`。
