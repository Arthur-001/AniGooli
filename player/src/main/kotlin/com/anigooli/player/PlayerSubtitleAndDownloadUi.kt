package com.anigooli.player

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100
private const val LINE_HEIGHT_MULTIPLIER = 1.28f
private const val MIN_FONT_SIZE_SP = 12f
private const val MAX_FONT_SIZE_SP = 36f
private const val MIN_WIDTH_FRACTION = 0.4f
private const val MAX_WIDTH_FRACTION = 1.0f
private const val SHADOW_BLUR_RADIUS = 5f
private const val SHADOW_OFFSET_Y = 2f
private const val PANEL_WIDTH_DP = 320
private const val CONTROLS_BOTTOM_CLEARANCE_DP = 88
private const val DEFAULT_EDGE_PADDING_DP = 20

private val SUBTITLE_BG_COLOR_SWATCHES = listOf(
    0xFF000000L, // Black
    0xFF263238L, // Charcoal
    0xFF1A237EL, // Navy
    0xFF1B5E20L, // Forest Green
    0xFF7F0000L, // Maroon
    0xFF4A148CL, // Purple
    0xFF006064L, // Teal
    0xFFE65100L, // Amber/Orange
    0xFFFFFFFFL, // White
)

/**
 * Renders active subtitle lines (or a live sample preview while [isPreviewMode] is true)
 * using the user's real-time [style] configuration.
 */
@Composable
fun SubtitleOverlay(
    lines: List<String>,
    style: SubtitleStyleConfig,
    showControls: Boolean,
    isPreviewMode: Boolean,
    modifier: Modifier = Modifier,
) {
    val displayLines = if (lines.isNotEmpty()) {
        lines
    } else if (isPreviewMode) {
        listOf("Subtitle preview — live appearance", "Adjust size, position, width & background")
    } else {
        emptyList()
    }
    if (displayLines.isEmpty()) return

    val verticalBias = (1f - 2f * style.verticalPosition).coerceIn(-1f, 1f)
    val fontFamily = when (SubtitleFontOption.fromId(style.fontFamilyId)) {
        SubtitleFontOption.SANS_SERIF -> FontFamily.SansSerif
        SubtitleFontOption.SERIF -> FontFamily.Serif
        SubtitleFontOption.MONOSPACE -> FontFamily.Monospace
        SubtitleFontOption.CURSIVE -> FontFamily.Cursive
    }
    val bgColor = if (style.backgroundEnabled) {
        Color(style.backgroundColorArgb).copy(alpha = style.backgroundOpacity.coerceIn(0f, 1f))
    } else {
        Color.Transparent
    }
    val needsShadow = !style.backgroundEnabled || style.backgroundOpacity < 0.35f
    val textStyle = TextStyle(
        color = Color.White,
        fontSize = style.fontSizeSp.sp,
        lineHeight = (style.fontSizeSp * LINE_HEIGHT_MULTIPLIER).sp,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Medium,
        textAlign = TextAlign.Center,
        shadow = if (needsShadow) {
            Shadow(
                color = Color.Black,
                offset = Offset(1f, SHADOW_OFFSET_Y),
                blurRadius = SHADOW_BLUR_RADIUS,
            )
        } else {
            null
        },
    )

    val bottomPadding = if (showControls && !isPreviewMode) {
        CONTROLS_BOTTOM_CLEARANCE_DP.dp
    } else {
        DEFAULT_EDGE_PADDING_DP.dp
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .padding(
                start = 16.dp,
                end = 16.dp,
                top = DEFAULT_EDGE_PADDING_DP.dp,
                bottom = bottomPadding,
            ),
    ) {
        val maxBoxWidth = maxWidth * style.widthFraction.coerceIn(MIN_WIDTH_FRACTION, MAX_WIDTH_FRACTION)
        Box(
            modifier = Modifier
                .align(BiasAlignment(horizontalBias = 0f, verticalBias = verticalBias))
                .widthIn(max = maxBoxWidth),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = bgColor,
            ) {
                Text(
                    text = displayLines.joinToString("\n"),
                    style = textStyle,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/**
 * Floating, non-dimming side panel for real-time subtitle customization so the user can
 * watch the subtitle update live on the video canvas as they adjust every option.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubtitleStylePanel(
    style: SubtitleStyleConfig,
    onFontFamilyChange: (String) -> Unit,
    onFontSizeChange: (Float) -> Unit,
    onVerticalPositionChange: (Float) -> Unit,
    onWidthFractionChange: (Float) -> Unit,
    onBackgroundEnabledChange: (Boolean) -> Unit,
    onBackgroundColorChange: (Long) -> Unit,
    onBackgroundOpacityChange: (Float) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        Surface(
            color = Color.Black.copy(alpha = 0.84f),
            shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(PANEL_WIDTH_DP.dp)
                .fillMaxHeight(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Subtitle Style",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = onReset) {
                            Text("Reset")
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                        }
                    }
                }

                HorizontalDivider(color = Color.White.copy(alpha = 0.14f))

                // Font family
                Text("Font", style = MaterialTheme.typography.labelLarge, color = Color.White)
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    SubtitleFontOption.entries.forEach { option ->
                        FilterChip(
                            selected = style.fontFamilyId == option.id,
                            onClick = { onFontFamilyChange(option.id) },
                            label = { Text(option.label) },
                        )
                    }
                }

                // Font size
                SliderSettingRow(
                    label = "Font Size",
                    valueText = "${style.fontSizeSp.roundToInt()} sp",
                    value = style.fontSizeSp,
                    valueRange = MIN_FONT_SIZE_SP..MAX_FONT_SIZE_SP,
                    onValueChange = onFontSizeChange,
                )

                // Location (Vertical Position)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    SliderSettingRow(
                        label = "Location",
                        valueText = "${(style.verticalPosition * PERCENT_SCALE).roundToInt()}%",
                        value = style.verticalPosition,
                        valueRange = 0f..1f,
                        onValueChange = onVerticalPositionChange,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        LocationPresetChip("Bottom", 0.05f, style.verticalPosition, onVerticalPositionChange)
                        LocationPresetChip("Middle", 0.50f, style.verticalPosition, onVerticalPositionChange)
                        LocationPresetChip("Top", 0.95f, style.verticalPosition, onVerticalPositionChange)
                    }
                }

                // Width
                SliderSettingRow(
                    label = "Subtitle Width",
                    valueText = "${(style.widthFraction * PERCENT_SCALE).roundToInt()}%",
                    value = style.widthFraction,
                    valueRange = MIN_WIDTH_FRACTION..MAX_WIDTH_FRACTION,
                    onValueChange = onWidthFractionChange,
                )

                HorizontalDivider(color = Color.White.copy(alpha = 0.14f))

                // Background toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Subtitle Background",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    Switch(
                        checked = style.backgroundEnabled,
                        onCheckedChange = onBackgroundEnabledChange,
                    )
                }

                if (style.backgroundEnabled) {
                    // Background Opacity
                    SliderSettingRow(
                        label = "Background Opacity",
                        valueText = "${(style.backgroundOpacity * PERCENT_SCALE).roundToInt()}%",
                        value = style.backgroundOpacity,
                        valueRange = 0f..1f,
                        onValueChange = onBackgroundOpacityChange,
                    )

                    // Background Color
                    Text(
                        text = "Background Color",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(vertical = 4.dp),
                    ) {
                        SUBTITLE_BG_COLOR_SWATCHES.forEach { argb ->
                            val swatchColor = Color(argb)
                            val isSelected = style.backgroundColorArgb == argb
                            val checkTint = if (swatchColor.luminance() > 0.5f) Color.Black else Color.White
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .border(
                                        width = if (isSelected) 2.5.dp else 1.dp,
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            Color.White.copy(alpha = 0.4f)
                                        },
                                        shape = CircleShape,
                                    )
                                    .clickable { onBackgroundColorChange(argb) },
                            ) {
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = "Selected",
                                        tint = checkTint,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationPresetChip(
    label: String,
    targetPosition: Float,
    currentPosition: Float,
    onSelect: (Float) -> Unit,
) {
    val isSelected = kotlin.math.abs(currentPosition - targetPosition) < 0.06f
    FilterChip(
        selected = isSelected,
        onClick = { onSelect(targetPosition) },
        label = { Text(label) },
    )
}

@Composable
private fun SliderSettingRow(
    label: String,
    valueText: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = Color.White)
            Text(valueText, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValueChange,
            valueRange = valueRange,
        )
    }
}

/**
 * Bottom sheet offering three download options: Video & Subtitle, Video Only, or Subtitle Only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadOptionsSheet(
    hasSubtitle: Boolean,
    onDownloadBoth: () -> Unit,
    onDownloadVideoOnly: () -> Unit,
    onDownloadSubtitleOnly: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
        ) {
            Text(
                text = "Download Options",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            DownloadOptionRow(
                title = "Video & Subtitle",
                subtitle = if (hasSubtitle) {
                    "Download episode video together with its subtitle file"
                } else {
                    "Download episode video (no external subtitle available)"
                },
                enabled = true,
                onClick = onDownloadBoth,
            )
            DownloadOptionRow(
                title = "Video Only",
                subtitle = "Download only the episode video without subtitle",
                enabled = true,
                onClick = onDownloadVideoOnly,
            )
            DownloadOptionRow(
                title = "Subtitle Only",
                subtitle = if (hasSubtitle) {
                    "Save subtitle file to Downloads/AniGooli"
                } else {
                    "No external subtitle available for this stream"
                },
                enabled = hasSubtitle,
                onClick = onDownloadSubtitleOnly,
            )
        }
    }
}

@Composable
private fun DownloadOptionRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = if (enabled) 2.dp else 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                },
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                },
            )
        }
    }
}
