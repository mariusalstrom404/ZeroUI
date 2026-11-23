package com.example.myapplication.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.R
import com.example.myapplication.automation.AppMemory
import com.example.myapplication.history.OperationRecord
import com.example.myapplication.ipc.CommandBridge
import com.example.myapplication.nlp.OpenAiLlmClient
import com.example.myapplication.ui.UserPreferences
import com.example.myapplication.ui.theme.ListeningRed
import com.example.myapplication.ui.theme.SuccessGreen
import com.example.myapplication.ui.theme.WarningAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HISTORY_TIME_FMT = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault())

fun hasMicPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

fun openSettingsSafely(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, R.string.err_open_settings, Toast.LENGTH_SHORT).show()
    }
}

// ── Setup Card & Guided Accessibility Dialog ──────────────────────────────────

@Composable
fun SetupCard(
    resumes: Int,
    onOpenAccessibilityGuide: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var micTick by remember { mutableIntStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micTick++ }

    val mic = remember(resumes, micTick) { hasMicPermission(context) }
    val service = remember(resumes) { CommandBridge.isServiceConnected }
    if (mic && service) return

    val readyCount = (if (mic) 1 else 0) + (if (service) 1 else 0)

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.25f))
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    stringResource(R.string.setup_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f)
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.secondary.copy(alpha = 0.16f)
                ) {
                    Text(
                        stringResource(R.string.setup_progress, readyCount, 2),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            SetupRow(
                title = stringResource(R.string.setup_mic_label),
                subtitle = stringResource(R.string.setup_mic_desc),
                done = mic
            ) {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            SetupRow(
                title = stringResource(R.string.setup_a11y_label),
                subtitle = stringResource(R.string.setup_a11y_desc),
                done = service,
                onFix = onOpenAccessibilityGuide
            )
        }
    }
}

@Composable
private fun SetupRow(
    title: String,
    subtitle: String,
    done: Boolean,
    onFix: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (done) Icons.Rounded.CheckCircle else Icons.Outlined.Circle,
            contentDescription = if (done) stringResource(R.string.status_enabled) else stringResource(R.string.status_missing),
            tint = if (done) SuccessGreen else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.45f),
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.72f)
            )
        }
        if (!done) {
            TextButton(onClick = onFix, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(stringResource(R.string.action_turn_on))
            }
        }
    }
}

@Composable
fun AccessibilityGuideDialog(
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Rounded.AccessibilityNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
        },
        title = {
            Text(stringResource(R.string.a11y_guide_title), style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.a11y_guide_step1), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.a11y_guide_step2), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(R.string.a11y_guide_step3), style = MaterialTheme.typography.bodyMedium)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        stringResource(R.string.a11y_guide_restricted),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp)
                    )
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Rounded.Shield,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            stringResource(R.string.a11y_guide_privacy),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onDismiss()
                onOpenSettings()
            }) {
                Text(stringResource(R.string.a11y_guide_open_btn))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

// ── Operation History & Shortcuts Bottom Sheet ────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistorySheet(
    records: List<OperationRecord>,
    pinnedCommands: List<String>,
    onDismiss: () -> Unit,
    onRunCommand: (String) -> Unit,
    onTogglePin: (String) -> Unit,
    onClearHistory: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(R.string.history_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (records.isNotEmpty()) {
                    TextButton(onClick = onClearHistory) {
                        Text(stringResource(R.string.history_clear))
                    }
                }
            }

            if (pinnedCommands.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    stringResource(R.string.section_pinned),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    pinnedCommands.forEach { cmd ->
                        Surface(
                            onClick = {
                                onDismiss()
                                onRunCommand(cmd)
                            },
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.3f))
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(cmd, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }

            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.history_recent),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))

            if (records.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            stringResource(R.string.history_empty_title),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            stringResource(R.string.history_empty_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(records, key = { it.id }) { rec ->
                        val runnableCmd = rec.utterance.ifBlank { rec.description }
                        val pinned = pinnedCommands.any { it.equals(runnableCmd, ignoreCase = true) }
                        HistoryRecordCard(
                            record = rec,
                            pinned = pinned,
                            onRun = {
                                onDismiss()
                                onRunCommand(runnableCmd)
                            },
                            onTogglePin = { onTogglePin(runnableCmd) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryRecordCard(
    record: OperationRecord,
    pinned: Boolean,
    onRun: () -> Unit,
    onTogglePin: () -> Unit
) {
    val (statusLabel, statusColor) = when (record.status.uppercase()) {
        "SUCCESS" -> stringResource(R.string.history_status_success) to SuccessGreen
        "FAILED" -> stringResource(R.string.history_status_failed) to ListeningRed
        "CANCELLED" -> stringResource(R.string.history_status_cancelled) to MaterialTheme.colorScheme.onSurfaceVariant
        "PAUSED" -> stringResource(R.string.history_status_paused) to WarningAmber
        "NEEDS_INPUT" -> stringResource(R.string.history_status_needs_input) to WarningAmber
        else -> record.status to MaterialTheme.colorScheme.primary
    }
    val formattedTime = synchronized(HISTORY_TIME_FMT) { HISTORY_TIME_FMT.format(Date(record.timestamp)) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
        border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = statusColor.copy(alpha = 0.16f)
                    ) {
                        Text(
                            statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                        )
                    }
                    Text(
                        formattedTime,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    record.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (record.utterance.isNotBlank()) {
                    Text(
                        "“${record.utterance}”",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = onTogglePin) {
                Icon(
                    Icons.Rounded.PushPin,
                    contentDescription = if (pinned) stringResource(R.string.cd_unpin) else stringResource(R.string.cd_pin),
                    tint = if (pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(19.dp)
                )
            }
            IconButton(onClick = onRun) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(R.string.cd_rerun),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ── Settings & Permissions Bottom Sheet ───────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsSheet(
    resumes: Int,
    onDismiss: () -> Unit,
    onClearChat: () -> Unit,
    onOpenAccessibilityGuide: () -> Unit,
    onOverlayPrefChanged: (resetPosition: Boolean) -> Unit
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var micTick by remember { mutableIntStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micTick++ }

    val mic = remember(resumes, micTick) { hasMicPermission(context) }
    val service = remember(resumes) { CommandBridge.isServiceConnected }
    val writeSettings = remember(resumes) { Settings.System.canWrite(context) }

    val ttsMuted by UserPreferences.ttsMuted.collectAsState()
    val speechRate by UserPreferences.speechRate.collectAsState()
    val speechLocale by UserPreferences.speechLocaleTag.collectAsState()
    val floatingVisible by UserPreferences.floatingVisible.collectAsState()
    val floatingAutoDim by UserPreferences.floatingAutoDim.collectAsState()
    val appMemoryEnabled by UserPreferences.appMemoryEnabled.collectAsState()
    val learnedAppCount by AppMemory.learnedAppCount.collectAsState()

    val apiConfigured = remember { OpenAiLlmClient.isConfigured() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(stringResource(R.string.settings_title), style = MaterialTheme.typography.titleLarge)

            // 1. Permissions Dashboard
            SettingsSection(title = stringResource(R.string.settings_section_permissions)) {
                PermissionStatusRow(
                    title = stringResource(R.string.setup_mic_label),
                    subtitle = stringResource(R.string.setup_mic_desc),
                    granted = mic
                ) { micLauncher.launch(Manifest.permission.RECORD_AUDIO) }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                PermissionStatusRow(
                    title = stringResource(R.string.setup_a11y_label),
                    subtitle = stringResource(R.string.setup_a11y_desc),
                    granted = service,
                    onGrant = onOpenAccessibilityGuide
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                PermissionStatusRow(
                    title = stringResource(R.string.setup_write_label),
                    subtitle = stringResource(R.string.setup_write_desc),
                    granted = writeSettings
                ) {
                    openSettingsSafely(
                        context,
                        Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                    )
                }
            }

            // 2. Voice & Speech
            SettingsSection(title = stringResource(R.string.settings_section_voice)) {
                ToggleRow(
                    title = stringResource(R.string.settings_mute_tts),
                    subtitle = stringResource(R.string.settings_mute_tts_desc),
                    checked = ttsMuted,
                    onCheckedChange = { UserPreferences.setTtsMuted(it) }
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column(modifier = Modifier.padding(vertical = 8.dp)) {
                    Text(stringResource(R.string.settings_speech_rate), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0.85f to "0.85×", 1.0f to "1.0×", 1.15f to "1.15×", 1.3f to "1.3×").forEach { (rate, label) ->
                            FilterChip(
                                selected = kotlin.math.abs(speechRate - rate) < 0.05f,
                                onClick = { UserPreferences.setSpeechRate(rate) },
                                label = { Text(label) }
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Column(modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_speech_lang), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            UserPreferences.LOCALE_AUTO to stringResource(R.string.lang_auto),
                            UserPreferences.LOCALE_EN_US to stringResource(R.string.lang_en),
                            UserPreferences.LOCALE_ZH_TW to stringResource(R.string.lang_zh_tw)
                        ).forEach { (tag, label) ->
                            FilterChip(
                                selected = speechLocale == tag,
                                onClick = { UserPreferences.setSpeechLocaleTag(tag) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            // 3. Floating Mic Controls
            SettingsSection(title = stringResource(R.string.settings_section_overlay)) {
                ToggleRow(
                    title = stringResource(R.string.settings_show_floating),
                    subtitle = stringResource(R.string.settings_show_floating_desc),
                    checked = floatingVisible,
                    onCheckedChange = {
                        UserPreferences.setFloatingVisible(it)
                        onOverlayPrefChanged(false)
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                ToggleRow(
                    title = stringResource(R.string.settings_auto_dim),
                    subtitle = stringResource(R.string.settings_auto_dim_desc),
                    checked = floatingAutoDim,
                    onCheckedChange = {
                        UserPreferences.setFloatingAutoDim(it)
                        onOverlayPrefChanged(false)
                    }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                TextButton(
                    onClick = { onOverlayPrefChanged(true) },
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_reset_pos))
                }
            }

            // 4. Conversation & Privacy
            SettingsSection(title = stringResource(R.string.settings_section_conversation)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        stringResource(R.string.settings_llm_status),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = (if (apiConfigured) SuccessGreen else ListeningRed).copy(alpha = 0.14f)
                    ) {
                        Text(
                            if (apiConfigured) stringResource(R.string.settings_llm_configured)
                            else stringResource(R.string.settings_llm_missing),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (apiConfigured) SuccessGreen else ListeningRed,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                ToggleRow(
                    title = stringResource(R.string.settings_app_memory),
                    subtitle = stringResource(R.string.settings_app_memory_desc),
                    checked = appMemoryEnabled,
                    onCheckedChange = { UserPreferences.setAppMemoryEnabled(it) }
                )

                val clearedToast = stringResource(R.string.settings_memory_cleared)
                OutlinedButton(
                    onClick = {
                        AppMemory.clear()
                        Toast.makeText(context, clearedToast, Toast.LENGTH_SHORT).show()
                    },
                    enabled = learnedAppCount > 0,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_clear_memory, learnedAppCount))
                }

                Spacer(Modifier.height(6.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

                Row(
                    modifier = Modifier.padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Rounded.Security,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        stringResource(R.string.settings_privacy_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = {
                        onClearChat()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.settings_clear_chat))
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary
        )
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            border = BorderStroke(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                content()
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(
    title: String,
    subtitle: String,
    granted: Boolean,
    onGrant: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (granted) SuccessGreen else WarningAmber)
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (granted) {
            Text(
                stringResource(R.string.status_enabled),
                style = MaterialTheme.typography.labelSmall,
                color = SuccessGreen
            )
        } else {
            TextButton(onClick = onGrant, contentPadding = PaddingValues(horizontal = 10.dp)) {
                Text(stringResource(R.string.action_turn_on))
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
