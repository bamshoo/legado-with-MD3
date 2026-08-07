package io.legado.app.fanqie.ui

import androidx.compose.runtime.Stable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class FanqieBookUi(
    val bookId: String,
    val name: String,
    val author: String,
    val coverUrl: String?,
    val totalChapterNum: Int,
    val readChapterIndex: Int,
    val inLocalShelf: Boolean,
)

@Stable
data class FanqieUiState(
    val loading: Boolean = true,
    val loggedIn: Boolean = false,
    val autoSyncProgress: Boolean = true,
    val lastSyncTime: Long = 0L,
    val books: ImmutableList<FanqieBookUi> = persistentListOf(),
    val error: String? = null,
    val pendingRemove: FanqieBookUi? = null,
)

sealed interface FanqieIntent {
    data object Load : FanqieIntent
    data object SyncNow : FanqieIntent
    data object Login : FanqieIntent
    data object LoginCompleted : FanqieIntent
    data object ToggleAutoSyncProgress : FanqieIntent
    data class AddToShelf(val bookId: String) : FanqieIntent
    data class RemoveFromShelf(val bookId: String) : FanqieIntent
    data class ShowRemoveConfirm(val bookId: String) : FanqieIntent
    data object DismissRemoveConfirm : FanqieIntent
    data object ConfirmRemoveFromCloud : FanqieIntent
}

sealed interface FanqieEffect {
    data class OpenLogin(val url: String) : FanqieEffect
    data class ShowToast(val message: String) : FanqieEffect
}
