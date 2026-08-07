package io.legado.app.fanqie.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.data.appDb
import io.legado.app.fanqie.FanqieApi
import io.legado.app.fanqie.FanqieAuthException
import io.legado.app.fanqie.FanqieBook
import io.legado.app.fanqie.FanqieConfig
import io.legado.app.fanqie.FanqieConstants
import io.legado.app.fanqie.FanqieGroup
import io.legado.app.fanqie.FanqieShelfRepository
import io.legado.app.help.http.CookieStore
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class FanqieViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(FanqieUiState())
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<FanqieEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        load()
    }

    fun onIntent(intent: FanqieIntent) {
        when (intent) {
            FanqieIntent.Load -> load()
            FanqieIntent.SyncNow -> syncNow()
            FanqieIntent.Login -> _effects.tryEmit(FanqieEffect.OpenLogin(FanqieConstants.BASE_URL))
            FanqieIntent.LoginCompleted -> onLoginCompleted()
            FanqieIntent.ToggleAutoSyncProgress -> toggleAutoSyncProgress()
            is FanqieIntent.AddToShelf -> addToShelf(intent.bookId)
            is FanqieIntent.RemoveFromShelf -> removeFromShelf(intent.bookId)
            is FanqieIntent.ShowRemoveConfirm -> showRemoveConfirm(intent.bookId)
            FanqieIntent.DismissRemoveConfirm -> _uiState.update { it.copy(pendingRemove = null) }
            FanqieIntent.ConfirmRemoveFromCloud -> confirmRemoveFromCloud()
        }
    }

    private fun load() {
        viewModelScope.launch {
            FanqieApi.refreshLoginState()
            _uiState.update {
                it.copy(loading = true, error = null)
            }
            if (!FanqieApi.isLoggedIn()) {
                _uiState.update {
                    it.copy(
                        loading = false,
                        loggedIn = false,
                        autoSyncProgress = FanqieConfig.autoSyncProgress,
                        lastSyncTime = FanqieConfig.lastSyncTime,
                        books = kotlinx.collections.immutable.persistentListOf(),
                    )
                }
                return@launch
            }
            runCatching {
                val groupId = FanqieGroup.ensureGroup()
                val cloudBooks = FanqieApi.fetchShelfBooks().distinctBy { it.bookId }
                FanqieShelfRepository.syncFromCloud(cloudBooks)
                val localBooks = appDb.bookDao.getAll()
                    .filter { it.group and groupId != 0L }
                val localUrls = localBooks.mapTo(HashSet()) { it.bookUrl }
                val localBookIds = localBooks
                    .mapNotNullTo(HashSet()) {
                        it.variableMap[FanqieConstants.BOOK_ID_VARIABLE]
                    }
                val localNames = localBooks
                    .filter { FanqieConstants.parseBookId(it.bookUrl) == null }
                    .mapTo(HashSet()) { it.name }
                cloudBooks.map { it.toUi(localUrls, localBookIds, localNames) }
            }.onSuccess { books ->
                _uiState.update {
                    it.copy(
                        loading = false,
                        loggedIn = true,
                        autoSyncProgress = FanqieConfig.autoSyncProgress,
                        lastSyncTime = FanqieConfig.lastSyncTime,
                        books = books.toImmutableList(),
                    )
                }
            }.onFailure { e ->
                Log.e("FanqieVM", "load/sync failed", e)
                val authFailed = e is FanqieAuthException
                _uiState.update {
                    it.copy(
                        loading = false,
                        loggedIn = !authFailed,
                        autoSyncProgress = FanqieConfig.autoSyncProgress,
                        lastSyncTime = FanqieConfig.lastSyncTime,
                        error = e.message,
                    )
                }
            }
        }
    }

    private fun syncNow() {
        viewModelScope.launch {
            _uiState.update { it.copy(error = null) }
            runCatching { FanqieShelfRepository.syncFromCloud() }
                .onSuccess { result ->
                    _effects.tryEmit(
                        FanqieEffect.ShowToast(
                            "同步完成：新增 ${result.added}，更新 ${result.updated}，移除 ${result.removed}"
                        )
                    )
                    load()
                }
                .onFailure { e ->
                    if (e is FanqieAuthException) {
                        _uiState.update { it.copy(loggedIn = false, error = e.message) }
                    } else {
                        _uiState.update { it.copy(error = e.message) }
                    }
                    _effects.tryEmit(FanqieEffect.ShowToast("同步失败：${e.message}"))
                }
        }
    }

    private fun onLoginCompleted() {
        viewModelScope.launch {
            val cookies = android.webkit.CookieManager.getInstance()
                .getCookie(FanqieConstants.BASE_URL)
            if (cookies.isNullOrBlank()) {
                _effects.tryEmit(FanqieEffect.ShowToast("未检测到登录信息，请重新登录"))
                return@launch
            }
            withContext(Dispatchers.IO) {
                CookieStore.setCookie(FanqieConstants.BASE_URL, cookies)
            }
            FanqieApi.refreshLoginState()
            _effects.tryEmit(FanqieEffect.ShowToast("登录成功"))
            load()
        }
    }

    private fun toggleAutoSyncProgress() {
        val newValue = !FanqieConfig.autoSyncProgress
        FanqieConfig.autoSyncProgress = newValue
        _uiState.update { it.copy(autoSyncProgress = newValue) }
    }

    private fun addToShelf(bookId: String) {
        viewModelScope.launch {
            runCatching { FanqieShelfRepository.addToLocalShelf(bookId) }
                .onSuccess { added ->
                    _effects.tryEmit(
                        FanqieEffect.ShowToast(
                            if (added) "已加入书架" else "入架失败：云端书架中不存在该书"
                        )
                    )
                    load()
                }
                .onFailure { e ->
                    _effects.tryEmit(FanqieEffect.ShowToast("入架失败：${e.message}"))
                }
        }
    }

    private fun removeFromShelf(bookId: String) {
        viewModelScope.launch {
            FanqieShelfRepository.removeFromLocalShelf(bookId)
            _effects.tryEmit(FanqieEffect.ShowToast("已移出书架分组"))
            load()
        }
    }

    private fun showRemoveConfirm(bookId: String) {
        val book = _uiState.value.books.firstOrNull { it.bookId == bookId }
        _uiState.update { it.copy(pendingRemove = book) }
    }

    private fun confirmRemoveFromCloud() {
        val book = _uiState.value.pendingRemove ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(pendingRemove = null) }
            runCatching { FanqieShelfRepository.removeFromCloudShelf(book.bookId) }
                .onSuccess {
                    _effects.tryEmit(FanqieEffect.ShowToast("已从番茄云书架移除"))
                    load()
                }
                .onFailure { e ->
                    _effects.tryEmit(FanqieEffect.ShowToast("移除失败：${e.message}"))
                }
        }
    }

    private fun FanqieBook.toUi(
        localUrls: Set<String>,
        localBookIds: Set<String>,
        localNames: Set<String>,
    ) = FanqieBookUi(
        bookId = bookId,
        name = name.ifBlank { "未命名" },
        author = author,
        coverUrl = coverUrl,
        totalChapterNum = totalChapterNum,
        readChapterIndex = readChapterIndex,
        inLocalShelf = FanqieConstants.pageUrl(bookId) in localUrls ||
            bookId in localBookIds ||
            name in localNames,
    )
}
