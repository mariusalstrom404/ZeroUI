package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ipc.CommandBridge
import com.example.myapplication.speech.Speaker
import com.example.myapplication.speech.SpeechManager
import com.example.myapplication.ui.ProcessViewModels
import com.example.myapplication.ui.VoiceViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme
import kotlinx.coroutines.launch

/** One chat message. [choices] are answers shown as buttons under the latest assistant message. */
data class Message(val text: String, val isUser: Boolean, val choices: List<String> = emptyList())

private val ListeningRed = Color(0xFFE53935)

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

    /** Bumped on every resume, so the setup card re-checks permissions changed in Settings. */
    private var resumes by mutableIntStateOf(0)

    /** Retries used up for the current automation question. */
    private var answerRetries = 0

    // The automation taps the app underneath, so this dialog must not stay on top of it.
    private val getOutOfTheWay: () -> Unit = { moveTaskToBack(true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A stray tap beside the dialog mustn't close it, above all while an automation waits for an answer.
        setFinishOnTouchOutside(false)

        speech = SpeechManager(this)
        speech.resultListener = {
            answerRetries = 0
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
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val isListening by speech.isListening.collectAsState()
                    val partialText by speech.partialText.collectAsState()
                    val level by speech.level.collectAsState()
                    ConversationalNLPUI(
                        modifier = Modifier.padding(innerPadding),
                        messages = viewModel.messages,
                        isListening = isListening,
                        partialText = partialText,
                        voiceLevel = level,
                        isProcessing = viewModel.isProcessing,
                        // While an automation waits for an answer, the mic is needed to give it.
                        canStop = viewModel.isProcessing && !viewModel.awaitingAnswer,
                        voiceRequests = voiceRequests,
                        resumes = resumes,
                        onStartListening = {
                            speech.startListening()
                            // Done while the user speaks, so the request after it starts on an open connection.
                            viewModel.warmUp()
                        },
                        onStopListening = { speech.stopListening() },
                        onStop = { viewModel.stop() },
                        onSend = { viewModel.onUserCommand(it) }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes++
    }

    /** The dialog was already open (e.g. an automation asks a question): listen again without recreating it. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START_VOICE, false)) {
            answerRetries = 0
            voiceRequests++
        }
    }

    /**
     * An automation waiting for an answer gets one more try by itself when nothing was heard;
     * otherwise the user is told what went wrong.
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
        SpeechManager.messageFor(error)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    override fun onDestroy() {
        super.onDestroy()
        speech.destroy()
        CommandBridge.reportListening(false)
        // The ViewModel outlives this Activity; a newer instance may already have set its own.
        if (viewModel.onAnswered === getOutOfTheWay) viewModel.onAnswered = {}
    }
}

@Composable
fun ConversationalNLPUI(
    modifier: Modifier = Modifier,
    messages: List<Message>,
    isListening: Boolean,
    partialText: String,
    voiceLevel: Float,
    isProcessing: Boolean,
    canStop: Boolean,
    voiceRequests: Int,
    resumes: Int,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onStop: () -> Unit,
    onSend: (String) -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val showHeard = isListening || partialText.isNotBlank()

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

    // Keeps the newest message, its buttons and the live transcript in view.
    val itemCount = messages.size + (if (isProcessing) 1 else 0) + (if (showHeard) 1 else 0)
    LaunchedEffect(itemCount, messages.lastOrNull()?.choices) {
        if (itemCount == 0) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= itemCount - 3) listState.animateScrollToItem(itemCount - 1)
    }

    LaunchedEffect(voiceRequests) {
        if (voiceRequests > 0) requestListen()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime)
    ) {
        ZeroUIHeader(
            isListening = isListening,
            isProcessing = isProcessing,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
        )

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        SetupCard(resumes = resumes, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            itemsIndexed(messages) { index, msg ->
                Column {
                    ChatBubble(msg)
                    // Only the latest message's buttons still answer anything.
                    if (index == messages.lastIndex && !msg.isUser && msg.choices.isNotEmpty() && !showHeard) {
                        ChoiceChips(msg.choices, onChoice = { send(it) })
                    }
                }
            }
            if (showHeard) item { HeardBubble(partialText) }
            if (isProcessing) item { TypingIndicator() }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        InputBar(
            value = inputText,
            onValueChange = { inputText = it },
            isListening = isListening,
            voiceLevel = voiceLevel,
            canStop = canStop,
            onMicClick = { if (isListening) onStopListening() else requestListen() },
            onStopClick = onStop,
            onSendClick = { send(inputText) },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

private fun hasMicPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

// ── Header ────────────────────────────────────────────────────────────────────

@Composable
fun ZeroUIHeader(isListening: Boolean, isProcessing: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "ZeroUI",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.weight(1f))
        StatusChip(isListening = isListening, isProcessing = isProcessing)
    }
}

@Composable
fun StatusChip(isListening: Boolean, isProcessing: Boolean) {
    val (label, chipColor) = when {
        isListening -> "Listening" to ListeningRed
        isProcessing -> "Working" to MaterialTheme.colorScheme.tertiary
        else -> "Ready" to MaterialTheme.colorScheme.primary
    }

    val active = isListening || isProcessing
    val infiniteTransition = rememberInfiniteTransition(label = "status-dot")
    val dotScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (active) 1.6f else 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "dot-scale"
    )

    Surface(
        color = chipColor.copy(alpha = 0.12f),
        shape = RoundedCornerShape(50),
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .scale(dotScale)
                    .background(chipColor, CircleShape)
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = chipColor
            )
        }
    }
}

// ── First-run setup ───────────────────────────────────────────────────────────

/**
 * Lists what ZeroUI still needs (microphone, accessibility service, and optionally system
 * settings for brightness) with a button for each. Shown only while a required one is missing,
 * so users find out before their first command fails rather than after.
 */
@Composable
fun SetupCard(resumes: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var micTick by remember { mutableIntStateOf(0) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { micTick++ }

    val mic = remember(resumes, micTick) { hasMicPermission(context) }
    val service = remember(resumes) { CommandBridge.isServiceConnected }
    val writeSettings = remember(resumes) { Settings.System.canWrite(context) }
    if (mic && service) return

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(
                "Finish setting up ZeroUI",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            SetupRow("Microphone, to hear you", done = mic) {
                micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
            SetupRow("Accessibility service, to work in other apps", done = service) {
                openSettings(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            SetupRow("Change system settings, for brightness (optional)", done = writeSettings) {
                openSettings(
                    context,
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                )
            }
        }
    }
}

@Composable
private fun SetupRow(label: String, done: Boolean, onFix: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            if (done) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = if (done) "Done" else "Not done",
            tint = if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.weight(1f)
        )
        if (!done) {
            TextButton(onClick = onFix, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Turn on") }
        }
    }
}

private fun openSettings(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        Toast.makeText(context, "Couldn't open Settings on this device.", Toast.LENGTH_SHORT).show()
    }
}

// ── Chat bubbles ───────────────────────────────────────────────────────────────

@Composable
fun ChatBubble(message: Message) {
    val isUser = message.isUser
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
    }
    val bgColor = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isUser) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            color = bgColor,
            shape = bubbleShape,
            shadowElevation = if (isUser) 2.dp else 1.dp,
            modifier = Modifier.widthIn(max = 260.dp)
        ) {
            Text(
                text = message.text,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
        }
    }
}

/** Answers to the latest question, or example commands, as buttons. A tap sends it like a reply. */
@Composable
fun ChoiceChips(choices: List<String>, onChoice: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        choices.forEach { choice ->
            SuggestionChip(
                onClick = { onChoice(choice) },
                label = { Text(choice, style = MaterialTheme.typography.labelLarge) },
                shape = RoundedCornerShape(50)
            )
        }
    }
}

/** What speech recognition has heard so far, as a faint user bubble that fills in while speaking. */
@Composable
fun HeardBubble(partialText: String) {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            modifier = Modifier.widthIn(max = 260.dp)
        ) {
            Text(
                text = partialText.ifBlank { "Listening…" },
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = if (partialText.isBlank()) FontStyle.Italic else FontStyle.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }
    }
}

// ── Processing indicator ───────────────────────────────────────────────────────

@Composable
fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(3) { index ->
                    val yOffset by infiniteTransition.animateFloat(
                        initialValue = 0f,
                        targetValue = -7f,
                        animationSpec = infiniteRepeatable(
                            tween(400, delayMillis = index * 130, easing = FastOutSlowInEasing),
                            RepeatMode.Reverse
                        ),
                        label = "dot$index"
                    )
                    Box(
                        Modifier
                            .offset(y = yOffset.dp)
                            .size(8.dp)
                            .background(
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                CircleShape
                            )
                    )
                }
            }
        }
    }
}

// ── Input bar ─────────────────────────────────────────────────────────────────

/** Grows with the user's voice while listening, so they can see they're being heard. */
@Composable
fun MicButton(isListening: Boolean, voiceLevel: Float, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (isListening) 1f + 0.3f * voiceLevel else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "mic-level"
    )
    val bgColor by animateColorAsState(
        targetValue = if (isListening) ListeningRed else MaterialTheme.colorScheme.primary,
        animationSpec = tween(300),
        label = "mic-color"
    )

    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).scale(scale),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = bgColor)
    ) {
        Icon(Icons.Default.Mic, contentDescription = if (isListening) "Stop listening" else "Speak")
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
        Icon(Icons.Default.Stop, contentDescription = "Stop")
    }
}

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isListening: Boolean,
    voiceLevel: Float,
    canStop: Boolean,
    onMicClick: () -> Unit,
    onStopClick: () -> Unit,
    onSendClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(start = 6.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // While a command runs, the mic's place is taken by stop.
            Crossfade(targetState = canStop, label = "mic-or-stop") { stop ->
                if (stop) StopButton(onStopClick) else MicButton(isListening, voiceLevel, onMicClick)
            }

            Spacer(Modifier.width(8.dp))

            Box(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (value.isEmpty()) {
                    Text(
                        if (canStop) "Working on it… tap stop to cancel" else "How can I help?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { onSendClick() }),
                    maxLines = 4
                )
            }

            AnimatedVisibility(
                visible = value.isNotBlank(),
                enter = fadeIn() + scaleIn(initialScale = 0.8f),
                exit = fadeOut() + scaleOut(targetScale = 0.8f)
            ) {
                IconButton(onClick = onSendClick) {
                    Icon(
                        Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
