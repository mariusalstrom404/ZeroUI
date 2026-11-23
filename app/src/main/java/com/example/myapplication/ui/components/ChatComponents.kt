package com.example.myapplication.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.myapplication.Message
import com.example.myapplication.MessageKind
import com.example.myapplication.R
import com.example.myapplication.ui.theme.HandoverAmberContainerDark
import com.example.myapplication.ui.theme.HandoverAmberContainerLight
import com.example.myapplication.ui.theme.HandoverAmberOnContainerDark
import com.example.myapplication.ui.theme.HandoverAmberOnContainerLight
import com.example.myapplication.ui.theme.WarningAmber
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMATTER = SimpleDateFormat("HH:mm", Locale.getDefault())

private fun formatTime(timestamp: Long): String = synchronized(TIME_FORMATTER) {
    TIME_FORMATTER.format(Date(timestamp))
}

/**
 * Renders one chat message with semantic visual hierarchy:
 * - User bubbles align right with tap/long-press to quickly re-run or copy.
 * - Intermediate [MessageKind.PROGRESS] updates appear as compact muted inline pills.
 * - [MessageKind.CONFIRM], [MessageKind.HANDOVER], and [MessageKind.ERROR] get distinct accent
 *   cards with clear icons so safety-critical notices stand out immediately.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatBubble(
    message: Message,
    showConfirmationButtons: Boolean,
    onConfirmChoice: (String) -> Unit,
    onCopy: (String) -> Unit,
    onRerun: (String) -> Unit
) {
    if (!message.isUser && message.kind == MessageKind.PROGRESS) {
        ProgressInlinePill(text = message.text)
        return
    }

    val isUser = message.isUser
    val dark = isSystemInDarkTheme()
    var showActions by remember(message.timestamp, message.text) { mutableStateOf(false) }

    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp)
    } else {
        RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    }

    val (bgColor, textColor, borderStroke, badgeInfo) = when {
        isUser -> Quad(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.onPrimary,
            null,
            null
        )
        message.kind == MessageKind.ERROR -> Quad(
            MaterialTheme.colorScheme.errorContainer,
            MaterialTheme.colorScheme.onErrorContainer,
            BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
            BadgeInfo(Icons.Rounded.ErrorOutline, stringResource(R.string.badge_error_title), MaterialTheme.colorScheme.error)
        )
        message.kind == MessageKind.CONFIRM -> Quad(
            if (dark) HandoverAmberContainerDark else HandoverAmberContainerLight,
            if (dark) HandoverAmberOnContainerDark else HandoverAmberOnContainerLight,
            BorderStroke(1.2.dp, WarningAmber.copy(alpha = 0.65f)),
            BadgeInfo(Icons.Rounded.WarningAmber, stringResource(R.string.badge_confirm_title), WarningAmber)
        )
        message.kind == MessageKind.HANDOVER -> Quad(
            if (dark) HandoverAmberContainerDark else HandoverAmberContainerLight,
            if (dark) HandoverAmberOnContainerDark else HandoverAmberOnContainerLight,
            BorderStroke(1.dp, WarningAmber.copy(alpha = 0.45f)),
            BadgeInfo(Icons.Rounded.Shield, stringResource(R.string.badge_handover_title), WarningAmber)
        )
        else -> Quad(
            MaterialTheme.colorScheme.surfaceVariant,
            MaterialTheme.colorScheme.onSurfaceVariant,
            BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
            null
        )
    }

    val bubbleMaxWidth = (LocalConfiguration.current.screenWidthDp * 0.80f).dp
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
            Surface(
                color = bgColor,
                shape = bubbleShape,
                border = borderStroke,
                tonalElevation = if (isUser) 2.dp else 0.dp,
                modifier = Modifier
                    .widthIn(max = bubbleMaxWidth)
                    .clip(bubbleShape)
                    .combinedClickable(
                        onClick = { showActions = !showActions },
                        onLongClick = { onCopy(message.text) }
                    )
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    if (badgeInfo != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.padding(bottom = 6.dp)
                        ) {
                            Icon(
                                imageVector = badgeInfo.icon,
                                contentDescription = null,
                                tint = badgeInfo.tint,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = badgeInfo.label,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = badgeInfo.tint
                            )
                        }
                    }

                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )

                    if (showConfirmationButtons && message.kind == MessageKind.CONFIRM) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { onConfirmChoice(message.choices.firstOrNull() ?: "Confirm") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            ) {
                                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(stringResource(R.string.action_confirm), style = MaterialTheme.typography.labelLarge)
                            }
                            OutlinedButton(
                                onClick = { onConfirmChoice(message.choices.getOrNull(1) ?: "Cancel") },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(14.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                                border = BorderStroke(1.dp, textColor.copy(alpha = 0.4f))
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = null, tint = textColor, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    stringResource(R.string.action_cancel),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = textColor
                                )
                            }
                        }
                    }
                }
            }

            AnimatedVisibility(visible = showActions) {
                Row(
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = formatTime(message.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                    MiniActionPill(
                        icon = Icons.Rounded.ContentCopy,
                        label = stringResource(R.string.cd_copy),
                        onClick = {
                            onCopy(message.text)
                            showActions = false
                        }
                    )
                    if (isUser) {
                        MiniActionPill(
                            icon = Icons.Rounded.PlayArrow,
                            label = stringResource(R.string.cd_rerun),
                            onClick = {
                                onRerun(message.text)
                                showActions = false
                            }
                        )
                    }
                }
            }
        }
}

@Composable
private fun ProgressInlinePill(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f)
        )
    }
}

@Composable
private fun MiniActionPill(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Wrapping choice chips using [FlowRow] so multi-option replies and long suggestions are never
 * clipped horizontally off the right edge.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChips(choices: List<String>, onChoice: (String) -> Unit) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        choices.forEach { choice ->
            SuggestionChip(
                onClick = { onChoice(choice) },
                label = {
                    Text(
                        choice,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                },
                shape = RoundedCornerShape(50),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                )
            )
        }
    }
}

/** Live speech transcript bubble with an animated audio waveform reacting to [voiceLevelFlow]. */
@Composable
fun HeardBubble(partialText: String, voiceLevelFlow: StateFlow<Float>) {
    val bubbleMaxWidth = (LocalConfiguration.current.screenWidthDp * 0.78f).dp
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 6.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
            modifier = Modifier.widthIn(max = bubbleMaxWidth)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                VoiceWaveform(
                    levelFlow = voiceLevelFlow,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = partialText.ifBlank { stringResource(R.string.heard_listening) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = if (partialText.isBlank()) FontStyle.Italic else FontStyle.Normal,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/** Five-bar animated audio equalizer driven by live speech loudness [levelFlow] (0f..1f). */
@Composable
fun VoiceWaveform(levelFlow: StateFlow<Float>, color: Color, modifier: Modifier = Modifier) {
    val level by levelFlow.collectAsState()
    val infinite = rememberInfiniteTransition(label = "wave-idle")
    val phase by infinite.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wave-phase"
    )
    val multipliers = listOf(0.5f, 0.85f, 1.0f, 0.78f, 0.48f)
    Row(
        modifier = modifier.height(20.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        multipliers.forEachIndexed { idx, weight ->
            val idlePulse = if (idx % 2 == 0) phase else (1f - phase)
            val targetFraction = (0.24f + (level * 0.76f * weight) + (idlePulse * 0.14f)).coerceIn(0.2f, 1f)
            val animated by animateFloatAsState(
                targetValue = targetFraction,
                animationSpec = spring(stiffness = 400f),
                label = "bar-$idx"
            )
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height((20f * animated).dp)
                    .clip(RoundedCornerShape(50))
                    .background(color)
            )
        }
    }
}

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 13.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val yOffset by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = -6f,
                        animationSpec = infiniteRepeatable(
                            tween(400, delayMillis = index * 130, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse
                        ),
                        label = "dot$index"
                    )
                    Box(
                        Modifier
                            .offset(y = yOffset.dp)
                            .size(7.dp)
                            .background(
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.7f),
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}

private data class BadgeInfo(val icon: ImageVector, val label: String, val tint: Color)

private data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
