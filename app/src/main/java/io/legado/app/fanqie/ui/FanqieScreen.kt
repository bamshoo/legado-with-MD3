package io.legado.app.fanqie.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SyncProblem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import io.legado.app.fanqie.FanqieConstants
import io.legado.app.ui.browser.WebViewActivity
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.card.NormalCard
import io.legado.app.ui.widget.components.progressIndicator.AppCircularProgressIndicator
import io.legado.app.ui.widget.components.text.AppText
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarActionButton
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.toastOnUi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun FanqieScreen(
    state: FanqieUiState,
    onIntent: (FanqieIntent) -> Unit,
    effects: Flow<FanqieEffect>,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val loginLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onIntent(FanqieIntent.LoginCompleted)
    }

    LaunchedEffect(Unit) {
        effects.collectLatest { effect ->
            when (effect) {
                is FanqieEffect.OpenLogin -> {
                    loginLauncher.launch(
                        Intent(context, WebViewActivity::class.java).apply {
                            putExtra("title", "番茄小说登录")
                            putExtra("url", effect.url)
                            putExtra("sourceOrigin", FanqieConstants.BASE_URL)
                            putExtra("sourceName", FanqieConstants.ORIGIN_NAME)
                        }
                    )
                }

                is FanqieEffect.ShowToast -> context.toastOnUi(effect.message)
            }
        }
    }

    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()

    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = "番茄小说",
                navigationIcon = { TopBarNavigationButton(onClick = onBack) },
                scrollBehavior = scrollBehavior,
                actions = {
                    if (state.loggedIn) {
                        TopBarActionButton(
                            onClick = { onIntent(FanqieIntent.SyncNow) },
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "同步书架",
                        )
                    }
                },
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .padding(contentPadding)
                .fillMaxSize()
        ) {
            if (state.loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    AppCircularProgressIndicator()
                }
            } else if (!state.loggedIn) {
                NotLoggedInContent(onLogin = { onIntent(FanqieIntent.Login) })
            } else {
                BookListContent(
                    state = state,
                    onIntent = onIntent,
                )
            }
        }
    }

    val pending = state.pendingRemove
    if (pending != null) {
        AppAlertDialog(
            show = true,
            title = "从番茄云书架移除",
            text = "确定将「${pending.name}」从番茄云书架移除吗？\n本地书架将同步移出该分组。",
            confirmText = "移除",
            onConfirm = { onIntent(FanqieIntent.ConfirmRemoveFromCloud) },
            onDismissRequest = { onIntent(FanqieIntent.DismissRemoveConfirm) },
        )
    }
}

@Composable
private fun NotLoggedInContent(onLogin: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(modifier = Modifier.height(48.dp))
        Icon(
            imageVector = Icons.Default.SyncProblem,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = io.legado.app.ui.theme.LegadoTheme.colorScheme.primary,
        )
        AppText(
            text = "尚未登录番茄小说",
            fontWeight = FontWeight.Bold,
            fontSize = 17.sp,
        )
        AppText(
            text = "登录后可同步云书架、阅读进度自动上报",
            color = io.legado.app.ui.theme.LegadoTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp,
        )
        NormalCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clickable(onClick = onLogin),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Login,
                    contentDescription = null,
                    tint = io.legado.app.ui.theme.LegadoTheme.colorScheme.primary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "前往登录",
                    color = io.legado.app.ui.theme.LegadoTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun BookListContent(
    state: FanqieUiState,
    onIntent: (FanqieIntent) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "settings") {
            FanqieSettingCard(state = state, onIntent = onIntent)
        }
        if (state.error != null) {
            item(key = "error") {
                AppText(
                    text = "错误：${state.error}",
                    color = io.legado.app.ui.theme.LegadoTheme.colorScheme.error,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        items(state.books, key = { it.bookId }) { book ->
            FanqieBookRow(book = book, onIntent = onIntent)
        }
    }
}

@Composable
private fun FanqieSettingCard(
    state: FanqieUiState,
    onIntent: (FanqieIntent) -> Unit,
) {
    NormalCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AppText(text = "阅读进度自动上报", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                AppText(
                    text = "最近同步：${formatTime(state.lastSyncTime)}",
                    color = io.legado.app.ui.theme.LegadoTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = state.autoSyncProgress,
                onCheckedChange = { onIntent(FanqieIntent.ToggleAutoSyncProgress) },
            )
        }
    }
}

@Composable
private fun FanqieBookRow(
    book: FanqieBookUi,
    onIntent: (FanqieIntent) -> Unit,
) {
    NormalCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(
                model = book.coverUrl,
                contentDescription = book.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(width = 54.dp, height = 72.dp)
                    .clip(RoundedCornerShape(6.dp)),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp),
            ) {
                AppText(
                    text = book.name,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                AppText(
                    text = book.author.ifBlank { "未知作者" },
                    color = io.legado.app.ui.theme.LegadoTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.height(2.dp))
                AppText(
                    text = progressText(book),
                    color = io.legado.app.ui.theme.LegadoTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (book.inLocalShelf) {
                TopBarActionButton(
                    onClick = { onIntent(FanqieIntent.RemoveFromShelf(book.bookId)) },
                    imageVector = io.legado.app.ui.widget.components.icon.AppIcons.Delete,
                    contentDescription = "移出书架",
                )
            } else {
                TopBarActionButton(
                    onClick = { onIntent(FanqieIntent.AddToShelf(book.bookId)) },
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "加入书架",
                )
            }
        }
    }
}

private fun progressText(book: FanqieBookUi): String {
    val readIndex = book.readChapterIndex + 1
    val chapter = if (book.totalChapterNum > 0) {
        "$readIndex/${book.totalChapterNum}"
    } else {
        "$readIndex 章"
    }
    return "读到第 $chapter 章" + if (book.inLocalShelf) " · 已入架" else " · 未入架"
}

private fun formatTime(time: Long): String {
    if (time <= 0L) return "从未"
    return SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(time))
}
