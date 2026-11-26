package com.imashnake.animite.explore.searchbar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeNestedScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.imashnake.animite.api.anilist.type.MediaType
import com.imashnake.animite.core.resource.Resource
import com.imashnake.animite.core.ui.LocalPaddings
import com.imashnake.animite.core.ui.ext.copy
import com.imashnake.animite.explore.MediaMediumList
import com.imashnake.animite.explore.R
import kotlinx.collections.immutable.persistentListOf
import com.imashnake.animite.navigation.R as navigationR

/**
 * Search bar along with a Front Drop list.
 *
 * @param hasExtraPadding if the search bar should have extra bottom padding to accommodate the
 * [com.imashnake.animite.navigation.NavigationBar].
 * @param onItemClick called when media with an ID, [MediaType], and optional [SearchFallbackMeta] is clicked.
 * @param modifier the [Modifier] to be applied to this Front Drop.
 * @param viewModel [SearchViewModel] instance.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchFrontDrop(
    hasExtraPadding: Boolean,
    isFabVisible: Boolean,
    onItemClick: (Int, MediaType, SearchFallbackMeta?) -> Unit,
    modifier: Modifier = Modifier,
    contentWindowInsets: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
    viewModel: SearchViewModel = hiltViewModel()
) {
    val insetPaddingValues = contentWindowInsets.asPaddingValues()
    val insetPaddingValuesForSearchBar = contentWindowInsets
        .exclude(WindowInsets.navigationBars)
        .asPaddingValues()

    val searchMediaType = MediaType.ANIME
    LaunchedEffect(searchMediaType) {
        viewModel.setMediaType(searchMediaType)
    }
    val searchList by viewModel.searchList.collectAsState()
    val draftQuery by viewModel.draftQuery.collectAsState()
    val submittedQuery by viewModel.submittedQuery.collectAsState()
    val cardSize by viewModel.cardSize.collectAsState()
    val keyboardController = LocalSoftwareKeyboardController.current

    var isOverlayOpen by rememberSaveable { mutableStateOf(false) }
    var isExpanded by rememberSaveable { mutableStateOf(false) }
    val showOverlay = isFabVisible && isOverlayOpen

    BackHandler(enabled = showOverlay && !isExpanded) {
        isOverlayOpen = false
    }

    val searchBarBottomPadding by animateDpAsState(
        targetValue = if (hasExtraPadding) {
            dimensionResource(navigationR.dimen.navigation_bar_height)
        } else 0.dp,
        label = "translate_search_bar"
    )

    if (showOverlay) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background.copy(alpha = 0.97f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                )
        ) {
            val results = searchList.data ?: persistentListOf()
            val isLoading = searchList is Resource.Loading

            if (results.isNotEmpty()) {
                MediaMediumList(
                    mediaMediumList = results,
                    onItemClick = { id ->
                        keyboardController?.hide()
                        isExpanded = false
                        isOverlayOpen = false
                        onItemClick(id, searchMediaType, viewModel.getFallbackMeta(id))
                    },
                    shouldShowRank = false,
                    cardSize = cardSize,
                    modifier = Modifier
                        .fillMaxSize()
                        .imeNestedScroll()
                        .consumeWindowInsets(
                            PaddingValues(
                                bottom = searchBarBottomPadding + insetPaddingValues.calculateBottomPadding()
                            )
                        ).imePadding(),
                    contentPadding = PaddingValues(
                        LocalPaddings.current.large
                    ) + PaddingValues(
                        bottom = LocalPaddings.current.large +
                            dimensionResource(R.dimen.search_bar_height) +
                            searchBarBottomPadding
                    ) + insetPaddingValues,
                )
            } else if (!isLoading && !submittedQuery.isNullOrBlank()) {
                Text(
                    text = "No results found for \"$submittedQuery\"",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }

            if (isLoading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = insetPaddingValues.calculateTopPadding()),
                )
            }
        }
    }

    AnimatedVisibility(
        visible = isFabVisible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier
            .padding(insetPaddingValuesForSearchBar.copy(bottom = 0.dp))
            .padding(bottom = searchBarBottomPadding)
            .navigationBarsPadding()
            .consumeWindowInsets(PaddingValues(bottom = searchBarBottomPadding))
            .imePadding()
            .height(dimensionResource(R.dimen.search_bar_height))
    ) {
        SearchFab(
            isExpanded = isExpanded,
            onExpand = {
                isOverlayOpen = true
                isExpanded = true
            },
            query = draftQuery,
            onQueryChange = viewModel::updateDraftQuery,
            onSubmit = {
                viewModel.submitSearch()
                isExpanded = false
                isOverlayOpen = true
            },
            onMinimize = {
                isExpanded = false
                if (submittedQuery.isNullOrBlank()) {
                    isOverlayOpen = false
                }
            },
            onClear = {
                viewModel.clearSearch()
            },
        )
    }
}
