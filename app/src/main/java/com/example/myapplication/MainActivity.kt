package com.example.myapplication

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.QuestionAnswer
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.history.OperationRecord
import com.example.myapplication.ipc.CommandBridge
import com.example.myapplication.speech.Speaker
import com.example.myapplication.speech.SpeechManager
import com.example.myapplication.ui.ProcessViewModels
import com.example.myapplication.ui.UserPreferences
import com.example.myapplication.ui.VoiceViewModel
import com.example.myapplication.ui.components.AccessibilityGuideDialog
import com.example.myapplication.ui.components.ChatBubble
import com.example.myapplication.ui.components.ChoiceChips
import com.example.myapplication.ui.components.HeardBubble
import com.example.myapplication.ui.components.HistorySheet
import com.example.myapplication.ui.components.SettingsSheet
import com.example.myapplication.ui.components.SetupCard
import com.example.myapplication.ui.components.TypingIndicator
import com.example.myapplication.ui.components.VoiceWaveform
import com.example.myapplication.ui.components.WelcomeSection
import com.example.myapplication.ui.components.hasMicPermission
import com.example.myapplication.ui.components.openSettingsSafely
import com.example.myapplication.ui.theme.ListeningRed
import com.example.myapplication.ui.theme.MyApplicationTheme
import com.example.myapplication.ui.theme.WarningAmber
import com.example.myapplication.ui.theme.WorkingViolet
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        /** Intent extra: start listening as soon as the dialog is shown. */
        const val EXTRA_START_VOICE = "start_voice"
        /** Times speech recognition is retried by itself when an automation's question got no usable answer. */
        private const val MAX_ANSWER_RETRIES = 1
    }

    // Process-wide, so the chat and a pending follow-up survive this dialog closing and reopening.
    private val viewModel: VoiceViewModel by lazy {
        ViewModelProvider(
            ProcessViewModels,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[VoiceViewModel::class.java]
    }
    private lateinit var speech: SpeechManager

    /** Bumped for each request to start listening, including ones arriving at an open dialog. */
    private var voiceRequests by mutableIntStateOf(0)

    /** Bumped on every resume, so setup & settings re-check permissions changed in Settings. */
    private var resumes by mutableIntStateOf(0)

    /** Inline speech error banner message (replaces ephemeral Toasts). */
    private var speechErrorMessage by mutableStateOf<String?>(null)

    /** Retries used up for the current automation question. */
    private var answerRetries = 0

    // The automation taps the app underneath, so this dialog must not stay on top of it.
    private val getOutOfTheWay: () -> Unit = { moveTaskToBack(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        UserPreferences.init(this)

        // Full-width transparent translucent window so our rounded assistant sheet can anchor cleanly at the bottom.
        window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
        window.setGravity(Gravity.BOTTOM)
        setFinishOnTouchOutside(false)

        speech = SpeechManager(this)
        speech.resultListener = {
            answerRetries = 0
            speechErrorMessage = null
            viewModel.onUserCommand(it)
        }
        speech.errorListener = { onSpeechError(it) }
        Speaker.init(this)
        viewModel.speak = { text, interrupt -> Speaker.speak(text, interrupt) }
        viewModel.onAnswered = getOutOfTheWay
        if (savedInstanceState == null && intent.getBooleanExtra(EXTRA_START_VOICE, false)) voiceRequests++

        // The floating button shows when the mic is open.
        lifecycleScope.launch { speech.isListening.collect { CommandBridge.reportListening(it) } }

        setContent {
            MyApplicationTheme {
                val isListening by speech.isListening.collectAsState()
                val partialText by speech.partialText.collectAsState()
                val ttsMuted by UserPreferences.ttsMuted.collectAsState()
                val pinnedCommands by UserPreferences.pinnedCommands.collectAsState()

                ZeroUIAssistantSheet(
                    messages = viewModel.messages,
                    operationHistory = viewModel.operationHistory,
                    pinnedCommands = pinnedCommands,
                    isListening = isListening,
                    partialText = partialText,
                    voiceLevelFlow = speech.level,
                    isProcessing = viewModel.isProcessing,
                    awaitingAnswer = viewModel.awaitingAnswer,
                    canStop = viewModel.isProcessing && !viewModel.awaitingAnswer,
                    ttsMuted = ttsMuted,
                    speechError = speechErrorMessage,
                    voiceRequests = voiceRequests,
                    resumes = resumes,
                    onStartListening = {
                        speechErrorMessage = null
                        speech.startListening()
                        viewModel.warmUp()
                    },
                    onStopListening = { speech.stopListening() },
                    onStop = { viewModel.stop() },
                    onSend = {
                        speechErrorMessage = null
                        viewModel.onUserCommand(it)
                    },
                    onToggleMute = {
                        val muted = UserPreferences.toggleTtsMuted()
                        if (muted) Speaker.stop()
                    },
                    onClearChat = { viewModel.clearHistory(announce = false) },
                    onClearOperationHistory = { viewModel.clearOperationHistory() },
                    onTogglePin = { viewModel.togglePinCommand(it) },
                    onDismissSpeechError = { speechErrorMessage = null },
                    onMinimize = getOutOfTheWay
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes++
        viewModel.refreshOperationHistory()
    }

    /** The dialog was already open (e.g. an automation asks a question): listen again without recreating it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_VOICE, false)) {
            answerRetries = 0
            speechErrorMessage = null
            voiceRequests++
        }
    }

    /**
     * An automation waiting for an answer gets one more try by itself when nothing was heard;
     * otherwise an inline error banner with Retry is shown inside the assistant sheet.
     */
    private fun onSpeechError(error: Int) {
        if (CommandBridge.isAwaitingAnswer && SpeechManager.heardNothing(error) && answerRetries < MAX_ANSWER_RETRIES) {
            answerRetries++
            lifecycleScope.launch {
                Speaker.speakAndWait("Sorry, I didn't catch that. Please say it again.")
                if (CommandBridge.isAwaitingAnswer) voiceRequests++
            }
            return
        }
        speechErrorMessage = SpeechManager.messageFor(error)
    }

    override fun onDestroy() {
        super.onDestroy()
        speech.destroy()
        CommandBridge.reportListening(false)
        if (viewModel.onAnswered === getOutOfTheWay) viewModel.onAnswered = {}
    }
}

@Composable
fun ZeroUIAssistantSheet(
    messages: List<Message>,
    operationHistory: List<OperationRecord>,
    pinnedCommands: List<String>,
    isListening: Boolean,
    partialText: String,
    voiceLevelFlow: StateFlow<Float>,
    isProcessing: Boolean,
    awaitingAnswer: Boolean,
    canStop: Boolean,
    ttsMuted: Boolean,
    speechError: String?,
    voiceRequests: Int,
    resumes: Int,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit,
    onToggleMute: () -> Unit,
    onClearChat: () -> Unit,
    onClearOperationHistory: () -> Unit,
    onTogglePin: (String) -> Unit,
    onDismissSpeechError: () -> Unit,
    onMinimize: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var inputText by remember { mutableStateOf("") }
    var showHistorySheet by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showAccessibilityGuide by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val showHeard = isListening || partialText.isNotBlank()
    val copiedToastText = stringResource(R.string.copied_feedback)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onStartListening() }

    fun requestListen() {
        if (hasMicPermission(context)) onStartListening()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    fun send(text: String) {
        if (text.isBlank()) return
        onSend(text)
        inputText = ""
    }

    val itemCount = messages.size + (if (isProcessing) 1 else 0) + (if (showHeard) 1 else 0)
    LaunchedEffect(itemCount, messages.lastOrNull()?.choices) {
        if (itemCount == 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= itemCount - 3 || itemCount <= 3) {
            listState.animateScrollToItem((itemCount - 1).coerceAtLeast(0))
        }
    }

    LaunchedEffect(voiceRequests) {
        if (voiceRequests > 0) requestListen()
    }

    // Outer translucent backdrop: tapping the top dim area minimizes ZeroUI unless an automation awaits a reply.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.statusBars)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (!awaitingAnswer) onMinimize()
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        // Floating rounded assistant sheet (inspired by Gemini / modern AI overlays)
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.84f)
                .padding(horizontal = 8.dp)
                .padding(bottom = 8.dp)
                .windowInsetsPadding(WindowInsets.navigationBars.union(WindowInsets.ime))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // absorb clicks inside the card
                ),
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f))
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Subtle top drag-handle indicator
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .width(38.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                }

                ZeroUIHeader(
                    isListening = isListening,
                    isProcessing = isProcessing,
                    awaitingAnswer = awaitingAnswer,
                    ttsMuted = ttsMuted,
                    hasHistory = operationHistory.isNotEmpty() || pinnedCommands.isNotEmpty(),
                    onToggleMute = onToggleMute,
                    onOpenHistory = { showHistorySheet = true },
                    onOpenSettings = { showSettingsSheet = true },
                    onMinimize = onMinimize,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                )

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                SetupCard(
                    resumes = resumes,
                    onOpenAccessibilityGuide = { showAccessibilityGuide = true },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )

                // Conversation body OR categorized welcome empty state
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp)
                ) {
                    if (messages.isEmpty() && !showHeard && !isProcessing) {
                        item(key = "welcome") {
                            WelcomeSection(
                                pinnedCommands = pinnedCommands,
                                onRunCommand = { send(it) },
                                onUnpinCommand = { onTogglePin(it) }
                            )
                        }
                    } else {
                        itemsIndexed(
                            items = messages,
                            key = { index, msg -> "${msg.timestamp}_${msg.isUser}_$index" }
                        ) { index, msg ->
                            val isLatestAssistant = index == messages.lastIndex && !msg.isUser && !showHeard
                            val showInlineConfirm = isLatestAssistant && msg.kind == MessageKind.CONFIRM && msg.choices.isNotEmpty()
                            Column {
                                ChatBubble(
                                    message = msg,
                                    showConfirmationButtons = showInlineConfirm,
                                    onConfirmChoice = { send(it) },
                                    onCopy = {
                                        clipboard.setText(AnnotatedString(it))
                                        Toast.makeText(context, copiedToastText, Toast.LENGTH_SHORT).show()
                                    },
                                    onRerun = { send(it) }
                                )
                                // Regular suggestion / answer chips (CONFIRM cards render dedicated primary/secondary buttons inside the card)
                                if (isLatestAssistant && msg.kind != MessageKind.CONFIRM && msg.choices.isNotEmpty()) {
                                    ChoiceChips(msg.choices, onChoice = { send(it) })
                                }
                            }
                        }
                    }
                    if (showHeard) item(key = "heard") { HeardBubble(partialText, voiceLevelFlow) }
                    if (isProcessing) item(key = "typing") { TypingIndicator() }
                }

                // Active automation awaiting-answer banner (with clear Cancel Task button!)
                AnimatedVisibility(
                    visible = awaitingAnswer,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    AwaitingAnswerBanner(
                        onCancelTask = onStop,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                // Inline speech error banner (replaces ephemeral Toast)
                AnimatedVisibility(
                    visible = speechError != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SpeechErrorBanner(
                        message = speechError.orEmpty(),
                        onRetry = {
                            onDismissSpeechError()
                            requestListen()
                        },
                        onDismiss = onDismissSpeechError,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))

                InputBar(
                    value = inputText,
                    onValueChange = { inputText = it },
                    isListening = isListening,
                    voiceLevelFlow = voiceLevelFlow,
                    canStop = canStop,
                    awaitingAnswer = awaitingAnswer,
                    onMicClick = { if (isListening) onStopListening() else requestListen() },
                    onStopClick = onStop,
                    onSendClick = { send(inputText) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                )
            }
        }
    }

    if (showAccessibilityGuide) {
        AccessibilityGuideDialog(
            onDismiss = { showAccessibilityGuide = false },
            onOpenSettings = { openSettingsSafely(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        )
    }

    if (showHistorySheet) {
        HistorySheet(
            records = operationHistory,
            pinnedCommands = pinnedCommands,
            onDismiss = { showHistorySheet = false },
            onRunCommand = { send(it) },
            onTogglePin = onTogglePin,
            onClearHistory = onClearOperationHistory
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            resumes = resumes,
            onDismiss = { showSettingsSheet = false },
            onClearChat = onClearChat,
            onOpenAccessibilityGuide = {
                showSettingsSheet = false
                showAccessibilityGuide = true
            },
            onOverlayPrefChanged = { reset ->
                CommandBridge.refreshOverlayPreferences(resetPosition = reset)
            }
        )
    }
}

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
fun ZeroUIHeader(
    isListening: Boolean,
    isProcessing: Boolean,
    awaitingAnswer: Boolean,
    ttsMuted: Boolean,
    hasHistory: Boolean,
    onToggleMute: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary)
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
        }
        Spacer(Modifier.width(8.dp))
        Column {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Spacer(Modifier.width(8.dp))
        StatusChip(isListening = isListening, isProcessing = isProcessing, awaitingAnswer = awaitingAnswer)

        Spacer(Modifier.weight(1f))

        IconButton(onClick = onToggleMute, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (ttsMuted) Icons.AutoMirrored.Rounded.VolumeOff else Icons.AutoMirrored.Rounded.VolumeUp,
                contentDescription = if (ttsMuted) stringResource(R.string.cd_unmute_tts) else stringResource(R.string.cd_mute_tts),
                tint = if (ttsMuted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(onClick = onOpenHistory, modifier = Modifier.size(36.dp)) {
            Box {
                Icon(
                    imageVector = Icons.Rounded.History,
                    contentDescription = stringResource(R.string.cd_history),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
                if (hasHistory) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }

        IconButton(onClick = onOpenSettings, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }

        IconButton(onClick = onMinimize, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowDown,
                contentDescription = stringResource(R.string.cd_minimize),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
fun StatusChip(isListening: Boolean, isProcessing: Boolean, awaitingAnswer: Boolean) {
    val (label, chipColor) = when {
        isListening -> stringResource(R.string.status_listening) to ListeningRed
        awaitingAnswer -> stringResource(R.string.status_waiting_reply) to WarningAmber
        isProcessing -> stringResource(R.string.status_working) to WorkingViolet
        else -> stringResource(R.string.status_ready) to MaterialTheme.colorScheme.primary
    }

    val active = isListening || isProcessing || awaitingAnswer
    // Only tick an infinite Choreographer transition while active; idle uses a static scale.
    val dotScale = if (active) {
        val infiniteTransition = rememberInfiniteTransition(label = "status-dot")
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 1.55f,
            animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "dot-scale"
        ).value
    } else {
        1f
    }

    Surface(
        color = chipColor.copy(alpha = 0.13f),
        shape = RoundedCornerShape(50),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                Modifier
                    .size(6.dp)
                    .scale(dotScale)
                    .background(chipColor, CircleShape)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = chipColor
            )
        }
    }
}

// ── Context & Error Banners ───────────────────────────────────────────────────

@Composable
private fun AwaitingAnswerBanner(onCancelTask: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = WarningAmber.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, WarningAmber.copy(alpha = 0.45f))
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.QuestionAnswer,
                contentDescription = null,
                tint = WarningAmber,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.banner_awaiting_title),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    stringResource(R.string.banner_awaiting_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onCancelTask, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.action_cancel_task), color = ListeningRed)
            }
        }
    }
}

@Composable
private fun SpeechErrorBanner(
    message: String,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(stringResource(R.string.action_retry))
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(30.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.cd_dismiss),
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

// ── Input Bar ─────────────────────────────────────────────────────────────────

@Composable
fun MicButton(isListening: Boolean, voiceLevelFlow: StateFlow<Float>, onClick: () -> Unit) {
    val voiceLevel by voiceLevelFlow.collectAsState()
    val scale by animateFloatAsState(
        targetValue = if (isListening) 1f + 0.24f * voiceLevel else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "mic-level"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isListening) ListeningRed else MaterialTheme.colorScheme.primary,
        animationSpec = tween(250),
        label = "mic-color"
    )

    FilledIconButton(
        onClick = onClick,
        modifier = Modifier
            .size(44.dp)
            .scale(scale),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = bgColor,
            contentColor = Color.White
        )
    ) {
        Icon(
            Icons.Rounded.Mic,
            contentDescription = if (isListening) stringResource(R.string.cd_stop_listening) else stringResource(R.string.cd_speak)
        )
    }
}

@Composable
fun StopButton(onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer
        )
    ) {
        Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.cd_stop_task))
    }
}

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isListening: Boolean,
    voiceLevelFlow: StateFlow<Float>,
    canStop: Boolean,
    awaitingAnswer: Boolean,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val borderColor by animateColorAsState(
        targetValue = when {
            isListening -> ListeningRed.copy(alpha = 0.6f)
            awaitingAnswer -> WarningAmber.copy(alpha = 0.65f)
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(220),
        label = "input-border"
    )

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Crossfade(targetState = canStop, label = "mic-or-stop") { stop ->
                if (stop) StopButton(onStopClick) else MicButton(isListening, voiceLevelFlow, onMicClick)
            }

            Spacer(Modifier.width(10.dp))

            if (isListening && value.isEmpty()) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    VoiceWaveform(levelFlow = voiceLevelFlow, color = ListeningRed)
                    Text(
                        stringResource(R.string.heard_listening),
                        style = MaterialTheme.typography.bodyMedium,
                        color = ListeningRed,
                        fontWeight = FontWeight.Medium
                    )
                }
            } else {
                Box(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (value.isEmpty()) {
                        val hint = when {
                            canStop -> stringResource(R.string.input_placeholder_working)
                            awaitingAnswer -> stringResource(R.string.input_placeholder_answering)
                            else -> stringResource(R.string.input_placeholder)
                        }
                        Text(
                            hint,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                        )
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSendClick() }),
                        maxLines = 4
                    )
                }
            }

            AnimatedVisibility(
                visible = value.isNotBlank(),
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                FilledIconButton(
                    onClick = onSendClick,
                    modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Send,
                        contentDescription = stringResource(R.string.cd_send),
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
