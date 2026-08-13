package io.legado.app.fanqie

import io.legado.app.model.LegacyReaderSnapshot
import io.legado.app.model.ReadBook
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object FanqieProgressSyncer {

    private const val TAG = "FanqieProgress"
    private const val DEBOUNCE_MS = 30_000L
    private const val DIRECTORY_TTL_MS = 60 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val directoryCache = mutableMapOf<String, Pair<Long, List<FanqieChapter>>>()
    /** 每个 bookId 最后一次上报的章节索引，同章节不重复上报（flush 仍会强制重试）。 */
    private val lastReportedChapter = mutableMapOf<String, Int>()
    /** 每个 bookId 独立的防抖 Job，key 为 bookId。 */
    private val pendingDebounce = mutableMapOf<String, Job>()
    /** 云端进度有更新时通知当前阅读器，key 为 bookUrl，值为 (chapterIndex, chapterTimeMs)。 */
    internal var progressUpdateCallback: ((bookUrl: String, chapterIndex: Int, chapterTime: Long) -> Unit)? = null

    fun start() {
        if (!started.compareAndSet(false, true)) {
            LogUtils.d(TAG, "start: already started, skip")
            return
        }
        LogUtils.d(TAG, "start: progress syncer enabled, will report on snapshot changes (debounce=${DEBOUNCE_MS}ms)")
        scope.launch {
            ReadBook.snapshot.map { Triple(it, it.bookUrl, resolveBookId(it.bookUrl)) }
                .collect { (snapshot, _, bookId) ->
                    if (bookId == null) return@collect
                    if (!FanqieConfig.autoSyncProgress) {
                        LogUtils.d(TAG, "snapshot: autoSyncProgress off, skip")
                        return@collect
                    }
                    if (snapshot.isLocalBook) return@collect
                    if (!FanqieApi.hasCookie()) {
                        LogUtils.d(TAG, "snapshot: no cookie, skip")
                        return@collect
                    }
                    LogUtils.d(TAG, "snapshot: bookId=$bookId chapterIndex=${snapshot.chapterIndex} pos=${snapshot.chapterPos} → schedule debounce")
                    // 取消该 book 已挂起的防抖，重新计时，保证每次 snapshot 变化都会触发上报
                    pendingDebounce[bookId]?.cancel()
                    pendingDebounce[bookId] = scope.launch {
                        delay(DEBOUNCE_MS)
                        pendingDebounce.remove(bookId)
                        report(snapshot, bookId)
                    }
                }
        }
    }

    /**
     * 立即上报当前阅读位置，跳过防抖。用于阅读器关闭/App 退后台时兜底，
     * 避免 30s 防抖内退出导致进度丢失。非番茄书或开关关闭时静默返回。
     */
    fun flush() {
        if (!started.get()) return
        scope.launch {
            val snapshot = ReadBook.snapshot.value
            if (!FanqieConfig.autoSyncProgress) {
                LogUtils.d(TAG, "flush: autoSyncProgress off")
                return@launch
            }
            if (snapshot.isLocalBook) {
                LogUtils.d(TAG, "flush: local book")
                return@launch
            }
            val bookId = resolveBookId(snapshot.bookUrl) ?: run {
                LogUtils.d(TAG, "flush: no bookId, bookUrl=${snapshot.bookUrl}")
                return@launch
            }
            if (!FanqieApi.hasCookie()) {
                LogUtils.d(TAG, "flush: no cookie")
                return@launch
            }
            // 取消该 book 的待执行防抖，立即上报
            pendingDebounce.remove(bookId)?.cancel()
            LogUtils.d(TAG, "flush: report bookId=$bookId chapterIndex=${snapshot.chapterIndex}")
            report(snapshot, bookId)
        }
    }

    private fun resolveBookId(bookUrl: String?): String? {
        if (bookUrl == null) return null
        FanqieConstants.parseBookId(bookUrl)?.let { return it }
        val current = ReadBook.book
        if (current?.bookUrl == bookUrl) {
            current.variableMap[FanqieConstants.BOOK_ID_VARIABLE]?.let { return it }
        }
        return null
    }

    private suspend fun report(snapshot: LegacyReaderSnapshot, bookId: String) {
        if (snapshot.chapterIndex < 0) {
            LogUtils.d(TAG, "report: chapterIndex<0 skip")
            return
        }
        val chapters = directory(bookId) ?: run {
            LogUtils.d(TAG, "report: directory null, fetch failed")
            return
        }
        LogUtils.d(TAG, "report: directory ok chapters=${chapters.size} bookId=$bookId")
        val chapter = chapters.getOrNull(snapshot.chapterIndex) ?: run {
            LogUtils.d(TAG, "report: chapter not found idx=${snapshot.chapterIndex} size=${chapters.size}")
            return
        }
        val lastChapter = lastReportedChapter[bookId]
        if (lastChapter != null && lastChapter == snapshot.chapterIndex) {
            LogUtils.d(TAG, "report: same chapter skip bookId=$bookId chapterIndex=${snapshot.chapterIndex}")
            return
        }
        val cloudIndex = cloudChapterIndex(bookId)
        if (cloudIndex != null && snapshot.chapterIndex < cloudIndex) {
            LogUtils.d(TAG, "report: behind cloud skip local=${snapshot.chapterIndex} cloud=$cloudIndex")
            return
        }
        LogUtils.d(TAG, "report: send bookId=$bookId chapterIndex=${snapshot.chapterIndex} itemId=${chapter.itemId} fraction=${"%.4f".format(((snapshot.chapterIndex + 0.5f) / chapters.size).coerceIn(0f, 1f))}")
        val fraction = if (chapters.size > 0) {
            ((snapshot.chapterIndex + 0.5f) / chapters.size).coerceIn(0f, 1f)
        } else {
            0f
        }
        runCatching { FanqieApi.updateProgress(bookId, chapter.itemId, snapshot.chapterIndex, fraction) }
            .onSuccess {
                lastReportedChapter[bookId] = snapshot.chapterIndex
                LogUtils.d(TAG, "report: ok bookId=$bookId chapterIndex=${snapshot.chapterIndex} fraction=$fraction")
            }
            .onFailure { e ->
                LogUtils.e(TAG, "report: failed bookId=$bookId ${e.message}\n${e.stackTraceToString()}")
            }
    }

    private suspend fun cloudChapterIndex(bookId: String): Int? {
        val progress = FanqieApi.fetchProgressForBook(bookId) ?: return null
        val itemId = progress.readItemId
        if (itemId.isNotBlank()) {
            directory(bookId)?.let { chapters ->
                val idx = chapters.indexOfFirst { it.itemId == itemId }
                if (idx >= 0) return idx
            }
        }
        return progress.readChapterIndex
    }

    private suspend fun directory(bookId: String): List<FanqieChapter>? {
        directoryCache[bookId]?.let { (time, chapters) ->
            if (System.currentTimeMillis() - time < DIRECTORY_TTL_MS) {
                return chapters
            }
        }
        return runCatching {
            val chapters = FanqieApi.fetchDirectory(bookId)
            directoryCache[bookId] = System.currentTimeMillis() to chapters
            chapters
        }.getOrNull()
    }
}
