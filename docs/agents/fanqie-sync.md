# 番茄小说集成（feature/fanqie-sync 分支）

番茄小说（fanqienovel.com）云书架同步 + 阅读进度双向同步功能。全部代码在 `app/src/main/java/io/legado/app/fanqie/`，是独立于 Clean Architecture 三层之外的 `help` 平级基础设施包，直接操作 `appDb`/`ReadBook`/`CookieStore`。

## 模块结构

| 文件 | 职责 |
|---|---|
| `fanqie/FanqieConstants.kt` | URL/UA/AID/版本常量；`parseBookId(bookUrl)` 用 `^https://fanqienovel\.com/page/(\d+)` 提取书号 |
| `fanqie/FanqieConfig.kt` | SharedPreferences `FanqieConfig`：`groupId`、`autoSyncProgress`（默认 true）、`lastSyncTime` |
| `fanqie/FanqieApi.kt` | HTTP 客户端（OkHttp + CookieStore）：书架信息、multidetail 详情、目录、进度拉取/上报、云书架删除；`loginState: StateFlow<FanqieLoginState>`；`hasCookie()`/`csrfToken()`；`FanqieBook`/`FanqieChapter`/`FanqieSyncResult` 数据类 |
| `fanqie/FanqieGroup.kt` | 私有分组（`isPrivate=true`，组名"番茄小说"）的幂等创建 `ensureGroup()`、备份恢复后重建 `afterRestore()`、`isFanqieGroup(groupId)` 判断；**固定 ID = `1L shl 62`**，永不与 `getUnusedId()` 产生的幂次 ID 冲突 |
| `fanqie/FanqieShelfRepository.kt` | 云书架 ↔ 本地书架（Room `bookDao`）双向同步 `syncFromCloud()`；group 位 = `groupId`（番茄书仅属于番茄组）；空书架时不做移除清理；`progressUpdateCallback` 在进度更新时回调，通知当前阅读器 |
| `fanqie/FanqieProgressSyncer.kt` | 阅读进度自动上报：监听 `ReadBook.snapshot`，per-book 独立防抖（`pendingDebounce` Map）→ 30s 后 `report()`；`flush()` 取消防抖立即上报；同章节去重（`lastReportedChapter`）；`progressUpdateCallback` 在云端进度比本地新时通知阅读器调 `setProgress()` |
| `fanqie/FanqieFeature.kt` | 入口 `init()`：注册 `onActivityStopped`→`flush()` 兜底；启动协程：ensureGroup → start syncer → refreshLoginState → 12h 定时全量同步 |
| `fanqie/ui/FanqieContract.kt` | MVI Contract：`FanqieUiState`/`FanqieIntent`/`FanqieEffect` |
| `fanqie/ui/FanqieViewModel.kt` | VM：加载/同步/登录/入架移架/开关自动上报 |
| `fanqie/ui/FanqieScreen.kt` | Compose 界面：云书架列表、进度、登录/同步入口 |

## 数据流

- **拉取（云→本地）**：`FanqieViewModel.load()/syncNow()` → `FanqieApi.fetchShelfBooks()`（书架 + 进度 + multidetail + 目录并行 `async/awaitAll` + 缺作者时爬页面补全）→ `FanqieShelfRepository.syncFromCloud()` 写 Room。
- **上报（本地→云）**：`FanqieProgressSyncer.start()` 收集 `ReadBook.snapshot`，条件满足（非本地书、是番茄书、有 cookie、autoSync 开）→ 每 bookId 独立防抖 30s → `report()` → `updateProgress`。`flush()` 取消防抖 Job 立即上报。
- **拉取（开书时）**：`ReadBookLoadDelegate.initBook()` 末尾调 `syncFanqieProgressIfApplicable()` → 异步 `syncFromCloud(FanqieApi.fetchShelfBooks())`；**全局 10 分钟间隔保护**（`lastSyncTime` 在 `syncFromCloud` 成功结束时更新，`FanqieShelfRepository.kt:61`），非每次开书都拉；云端进度更新时通过 `progressUpdateCallback` 调 `ReadBook.setProgress()` 同步当前阅读会话。
- **备份排除**：备份时跳过有 `variableMap["fanqieBookId"]` 的书，番茄书永远从云端重新拉取，避免源更换后 cloudId 匹配不上导致重复入库。
- **bookId 解析**：优先 `parseBookId(bookUrl)`；换源后的书靠 `variableMap["fanqieBookId"]`（`FanqieConstants.BOOK_ID_VARIABLE`）兜底；换源时 `ChangeBookSourceUseCase` 从 `oldBook.variableMap` 保留 fanqieBookId。
- **番茄组 ID**：固定值 `1L shl 62`，`getUnusedId()` 永远不产生此值，跨设备恢复通过 `getByName` 兜底。

## 登录机制

登录态 = CookieStore 中是否有番茄域名 cookie。UI 流程：`FanqieScreen` 点登录 → `OpenLogin` effect → 打开 `WebViewActivity`（传入 sourceOrigin/sourceName）→ 用户网页登录 → ActivityResult 回传 → `LoginCompleted` → 从 WebView `CookieManager` 取 cookie 写入 `CookieStore` → `refreshLoginState()` → 重新 load。

## 集成点（勿随意改动）

- `App.kt`：`FanqieFeature.init()`（番茄注释标记处）。
- `di/appModule.kt`：`viewModelOf(::FanqieViewModel)`。
- `ui/main/MainNavKey.kt`/`MainNavGraph.kt`/`MainNavigator.kt`：`MainRouteFanqie` 路由注册；`MainScreen` 与 `BookshelfScreen` 传 `onNavigateToFanqie`。
- `ui/main/bookshelf/`：`BookshelfScreen` 顶部刷新按钮旁新增"番茄同步"按钮、番茄分组空态登录引导、`FanqieLoginBanner` 横幅；`BookshelfViewModel` combine `FanqieApi.loginState` → `fanqieLoginState`；`BookshelfUiState` 加该字段。
- 私有组保护：`BookGroupRepository.flowSelect` 过滤掉番茄组；`BookshelfViewModel` 分组列表、`GroupManageSheet`、`GroupEditDialog`、`GroupEditSheet` 均用 `FanqieGroup.isFanqieGroup()` 排除/禁编辑。
- `domain/usecase/ChangeBookSourceUseCase.kt`：换源时若新书是番茄书则写 `fanqieBookId` 变量。
- `help/storage/Restore.kt`：备份恢复后调 `FanqieGroup.afterRestore()` 重建番茄组。
- `ui/book/read/ReadBookViewModel.kt` `onCleared()`：调 `FanqieProgressSyncer.flush()`（阅读会话真正结束）。

## 语义约定（容易踩坑）

- `durChapterIndex`/`readChapterIndex` 为 **0-based** 目录索引；显示章号须 `+1`。读 `FanqieApi.parseProgress` 的 `index`、`FanqieBook.readChapterIndex`、书架 `durChapterIndex` 均如此。
- `readTimestamp`（云端）单位**秒**；本地 `durChapterTime` 单位**毫秒**，换算 `*1000L`。
- 进度 `fraction` 按 `(chapterIndex + 0.5) / chapterCount` 计算，非真实章节内百分比。
- 番茄书本地 `bookUrl` = `https://fanqienovel.com/page/{bookId}`，`origin`= `https://fanqienovel.com`，并写入 `variableMap["fanqieBookId"]`。

## 已完成并实机验证的修复

1. **阅读进度不同步**：阅读器是 MainActivity 内 Compose 路由（`MainRouteReadBook`），BACK 退出时 MainActivity 不 stop，原挂 `onActivityStopped` 的 flush 不触发。改为 `ReadBookViewModel.onCleared()` 调 `flush()`——阅读会话真正结束时触发；`FanqieFeature.onActivityStopped` 保留作退后台兜底。
2. **番茄书架页进度 off-by-one**：`FanqieScreen.kt` 显示章号改为 `readChapterIndex + 1`。
3. **App 启动慢**：`FanqieFeature.kt` 移除启动时全量云同步，仅 `refreshLoginState()` + 12h 循环；实测冷启动 68928ms → 3820ms/1208ms。
4. **distinctUntilChanged 过滤重开同书**：去掉 `distinctUntilChanged`，改为 per-book 独立防抖 Job（`pendingDebounce` Map），重开同一书同一章也会重新触发 30s 防抖。
5. **短章节翻页被去重跳过**：去重从"章节+2000字符桶"改为"只比较章节索引"，短章节翻页不再被过滤；flush 负责兜底。
6. **开书时拉取云端进度**：`ReadBookLoadDelegate.initBook()` 末尾异步 `syncFromCloud(FanqieApi.fetchShelfBooks())`（全局 10 分钟间隔保护）；云端进度更新时通过 `progressUpdateCallback` 调 `ReadBook.setProgress()` 同步当前阅读会话。
7. **`syncFromCloud` group 位被覆盖**：`buildUpdatedBook` 和 `addToLocalShelf` 改为 `group = groupId`（番茄书仅属于番茄组，无其他分组位）。
8. **换源后 fanqieBookId 丢失**：`ChangeBookSourceUseCase.applyMigrationTo` 改为 `parseBookId(bookUrl) ?: oldBook.variableMap[...]` 兜底，换源后 fanqieBookId 一定保留。
9. **番茄同步按钮出现在所有分组页**：`BookshelfScreen` 中按钮条件加 `FanqieGroup.isFanqieGroup(selectedGroupId)` 判断。
10. **番茄组 ID 固定**：`FanqieGroup` 使用 `FIXED_ID = 1L shl 62`，永不与 `getUnusedId()` 产生的幂次 ID 冲突。
11. **开书同步误传 emptyList 清空番茄组**：`ReadBookLoadDelegate` 曾传 `syncFromCloud(cloudBooks = emptyList())`，移除循环拿空列表比对后把未换源番茄书的组位全清成"未分组"；改为真实 `FanqieApi.fetchShelfBooks()`，且移除循环加 `cloudBooks.isNotEmpty()` 防御。

调试日志保留：`FanqieProgressSyncer`（TAG=`FanqieProgress`，逐条件打日志）+ `FanqieApi.updateProgress`（bookId/itemId/index/fraction）。

## 关键环境与实机备忘

- 调试包 `io.legato.kazusa.debug`；设备序列号 `872d5417`；adb 在 `E:\DevelopSoft\AndroidSdk\platform-tools\adb.exe`。
- 构建需设 `JAVA_HOME=E:\DevelopSoft\Java\jdk-21`、`GRADLE_USER_HOME=E:\gradle-home`，前台运行 gradlew 并把输出 `Out-File` 到临时文件。
- `fanqie_app.json` 是番茄书源定义，用户决定**不提交**到仓库，勿 add。
- 上滑翻页 `input swipe 900 1300 200 1300 300`；点击正文 `tap 540 1300` 不翻页。番茄分组 tab `tap 353 464`，十日终焉封面 `tap 195 800`。
- uiautomator dump 在 MIUI 先打印 theme_compatibility.xml ENOENT 但 dump 仍成功；空 dump（len=2810）表示页面转场中需等待。
- 休眠陷阱：设备自动锁屏（`mDreamingLockscreen=true`）后 `wm dismiss-keyguard` 无效，需用户手动解锁。
