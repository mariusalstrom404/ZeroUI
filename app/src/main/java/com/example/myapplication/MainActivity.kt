package com.example.myapplication

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.view.Gravity
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.util.Locale
import kotlin.random.Random

data class Message(val text: String, val isUser: Boolean)

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        
        val params = window.attributes
        params.gravity = Gravity.BOTTOM
        params.width = WindowManager.LayoutParams.MATCH_PARENT
        params.height = (resources.displayMetrics.heightPixels * 0.6).toInt()
        params.dimAmount = 0.5f
        window.attributes = params
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        
        val shouldStartVoice = intent.getBooleanExtra("start_voice", false)

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    ConversationalNLPUI(
                        autoStartVoice = shouldStartVoice,
                        speechRecognizer = speechRecognizer,
                        onAssistantReply = { text -> speak(text) }
                    )
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setPitch(1.1f) // Slightly higher pitch for more "human" feel
            tts?.setSpeechRate(1.0f)
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }
}

@Composable
fun ConversationalNLPUI(
    autoStartVoice: Boolean, 
    speechRecognizer: SpeechRecognizer?,
    onAssistantReply: (String) -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val messages = remember { mutableStateListOf<Message>(Message("Hey there! How can I help you with your phone today?", false)) }
    var isListening by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    
    var pendingAction by remember { mutableStateOf<String?>(null) }
    var pendingDestination by remember { mutableStateOf<String?>(null) }

    val speechRecognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
    }

    fun startListening() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            isListening = true
            speechRecognizer?.startListening(speechRecognizerIntent)
        }
    }

    fun getRandomAcknowledge(): String {
        val options = listOf("Sure thing!", "On it!", "No problem,", "I'm on it,", "Of course,", "Got it,")
        return options[Random.nextInt(options.size)]
    }

    fun addAssistantMessage(text: String) {
        messages.add(Message(text, false))
        onAssistantReply(text)
    }

    fun processCommand(text: String) {
        messages.add(Message(text, true))
        val lowerText = text.lowercase().trim()

        when {
            // Contextual replies with personality
            pendingAction == "navigate_mode" -> {
                val dest = pendingDestination ?: "your destination"
                addAssistantMessage("${getRandomAcknowledge()} I'm starting the navigation to $dest by $text now.")
                sendCommandToService(context, "navigate to $dest by $text")
                pendingAction = null
                pendingDestination = null
            }

            pendingAction == "brightness_level" -> {
                val percent = text.filter { it.isDigit() }
                if (percent.isNotEmpty()) {
                    addAssistantMessage("Adjusting the brightness to $percent percent for you.")
                    sendCommandToService(context, "brightness $percent")
                    pendingAction = null
                } else {
                    addAssistantMessage("Sorry, I didn't catch a number there. What percentage would you like?")
                }
            }

            // Info queries with empathy
            lowerText.contains("battery") || lowerText.contains("percentage") || lowerText.contains("dying") -> {
                val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                var reply = "Your battery is currently at $level percent."
                if (level < 20) reply += " You might want to find a charger soon!"
                else if (level > 90) reply += " You're looking good for the day!"
                addAssistantMessage(reply)
            }

            // Commands with humanized phrasing
            lowerText.contains("navigate") || lowerText.contains("take me to") -> {
                val destination = extractTarget(text, listOf("navigate to", "take me to", "directions to", "navigate"))
                if (destination.isEmpty()) {
                    addAssistantMessage("I'd love to help with that. Where exactly are we going?")
                    pendingAction = "navigate_dest"
                } else {
                    pendingDestination = destination
                    addAssistantMessage("That's a great spot. How would you like to get there? By bus, walking, or driving?")
                    pendingAction = "navigate_mode"
                }
            }

            lowerText.contains("brightness") && !lowerText.any { it.isDigit() } -> {
                addAssistantMessage("I can change that. What brightness percentage would you like?")
                pendingAction = "brightness_level"
            }

            pendingAction == "open" -> {
                addAssistantMessage("${getRandomAcknowledge()} I'm opening $text for you.")
                sendCommandToService(context, "open $text")
                pendingAction = null
            }
            
            lowerText == "open" -> {
                addAssistantMessage("Sure, which app should I open?")
                pendingAction = "open"
            }

            lowerText.contains("flashlight") || lowerText.contains("torch") || lowerText.contains("light") -> {
                val enable = !lowerText.contains("off") && !lowerText.contains("stop")
                if (enable) addAssistantMessage("Sure thing, let me get that light for you.")
                else addAssistantMessage("Okay, I've turned the flashlight off.")
                sendCommandToService(context, text)
            }

            else -> {
                sendCommandToService(context, text)
                addAssistantMessage("${getRandomAcknowledge()} I'll take care of that right away.")
            }
        }
    }

    LaunchedEffect(messages.size) {
        listState.animateScrollToItem(messages.size)
    }

    LaunchedEffect(autoStartVoice) {
        if (autoStartVoice) startListening()
    }

    DisposableEffect(Unit) {
        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) { isListening = false }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) processCommand(matches[0])
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer?.setRecognitionListener(listener)
        onDispose {}
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "Assistant", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
        
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages) { msg -> ChatBubble(msg) }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask me anything...") },
                trailingIcon = {
                    IconButton(onClick = { startListening() }) {
                        Icon(imageVector = Icons.Default.Mic, contentDescription = "Mic", tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent)
            )
            
            IconButton(onClick = { if (inputText.isNotBlank()) { processCommand(inputText); inputText = "" } }, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}

@Composable
fun ChatBubble(message: Message) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val color = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Surface(color = color, shape = RoundedCornerShape(16.dp), modifier = Modifier.widthIn(max = 280.dp)) {
            Text(text = message.text, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

fun extractTarget(text: String, keywords: List<String>): String {
    val lower = text.lowercase()
    for (kw in keywords) {
        if (lower.contains(kw)) {
            val start = lower.indexOf(kw) + kw.length
            return text.substring(start).trim()
        }
    }
    return ""
}

fun sendCommandToService(context: Context, command: String) {
    val intent = Intent("com.example.myapplication.COMMAND").setPackage(context.packageName).putExtra("command_text", command)
    context.sendBroadcast(intent)
}

fun isAccessibilityServiceEnabled(context: Context, service: Class<*>): Boolean {
    val expectedComponentName = android.content.ComponentName(context, service)
    val enabledServicesSetting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
    val colonSplitter = TextUtils.SimpleStringSplitter(':')
    colonSplitter.setString(enabledServicesSetting)
    while (colonSplitter.hasNext()) {
        val enabledService = android.content.ComponentName.unflattenFromString(colonSplitter.next())
        if (enabledService != null && enabledService == expectedComponentName) return true
    }
    return false
}
