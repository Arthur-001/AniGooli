package com.imashnake.animite.core.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Card size presets shared across Anime, Manga, Explore, and Settings. */
enum class MediaCardSize(
    /** Display label shown in Settings. */
    val label: String,
    /** Compact label shown in the per-page size stepper. */
    val shortLabel: String,
    /** Card width in horizontal Anime/Manga carousels. */
    val gridCardWidth: Dp,
    /** Cover image height in horizontal Anime/Manga carousels. */
    val gridImageHeight: Dp,
    /** Row height in vertical Explore/Search lists. */
    val listRowHeight: Dp,
    /** Cover thumbnail width in vertical Explore/Search lists. */
    val listCoverWidth: Dp,
) {
    COMPACT(
        label = "Compact",
        shortLabel = "S",
        gridCardWidth = 96.dp,
        gridImageHeight = 138.dp,
        listRowHeight = 92.dp,
        listCoverWidth = 64.dp,
    ),
    MEDIUM(
        label = "Medium",
        shortLabel = "M",
        gridCardWidth = 118.dp,
        gridImageHeight = 168.dp,
        listRowHeight = 114.dp,
        listCoverWidth = 80.dp,
    ),
    LARGE(
        label = "Large",
        shortLabel = "L",
        gridCardWidth = 140.dp,
        gridImageHeight = 200.dp,
        listRowHeight = 137.dp,
        listCoverWidth = 96.dp,
    );

    /** Returns the next smaller preset, clamping at [COMPACT]. */
    fun smaller(): MediaCardSize = when (this) {
        COMPACT -> COMPACT
        MEDIUM -> COMPACT
        LARGE -> MEDIUM
    }

    /** Returns the next larger preset, clamping at [LARGE]. */
    fun larger(): MediaCardSize = when (this) {
        COMPACT -> MEDIUM
        MEDIUM -> LARGE
        LARGE -> LARGE
    }

    companion object {
        /** Parses a persisted card size string safely, defaulting to [COMPACT]. */
        fun safeValueOf(raw: String?): MediaCardSize =
            entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } ?: COMPACT
    }
}

/**
 * Compact `- / size / +` pill allowing users to shrink or enlarge media cards directly on any page.
 *
 * @param currentSize Currently selected [MediaCardSize].
 * @param onSizeChange Callback invoked when the user taps `-` or `+`.
 * @param modifier [Modifier].
 */
@Composable
fun CardSizeStepper(
    currentSize: MediaCardSize,
    onSizeChange: (MediaCardSize) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        ) {
            val canShrink = currentSize != MediaCardSize.COMPACT
            val canGrow = currentSize != MediaCardSize.LARGE

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canShrink) { onSizeChange(currentSize.smaller()) },
            ) {
                Text(
                    text = "−",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (canShrink) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    },
                )
            }

            Text(
                text = currentSize.shortLabel,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp),
            )

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable(enabled = canGrow) { onSizeChange(currentSize.larger()) },
            ) {
                Text(
                    text = "+",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (canGrow) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    },
                )
            }
        }
    }
}
