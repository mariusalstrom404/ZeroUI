package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.speech.SpeechManager
import com.example.myapplication.ui.VoiceViewModel
import com.example.myapplication.ui.theme.MyApplicationTheme

data class Message(val text: String, val isUser: Boolean)
enum class UIIntent { NONE, NAVIGATE_DEST, NAVIGATE_MODE, OPEN_APP, BRIGHTNESS, CONFIRM }

private val ListeningRed = Color(0xFFE53935)

class MainActivity : ComponentActivity() {

    private val viewModel: VoiceViewModel by viewModels()
    private lateinit var speech: SpeechManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        speech = SpeechManager(this)
        speech.resultListener = { viewModel.onUserCommand(it) }
        speech.errorListener = {
            Toast.makeText(this, "Sorry, I didn't catch that. Please try again.", Toast.LENGTH_SHORT).show()
        }
        viewModel.speak = { speech.speak(it) }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConversationalNLPUI(
                        modifier = Modifier.padding(innerPadding),
                        messages = viewModel.messages,
                        isListening = speech.isListening.collectAsState().value,
                        isProcessing = viewModel.isProcessing,
                        autoStartVoice = intent.getBooleanExtra("start_voice", false),
                        onStartListening = { speech.startListening() },
                        onSend = { viewModel.onUserCommand(it) }
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        speech.destroy()
    }
}

@Composable
fun ConversationalNLPUI(
    modifier: Modifier = Modifier,
    messages: List<Message>,
    isListening: Boolean,
    isProcessing: Boolean,
    autoStartVoice: Boolean,
    onStartListening: () -> Unit,
    onSend: (String) -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) onStartListening() }

    fun requestListen() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED
        ) onStartListening()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    LaunchedEffect(messages.size) {
        if (messages.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible >= messages.size - 2) listState.animateScrollToItem(messages.size - 1)
    }

    LaunchedEffect(autoStartVoice) {
        if (autoStartVoice) requestListen()
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

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
        ) {
            items(messages) { msg -> ChatBubble(msg) }
            if (isProcessing) item { TypingIndicator() }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

        InputBar(
            value = inputText,
            onValueChange = { inputText = it },
            isListening = isListening,
            onMicClick = { requestListen() },
            onSendClick = { onSend(inputText); inputText = "" },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

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
        shape = RoundedCornerShape(50)
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

@Composable
fun MicButton(isListening: Boolean, onClick: () -> Unit) {
    val infiniteTransition = rememberInfiniteTransition(label = "mic-pulse")
    val animatedScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.18f,
        animationSpec = infiniteRepeatable(tween(600, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "scale"
    )
    val pulseScale = if (isListening) animatedScale else 1f

    val bgColor by animateColorAsState(
        targetValue = if (isListening) ListeningRed else MaterialTheme.colorScheme.primary,
        animationSpec = tween(300),
        label = "mic-color"
    )

    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(44.dp).scale(pulseScale),
        colors = IconButtonDefaults.filledIconButtonColors(containerColor = bgColor)
    ) {
        Icon(Icons.Default.Mic, contentDescription = "Microphone")
    }
}

@Composable
fun InputBar(
    value: String,
    onValueChange: (String) -> Unit,
    isListening: Boolean,
    onMicClick: () -> Unit,
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
            MicButton(isListening = isListening, onClick = onMicClick)

            Spacer(Modifier.width(8.dp))

            Box(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                if (value.isEmpty()) {
                    Text(
                        "How can I help?",
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
