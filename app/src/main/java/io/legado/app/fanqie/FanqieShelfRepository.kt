package io.legado.app.fanqie

import io.legado.app.utils.LogUtils
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book

object FanqieShelfRepository {

    private const val TAG = "FanqieShelfRepo"

    private val bookDao get() = appDb.bookDao

    suspend fun syncFromCloud(): FanqieSyncResult =
        syncFromCloud(FanqieApi.fetchShelfBooks())

    suspend fun syncFromCloud(
        cloudBooks: List<FanqieBook>,
        progressUpdateCallback: ((bookUrl: String, chapterIndex: Int, chapterTime: Long) -> Unit)? = null,
    ): FanqieSyncResult {
        LogUtils.d(TAG, "syncFromCloud: got ${cloudBooks.size} cloud books")
        val groupId = FanqieGroup.ensureGroup()
        val cloudBookIds = cloudBooks.mapTo(HashSet()) { it.bookId }

        val localBooks = bookDao.getAll()
        var added = 0
        var updated = 0
        for (cloud in cloudBooks) {
            val bookUrl = FanqieConstants.pageUrl(cloud.bookId)
            val existing = localBooks.firstOrNull { it.bookUrl == bookUrl }
                ?: findChangedSourceBook(localBooks, cloud, groupId)
            if (existing == null) {
                LogUtils.d(TAG, "add new bookId=${cloud.bookId} name=${cloud.name} readIdx=${cloud.readChapterIndex} readTs=${cloud.readTimestamp}")
                bookDao.insert(buildNewBook(cloud, bookUrl, groupId))
                added++
            } else {
                LogUtils.d(
                    TAG,
                    "update bookId=${cloud.bookId} name=${cloud.name} " +
                        "cloud readIdx=${cloud.readChapterIndex} readTs=${cloud.readTimestamp} " +
                        "local durIdx=${existing.durChapterIndex} durTime=${existing.durChapterTime} " +
                        "localBookUrl=${existing.bookUrl.take(60)}"
                )
                bookDao.update(buildUpdatedBook(existing, cloud, groupId, progressUpdateCallback))
                updated++
            }
        }

        var removed = 0
        if (cloudBooks.isNotEmpty()) {
            for (local in localBooks) {
                if (local.group and groupId == 0L) continue
                val bookId = FanqieConstants.parseBookId(local.bookUrl) ?: continue
                if (bookId !in cloudBookIds) {
                    bookDao.update(local.copy(group = local.group and groupId.inv()))
                    removed++
                }
            }
        }

        FanqieConfig.lastSyncTime = System.currentTimeMillis()
        return FanqieSyncResult(added, updated, removed, cloudBooks.size)
    }

    suspend fun addToLocalShelf(bookId: String): Boolean {
        val groupId = FanqieGroup.ensureGroup()
        val bookUrl = FanqieConstants.pageUrl(bookId)
        val book = bookDao.getBook(bookUrl) ?: findByBookId(bookId)
        if (book != null) {
            bookDao.update(book.copy(group = groupId))
            return true
        }
        val cloud = FanqieApi.fetchShelfBooks().firstOrNull { it.bookId == bookId }
            ?: return false
        bookDao.insert(buildNewBook(cloud, bookUrl, groupId))
        return true
    }

    private suspend fun findByBookId(bookId: String): Book? = bookDao.getAll().firstOrNull { local ->
        local.variableMap[FanqieConstants.BOOK_ID_VARIABLE] == bookId
    }

    private fun findChangedSourceBook(
        localBooks: List<Book>,
        cloud: FanqieBook,
        groupId: Long,
    ): Book? = localBooks.firstOrNull { local ->
        local.group and groupId != 0L &&
            FanqieConstants.parseBookId(local.bookUrl) == null &&
            (local.variableMap[FanqieConstants.BOOK_ID_VARIABLE] == cloud.bookId ||
                (local.name == cloud.name &&
                    (local.author.isBlank() || cloud.author.isBlank() || local.author == cloud.author)))
    }

    suspend fun removeFromLocalShelf(bookId: String) {
        val groupId = FanqieGroup.ensureGroup()
        val bookUrl = FanqieConstants.pageUrl(bookId)
        val book = bookDao.getBook(bookUrl) ?: return
        bookDao.update(book.copy(group = book.group and groupId.inv()))
    }

    suspend fun removeFromCloudShelf(bookId: String) {
        FanqieApi.removeFromCloudShelf(bookId)
        removeFromLocalShelf(bookId)
    }

    private fun buildNewBook(cloud: FanqieBook, bookUrl: String, groupId: Long): Book {
        val readChapterIndex = cloud.readChapterIndex.coerceAtLeast(0)
        val book = Book(
            bookUrl = bookUrl,
            origin = FanqieConstants.BASE_URL,
            originName = FanqieConstants.ORIGIN_NAME,
            name = cloud.name,
            author = cloud.author,
            coverUrl = cloud.coverUrl,
            intro = cloud.intro.ifBlank { null },
            type = BookType.text,
            group = groupId,
            latestChapterTitle = cloud.latestChapterTitle,
            latestChapterTime = if (cloud.latestChapterTime > 0) cloud.latestChapterTime else System.currentTimeMillis(),
            lastCheckTime = System.currentTimeMillis(),
            totalChapterNum = cloud.totalChapterNum,
            durChapterTitle = cloud.readChapterTitle.ifBlank { cloud.latestChapterTitle },
            durChapterIndex = readChapterIndex,
            durChapterTime = cloud.readTimestamp * 1000L,
            wordCount = cloud.wordCount,
            canUpdate = true,
        )
        book.putVariable(FanqieConstants.BOOK_ID_VARIABLE, cloud.bookId)
        return book
    }

    private fun buildUpdatedBook(existing: Book, cloud: FanqieBook, groupId: Long, progressUpdateCallback: ((bookUrl: String, chapterIndex: Int, chapterTime: Long) -> Unit)? = null): Book {
        val name = cloud.name.ifBlank { existing.name }
        val author = cloud.author.ifBlank { existing.author }
        val base = existing.copy(
            origin = if (existing.origin == BookType.localTag) FanqieConstants.BASE_URL else existing.origin,
            originName = if (existing.originName.isBlank()) FanqieConstants.ORIGIN_NAME else existing.originName,
            name = name,
            author = author,
            coverUrl = cloud.coverUrl ?: existing.coverUrl,
            intro = cloud.intro.ifBlank { existing.intro },
            group = groupId,
            latestChapterTitle = cloud.latestChapterTitle ?: existing.latestChapterTitle,
            latestChapterTime = if (cloud.latestChapterTime > 0) cloud.latestChapterTime else existing.latestChapterTime,
            totalChapterNum = if (cloud.totalChapterNum > 0) cloud.totalChapterNum else existing.totalChapterNum,
            wordCount = cloud.wordCount ?: existing.wordCount,
        )
        base.putVariable(FanqieConstants.BOOK_ID_VARIABLE, cloud.bookId)
        val cloudIdx = cloud.readChapterIndex.coerceAtLeast(0)
        val cloudReadMs = cloud.readTimestamp * 1000L
        val cloudAhead = cloudIdx > existing.durChapterIndex ||
            (cloudIdx == existing.durChapterIndex && cloudReadMs > 0 && cloudReadMs > existing.durChapterTime)
        LogUtils.d(TAG, "buildUpdatedBook: cloudAhead=$cloudAhead bookId=${cloud.bookId} localIdx=${existing.durChapterIndex} cloudIdx=$cloudIdx localTime=${existing.durChapterTime} cloudTime=$cloudReadMs")
        return if (cloudAhead) {
            val updated = base.copy(
                durChapterTitle = cloud.readChapterTitle.ifBlank { base.durChapterTitle },
                durChapterIndex = cloudIdx,
                durChapterTime = if (cloudReadMs > 0) cloudReadMs else existing.durChapterTime,
            )
            progressUpdateCallback?.invoke(existing.bookUrl, cloudIdx, cloudReadMs)
            updated
        } else {
            base
        }
    }
}
