package io.legado.app.ui.book.read

import android.net.Uri

/**
 * [ReadBookViewModel] 的顶层纯函数。
 *
 * 这些函数不触碰 VM 状态与 [io.legado.app.model.ReadBook]，与阅读页各域无关，
 * 放在这里只为让 [ReadBookViewModel] 的行数棘轮（见 `ReadBookDomainSplitBoundaryTest`）
 * 只反映真正的域内逻辑。
 */
internal fun readStyleExportFileName(styleName: String): String {
    val safeName = styleName
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        .trim('.')
        .ifBlank { "readConfig" }
    return "$safeName.zip"
}

internal fun Int.coerceSearchResultIndex(resultSize: Int): Int {
    return if (resultSize <= 0) 0 else coerceIn(0, resultSize - 1)
}

internal fun String.isHttpTtsImportUri(): Boolean {
    val uri = runCatching { Uri.parse(this) }.getOrNull() ?: return false
    return uri.scheme in setOf("legado", "yuedu")
            && uri.host == "import"
            && uri.path.equals("/httpTTS", ignoreCase = true)
}
