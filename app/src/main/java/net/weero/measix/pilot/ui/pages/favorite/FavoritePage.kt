package net.weero.measix.pilot.ui.pages.favorite

import net.weero.measix.pilot.utils.plus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Delete01
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SwipeToDismissBoxDefaults
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import net.weero.measix.pilot.R
import net.weero.measix.pilot.ui.components.nav.BackButton
import net.weero.measix.pilot.ui.context.LocalNavController
import net.weero.measix.pilot.ui.theme.CustomColors
import net.weero.measix.pilot.service.NodeFavoriteItem
import net.weero.measix.pilot.utils.userVisibleDiagnostic
import net.weero.measix.pilot.utils.toLocalDateTime
import org.koin.androidx.compose.koinViewModel
import java.time.Instant

@Composable
fun FavoritePage(vm: FavoriteVM = koinViewModel()) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    val navController = LocalNavController.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val favorites = vm.nodeFavorites.collectAsStateWithLifecycle().value
    val favoriteRemovedText = stringResource(R.string.favorite_page_removed)
    val undoText = stringResource(R.string.history_page_undo)
    val retryText = stringResource(R.string.application_recovery_retry)

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                navigationIcon = {
                    BackButton()
                },
                title = {
                    Text(stringResource(R.string.favorite_page_title))
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        snackbarHost = {
            SnackbarHost(hostState = snackbarHostState) { data ->
                SelectionContainer { Snackbar(data) }
            }
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        if (favorites.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(R.string.favorite_page_no_favorites),
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f),
                )
            }
            return@Scaffold
        }

        LazyColumn(
            contentPadding = innerPadding + PaddingValues(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(favorites, key = { it.id }) { item ->
                SwipeableFavoriteCard(
                    item = item,
                    onClick = {
                        scope.launch {
                            try {
                                val request = vm.openRequest(item)
                                navController.clearAndNavigate(net.weero.measix.pilot.Screen.Chat(request, nodeId = item.nodeId.toString()))
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                android.util.Log.e("FavoritePage", "Favorite navigation failed", error)
                                snackbarHostState.showSnackbar(error.userVisibleDiagnostic(), withDismissAction = true)
                            }
                        }
                    },
                    onDelete = { reset ->
                        // The page owns removal and undo; item disposal or reset cannot cancel either.
                        scope.launch {
                            val restoreToken = try {
                                try {
                                    vm.removeForUndo(item)
                                } finally {
                                    reset()
                                }
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (error: Exception) {
                                android.util.Log.e("FavoritePage", "Favorite removal failed", error)
                                snackbarHostState.showSnackbar(error.userVisibleDiagnostic(), withDismissAction = true)
                                return@launch
                            } ?: return@launch
                            if (snackbarHostState.showSnackbar(
                                message = favoriteRemovedText,
                                actionLabel = undoText,
                                withDismissAction = true,
                            ) != SnackbarResult.ActionPerformed) return@launch
                            while (true) {
                                try {
                                    vm.restoreFavorite(restoreToken)
                                    break
                                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                    throw cancelled
                                } catch (error: Exception) {
                                    android.util.Log.e("FavoritePage", "Favorite restore failed", error)
                                    if (snackbarHostState.showSnackbar(
                                        message = error.userVisibleDiagnostic(),
                                        actionLabel = retryText,
                                        withDismissAction = true,
                                    ) != SnackbarResult.ActionPerformed) break
                                }
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .animateItem(),
                )
            }
        }
    }
}

@Composable
private fun SwipeableFavoriteCard(
    item: NodeFavoriteItem,
    onClick: () -> Unit,
    onDelete: (suspend () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val positionalThreshold = SwipeToDismissBoxDefaults.positionalThreshold
    // Undo reuses the favorite ID, but must not restore the removed card's saved swipe offset.
    val dismissState = remember(item.id) {
        SwipeToDismissBoxState(SwipeToDismissBoxValue.Settled, positionalThreshold)
    }

    LaunchedEffect(dismissState.settledValue) {
        when (dismissState.settledValue) {
            SwipeToDismissBoxValue.EndToStart -> {
                onDelete { dismissState.reset() }
            }

            else -> {}
        }
    }

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = HugeIcons.Delete01,
                    contentDescription = stringResource(R.string.assistant_page_remove),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        },
        enableDismissFromStartToEnd = false,
        modifier = modifier.testTag("favorite-${item.id}"),
    ) {
        FavoriteCard(
            item = item,
            onClick = onClick,
        )
    }
}

@Composable
private fun FavoriteCard(
    item: NodeFavoriteItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CustomColors.cardColorsOnSurfaceContainer,
    ) {
        SelectionContainer {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = item.conversationTitle.ifBlank { stringResource(R.string.favorite_page_untitled_conversation) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
                val dateText = Instant.ofEpochMilli(item.createdAt).toLocalDateTime()
                Text(
                    text = item.preview,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = dateText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}
