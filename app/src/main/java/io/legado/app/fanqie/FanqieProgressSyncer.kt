package io.legado.app.fanqie

import android.util.Log
import io.legado.app.model.LegacyReaderSnapshot
import io.legado.app.model.ReadBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

object FanqieProgressSyncer {

    private const val TAG = "FanqieProgress"
    private const val DEBOUNCE_MS = 30_000L
    private const val DIRECTORY_TTL_MS = 60 * 60 * 1000L
    private const val POS_BUCKET = 2_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val started = AtomicBoolean(false)
    private val directoryCache = mutableMapOf<String, Pair<Long, List<FanqieChapter>>>()
    private val lastReported = mutableMapOf<String, Pair<Int, Int>>()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            ReadBook.snapshot
                .map { it to progressKey(it) }
                .distinctUntilChanged { old, new -> old.second == new.second }
                .collect { (snapshot, _) ->
                    if (!FanqieConfig.autoSyncProgress) return@collect
                    if (snapshot.isLocalBook) return@collect
                    val bookId = resolveBookId(snapshot.bookUrl) ?: return@collect
                    if (!FanqieApi.hasCookie()) return@collect
                    delay(DEBOUNCE_MS)
                    report(snapshot, bookId)
                }
        }
    }

    /**
     * 立即上报当前阅读位置，跳过防抖。用于阅读器关闭/App 退后台时兜底，
     * 避免 30s 防抖内退出导致进度丢失。非番茄书或开关关闭时静默返回。
     */
    fun flush() {
        if (!started.get()) {
            Log.d(TAG, "flush: not started")
            return
        }
        scope.launch {
            val snapshot = ReadBook.snapshot.value
            if (!FanqieConfig.autoSyncProgress) {
                Log.d(TAG, "flush: autoSyncProgress off")
                return@launch
            }
            if (snapshot.isLocalBook) {
                Log.d(TAG, "flush: local book")
                return@launch
            }
            val bookId = resolveBookId(snapshot.bookUrl) ?: run {
                Log.d(TAG, "flush: no bookId, bookUrl=${snapshot.bookUrl}")
                return@launch
            }
            if (!FanqieApi.hasCookie()) {
                Log.d(TAG, "flush: no cookie")
                return@launch
            }
            Log.d(TAG, "flush: report bookId=$bookId chapterIndex=${snapshot.chapterIndex}")
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

    private fun progressKey(snapshot: LegacyReaderSnapshot) =
        snapshot.bookUrl to (snapshot.chapterIndex to snapshot.chapterPos / POS_BUCKET)

    private suspend fun report(snapshot: LegacyReaderSnapshot, bookId: String) {
        if (snapshot.chapterIndex < 0) {
            Log.d(TAG, "report: chapterIndex<0 skip")
            return
        }
        val chapters = directory(bookId) ?: run {
            Log.d(TAG, "report: directory null")
            return
        }
        val chapter = chapters.getOrNull(snapshot.chapterIndex) ?: run {
            Log.d(TAG, "report: chapter not found idx=${snapshot.chapterIndex} size=${chapters.size}")
            return
        }
        val last = lastReported[bookId]
        if (last != null && last.first == snapshot.chapterIndex &&
            abs(last.second - snapshot.chapterPos) < POS_BUCKET
        ) {
            Log.d(TAG, "report: dedupe skip last=$last")
            return
        }
        val cloudIndex = cloudChapterIndex(bookId)
        if (cloudIndex != null && snapshot.chapterIndex < cloudIndex) {
            Log.d(TAG, "report: behind cloud skip local=${snapshot.chapterIndex} cloud=$cloudIndex")
            return
        }
        val fraction = if (chapters.size > 0) {
            ((snapshot.chapterIndex + 0.5f) / chapters.size).coerceIn(0f, 1f)
        } else {
            0f
        }
        runCatching { FanqieApi.updateProgress(bookId, chapter.itemId, snapshot.chapterIndex, fraction) }
            .onSuccess {
                lastReported[bookId] = snapshot.chapterIndex to snapshot.chapterPos
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
