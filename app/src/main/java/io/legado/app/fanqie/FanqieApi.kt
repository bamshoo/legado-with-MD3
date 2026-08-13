package io.legado.app.fanqie

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.legado.app.help.http.CookieManager.cookieJarHeader
import io.legado.app.help.http.CookieStore
import io.legado.app.help.http.okHttpClient
import io.legado.app.utils.LogUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

data class FanqieBook(
    val bookId: String,
    val name: String,
    val author: String,
    val intro: String,
    val coverUrl: String?,
    val wordCount: String?,
    val latestChapterTitle: String?,
    val latestChapterTime: Long,
    val totalChapterNum: Int,
    val readChapterIndex: Int,
    val readChapterTitle: String,
    val readItemId: String,
    val readTimestamp: Long,
)

data class FanqieChapter(
    val itemId: String,
    val title: String,
)

data class FanqieSyncResult(
    val added: Int,
    val updated: Int,
    val removed: Int,
    val total: Int,
)

open class FanqieException(message: String, val code: Int = -1) : Exception(message)

class FanqieAuthException(message: String) : FanqieException(message)

enum class FanqieLoginState {
    LoggedOut, LoggedIn, Expired
}

object FanqieApi {

    private const val TAG = "FanqieApi"
    private const val JSON_MEDIA = "application/json;charset=UTF-8"
    private val AUTH_CODES = setOf(-2012, -2041)
    private val AUTHOR_REGEX = Regex(""""author"\s*:\s*"([^"]*)"""")
    private val authorCache = ConcurrentHashMap<String, String>()

    private val _loginState = MutableStateFlow(FanqieLoginState.LoggedOut)
    val loginState: StateFlow<FanqieLoginState> = _loginState.asStateFlow()

    fun refreshLoginState() {
        _loginState.value = if (hasCookie()) FanqieLoginState.LoggedIn else FanqieLoginState.LoggedOut
    }

    fun hasCookie(): Boolean =
        CookieStore.getCookie(FanqieConstants.BASE_URL).isNotBlank()

    private fun csrfToken(): String {
        val cookie = CookieStore.getCookie(FanqieConstants.BASE_URL)
        for (part in cookie.split(";")) {
            val kv = part.trim().split("=", limit = 2)
            if (kv.size == 2 && kv[0].trim() == "passport_csrf_token") {
                return kv[1].trim()
            }
        }
        return ""
    }

    fun isLoggedIn(): Boolean = hasCookie()

    private fun shelfParams() = "?aid=${FanqieConstants.AID}&iid=0" +
        "&version_code=${FanqieConstants.VERSION_CODE}" +
        "&update_version_code=${FanqieConstants.VERSION_CODE}"

    private fun baseBuilder(url: String, method: String): Request.Builder {
        val builder = Request.Builder()
            .url(url)
            .header(cookieJarHeader, "1")
            .header("User-Agent", FanqieConstants.USER_AGENT)
            .header("Referer", "${FanqieConstants.BASE_URL}/")
        if (method == "GET") {
            builder.get()
        }
        return builder
    }

    private fun Request.Builder.postJson(body: String): Request.Builder {
        val csrf = csrfToken()
        return this
            .header("Content-Type", JSON_MEDIA)
            .header("Origin", FanqieConstants.BASE_URL)
            .header("x-secsdk-csrf-token", csrf)
            .post(body.toRequestBody(JSON_MEDIA.toMediaType()))
    }

    private suspend fun requestJson(builder: Request.Builder): JsonObject = withContext(Dispatchers.IO) {
        okHttpClient.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw FanqieException("HTTP ${response.code}")
            }
            val root = try {
                JsonParser.parseString(body).asJsonObject
            } catch (e: Exception) {
                throw FanqieException("响应解析失败: ${body.take(200)}")
            }
            checkResult(root)
        }
    }

    private fun checkResult(root: JsonObject): JsonObject {
        val code = root.get("code")?.asInt ?: return root
        if (code == 0) {
            _loginState.value = FanqieLoginState.LoggedIn
            return root
        }
        if (code in AUTH_CODES) {
            _loginState.value =
                if (hasCookie()) FanqieLoginState.Expired else FanqieLoginState.LoggedOut
            throw FanqieAuthException(root.get("message")?.asString ?: "登录已失效($code)")
        }
        throw FanqieException(root.get("message")?.asString ?: "请求失败($code)", code)
    }

    private suspend fun getJson(url: String): JsonObject {
        return requestJson(baseBuilder(url, "GET"))
    }

    private suspend fun postJson(url: String, body: String): JsonObject {
        return requestJson(baseBuilder(url, "POST").postJson(body))
    }

    suspend fun fetchShelfBooks(): List<FanqieBook> {
        val shelf = getJson(FanqieConstants.SHELF_URL + shelfParams())
        val data = shelf.get("data")?.asJsonObject ?: return emptyList()
        val shelfItems = data.get("book_shelf_info")?.asJsonArray ?: return emptyList()

        val progressMap = fetchProgressMap()
        val detailMap = fetchDetailMap(shelfItems, progressMap)
        val bookIds = shelfItems.mapNotNull { element ->
            element.asJsonObject.get("book_id")?.asString
        }.distinct()
        val directoryMap = coroutineScope {
            bookIds.map { bookId ->
                async {
                    bookId to try {
                        fetchDirectory(bookId)
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }.awaitAll().toMap()
        }

        val result = mutableListOf<FanqieBook>()
        val seenBookIds = HashSet<String>()
        for (element in shelfItems) {
            val item = element.asJsonObject
            val bookId = item.get("book_id")?.asString ?: continue
            if (!seenBookIds.add(bookId)) continue
            val progress = progressMap[bookId]
            val detail = detailMap[bookId]
            val chapters = directoryMap[bookId] ?: emptyList()
            val readItemId = progress?.readItemId.orEmpty()
            val readIndex = if (readItemId.isNotBlank()) {
                chapters.indexOfFirst { it.itemId == readItemId }
                    .takeIf { it >= 0 } ?: (progress?.readChapterIndex ?: 0)
            } else {
                progress?.readChapterIndex ?: 0
            }
            val latestTitle = chapters.lastOrNull()?.title
                ?: detail?.get("last_chapter_title")?.asString
                ?: detail?.get("item_show_title")?.asString
            val latestTime = detail?.get("last_chapter_update_time")?.asLong ?: 0L
            result.add(
                FanqieBook(
                    bookId = bookId,
                    name = detail?.get("book_name")?.asString
                        ?: item.get("book_name")?.asString ?: "",
                    author = detail?.get("author")?.asString
                        ?: item.get("author")?.asString ?: "",
                    intro = detail?.get("abstract")?.asString
                        ?: item.get("abstract")?.asString ?: "",
                    coverUrl = detail?.get("thumb_url")?.asString
                        ?: item.get("thumb_url")?.asString,
                    wordCount = detail?.get("word_number")?.asString
                        ?: item.get("word_number")?.asString,
                    latestChapterTitle = latestTitle,
                    latestChapterTime = latestTime,
                    totalChapterNum = detail?.get("serial_count")?.asInt
                        ?: item.get("serial_count")?.asInt ?: 0,
                    readChapterIndex = readIndex,
                    readChapterTitle = detail?.get("item_show_title")?.asString.orEmpty(),
                    readItemId = readItemId,
                    readTimestamp = progress?.readTimestamp ?: 0L,
                )
            )
        }
        fillMissingAuthors(result)
        LogUtils.d(TAG, "fetchShelfBooks: parsed ${result.size} books")
        return result
    }

    private suspend fun fetchBookAuthor(bookId: String): String? = withContext(Dispatchers.IO) {
        try {
            okHttpClient.newCall(baseBuilder(FanqieConstants.pageUrl(bookId), "GET").build())
                .execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string().orEmpty()
                    val author = AUTHOR_REGEX.find(body)?.groupValues?.get(1) ?: return@withContext null
                    if (author.isBlank()) return@withContext null
                    runCatching {
                        JsonParser.parseString("{\"a\":$author}").asJsonObject.get("a").asString
                    }.getOrNull() ?: author
                }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun fillMissingAuthors(result: MutableList<FanqieBook>) {
        val missing = result.filter { it.author.isBlank() }
        if (missing.isEmpty()) return
        val authors = coroutineScope {
            missing.map { book ->
                async {
                    val cached = authorCache[book.bookId]
                    if (cached != null) return@async book.bookId to cached
                    val author = fetchBookAuthor(book.bookId)
                    if (!author.isNullOrBlank()) authorCache[book.bookId] = author
                    book.bookId to author
                }
            }.awaitAll().toMap()
        }
        for (i in result.indices) {
            val author = authors[result[i].bookId]
            if (!author.isNullOrBlank()) {
                result[i] = result[i].copy(author = author)
            }
        }
    }

    private suspend fun fetchProgressMap(): Map<String, FanqieBookProgress> {
        return try {
            val root = getJson(FanqieConstants.PROGRESS_URL)
            parseProgress(root)
        } catch (e: Exception) {
            emptyMap()
        }
    }

    internal suspend fun fetchProgressForBook(bookId: String): FanqieBookProgress? {
        return try {
            val root = getJson(FanqieConstants.PROGRESS_URL)
            parseProgress(root)[bookId]
        } catch (e: Exception) {
            null
        }
    }

    private fun parseProgress(root: JsonObject): Map<String, FanqieBookProgress> {
        val result = mutableMapOf<String, FanqieBookProgress>()
        val array = root.get("data")?.asJsonArray ?: return result
        for (element in array) {
            val item = element.asJsonObject
            val bookId = item.get("book_id")?.asString ?: continue
            result[bookId] = FanqieBookProgress(
                readChapterIndex = item.get("index")?.asInt ?: 0,
                readItemId = item.get("item_id")?.asString.orEmpty(),
                readTimestamp = item.get("read_timestamp")?.asLong ?: 0L,
            )
        }
        return result
    }

    private suspend fun fetchDetailMap(
        shelfItems: JsonArray,
        progressMap: Map<String, FanqieBookProgress>,
    ): Map<String, JsonObject> {
        val books = JsonArray()
        for (element in shelfItems) {
            val item = element.asJsonObject
            val bookId = item.get("book_id")?.asString ?: continue
            val book = JsonObject()
            book.addProperty("book_id", bookId)
            book.addProperty("item_id", progressMap[bookId]?.readItemId ?: "0")
            books.add(book)
        }
        if (books.isEmpty()) return emptyMap()
        val payload = JsonObject()
        payload.add("books", books)
        val result = mutableMapOf<String, JsonObject>()
        return try {
            val root = postJson(FanqieConstants.MULTIDETAIL_URL, payload.toString())
            val data = root.get("data")?.asJsonObject
            if (data == null) {
                LogUtils.e(TAG, "multidetail: no data, code=${root.get("code")}, keys=${root.keySet()}")
                return emptyMap()
            }
            LogUtils.d(TAG, "multidetail: code=${root.get("code")}, data keys=${data.keySet()}")
            val detailList = data.get("detail_list")?.asJsonArray
            if (detailList == null) {
                LogUtils.e(TAG, "multidetail: no detail_list in data")
                return emptyMap()
            }
            for (element in detailList) {
                val detail = element.asJsonObject
                val bookId = detail.get("book_id")?.asString ?: continue
                result[bookId] = detail
            }
            LogUtils.d(
                TAG,
                "multidetail: parsed ${result.size} details, " +
                    "sample keys=${detailList.firstOrNull()?.asJsonObject?.keySet()}",
            )
            result
        } catch (e: Exception) {
            LogUtils.e(TAG, "multidetail failed: ${e.message}\n${e.stackTraceToString()}")
            result
        }
    }

    suspend fun fetchDirectory(bookId: String): List<FanqieChapter> {
        val root = getJson(FanqieConstants.directoryUrl(bookId))
        val data = root.get("data")?.asJsonObject ?: return emptyList()
        val volumes = data.get("chapterListWithVolume")?.asJsonArray ?: return emptyList()
        val chapters = mutableListOf<FanqieChapter>()
        for (volume in volumes) {
            for (element in volume.asJsonArray) {
                val chapter = element.asJsonObject
                val itemId = chapter.get("itemId")?.asString ?: continue
                if (itemId.isBlank()) continue
                chapters.add(
                    FanqieChapter(
                        itemId = itemId,
                        title = chapter.get("title")?.asString.orEmpty(),
                    )
                )
            }
        }
        return chapters
    }

    suspend fun updateProgress(bookId: String, itemId: String, index: Int, readProgress: Float) {
        LogUtils.d(TAG, "updateProgress: bookId=$bookId itemId=$itemId index=$index fraction=$readProgress")
        val payload = JsonObject()
        payload.addProperty("book_id", bookId)
        payload.addProperty("item_id", itemId)
        payload.addProperty("read_progress", readProgress)
        payload.addProperty("index", index)
        payload.addProperty("read_timestamp", (System.currentTimeMillis() / 1000).toString())
        payload.addProperty("genre_type", 0)
        postJson(FanqieConstants.UPDATE_PROGRESS_URL, payload.toString())
    }

    suspend fun removeFromCloudShelf(bookId: String) {
        val payload = JsonObject()
        val identify = JsonArray()
        val entry = JsonObject()
        entry.addProperty("book_id", bookId)
        entry.addProperty("book_type", 0)
        entry.addProperty("remove_type", 1)
        entry.addProperty("modify_time", System.currentTimeMillis())
        identify.add(entry)
        payload.add("identify_data", identify)
        postJson(
            FanqieConstants.BASE_URL + "/reading/bookapi/bookshelf/delete/v:version/" + shelfParams(),
            payload.toString(),
        )
    }

    internal data class FanqieBookProgress(
        val readChapterIndex: Int,
        val readItemId: String,
        val readTimestamp: Long,
    )
}
