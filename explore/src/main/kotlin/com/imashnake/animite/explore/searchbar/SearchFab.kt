package com.imashnake.animite.explore.searchbar

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import com.imashnake.animite.explore.R

/**
 * A Floating Action Button-esque collapsible search bar. When collapsed, it displays a search icon.
 * Expanding it reveals a text field with minimize and clear buttons, preserving entered text across
 * minimize/expand cycles until explicitly cleared.
 *
 * @param isExpanded Whether the search FAB is currently "expanded".
 * @param setExpanded Called when [isExpanded] should be updated.
 * @param query Current preserved search query string.
 * @param onQueryChange Called when the user edits the query text.
 * @param onMinimize Called when the bar should collapse without clearing [query].
 * @param onClear Called when the user explicitly clears [query] via the cross button.
 * @param modifier [Modifier].
 */
@Composable
fun SearchFab(
    isExpanded: Boolean,
    setExpanded: (expanded: Boolean) -> Unit,
    query: String,
    onQueryChange: (String) -> Unit,
    onMinimize: () -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier,
        shape = CircleShape
    ) {
        AnimatedContent(targetState = isExpanded, label = "expand_search_fab") { targetExpanded ->
            if (targetExpanded) {
                ExpandedSearchBarContent(
                    query = query,
                    collapse = onMinimize,
                    clearText = onClear,
                    searchText = onQueryChange,
                )
            } else {
                CollapsedSearchBarContent(
                    expand = { setExpanded(true) },
                    modifier = Modifier.padding(dimensionResource(R.dimen.search_bar_icon_padding))
                )
            }
        }
    }
}

@Composable
internal fun CollapsedSearchBarContent(
    expand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Icon(
        imageVector = ImageVector.vectorResource(R.drawable.search),
        contentDescription = stringResource(R.string.quick_search),
        modifier = Modifier
            .clickable(onClick = expand)
            .then(modifier)
    )
}

@Composable
internal fun ExpandedSearchBarContent(
    query: String,
    collapse: () -> Unit,
    clearText: () -> Unit,
    searchText: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    var textFieldValue by remember {
        mutableStateOf(TextFieldValue(text = query, selection = TextRange(query.length)))
    }
    LaunchedEffect(query) {
        if (textFieldValue.text != query) {
            textFieldValue = TextFieldValue(text = query, selection = TextRange(query.length))
        }
    }

    TextField(
        value = textFieldValue,
        onValueChange = { updated ->
            textFieldValue = updated
            searchText(updated.text)
        },
        modifier = modifier.focusRequester(focusRequester),
        textStyle = MaterialTheme.typography.labelLarge,
        placeholder = {
            Text(
                text = stringResource(R.string.quick_search),
                style = MaterialTheme.typography.labelLarge
            )
        },
        singleLine = true,
        colors = searchTextFieldColors(),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(
            onSearch = {
                keyboardController?.hide()
                collapse()
            }
        ),
        leadingIcon = {
            IconButton(
                onClick = {
                    keyboardController?.hide()
                    collapse()
                },
                modifier = Modifier.size(dimensionResource(R.dimen.search_icon_size))
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                    contentDescription = "Minimize search bar"
                )
            }
        },
        trailingIcon = {
            IconButton(
                onClick = {
                    if (textFieldValue.text.isEmpty()) {
                        keyboardController?.hide()
                        collapse()
                    } else {
                        textFieldValue = TextFieldValue("")
                        clearText()
                    }
                },
                modifier = Modifier.size(dimensionResource(R.dimen.search_icon_size))
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Clear search text"
                )
            }
        }
    )

    BackHandler {
        keyboardController?.hide()
        collapse()
    }
}

@Composable
fun searchTextFieldColors(
    contentColor: Color = LocalContentColor.current
): TextFieldColors {
    return TextFieldDefaults.colors(
        unfocusedTextColor = contentColor,
        focusedTextColor = contentColor,
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        disabledContainerColor = Color.Transparent,
        cursorColor = contentColor,
        selectionColors = TextSelectionColors(
            handleColor = contentColor,
            backgroundColor = contentColor.copy(alpha = 0.3f)
        ),
        focusedIndicatorColor = Color.Transparent,
        unfocusedIndicatorColor = Color.Transparent,
        focusedLeadingIconColor = contentColor,
        unfocusedLeadingIconColor = contentColor,
        focusedTrailingIconColor = contentColor,
        unfocusedTrailingIconColor = contentColor,
        unfocusedPlaceholderColor = contentColor.copy(alpha = 0.5f),
        focusedPlaceholderColor = contentColor.copy(alpha = 0.5f),
    )
}
