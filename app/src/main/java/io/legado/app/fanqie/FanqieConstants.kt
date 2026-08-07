package io.legado.app.fanqie

object FanqieConstants {

    const val DOMAIN = "fanqienovel.com"
    const val BASE_URL = "https://$DOMAIN"
    const val ORIGIN_NAME = "番茄小说"

    const val GROUP_NAME = "番茄小说"

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; Pixel 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    const val AID = 1967
    const val VERSION_CODE = 57700

    const val SHELF_URL = "$BASE_URL/reading/bookapi/bookshelf/info/v:version/"
    const val MULTIDETAIL_URL = "$BASE_URL/api/bookshelf/multidetail"
    const val PROGRESS_URL = "$BASE_URL/api/reader/book/progress"
    const val UPDATE_PROGRESS_URL = "$BASE_URL/api/reader/book/update_progress"

    const val BOOK_ID_VARIABLE = "fanqieBookId"

    private val BOOK_ID_REGEX = Regex("""^https://fanqienovel\.com/page/(\d+).*""")

    fun directoryUrl(bookId: String) = "$BASE_URL/api/reader/directory/detail?bookId=$bookId"

    fun pageUrl(bookId: String) = "$BASE_URL/page/$bookId"

    fun parseBookId(bookUrl: String?): String? {
        if (bookUrl.isNullOrBlank()) return null
        return BOOK_ID_REGEX.matchEntire(bookUrl.trim())?.groupValues?.get(1)
    }

    fun isFanqieBook(bookUrl: String?): Boolean = parseBookId(bookUrl) != null
}
