package com.example.myapplication

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.myapplication.ui.theme.MyApplicationTheme
import java.util.Locale

data class Message(val text: String, val isUser: Boolean)
enum class UIIntent { NONE, NAVIGATE_DEST, NAVIGATE_MODE, OPEN_APP, BRIGHTNESS }

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val messages = mutableStateListOf<Message>()

    private val replyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val reply = intent?.getStringExtra("reply_text") ?: return
            addAssistantMessage(reply)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        tts = TextToSpeech(this, this)
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        
        if (messages.isEmpty()) {
            messages.add(Message("Hey! I'm your assistant. I can handle multiple tasks at once. What should we do?", false))
        }

        val filter = IntentFilter("com.example.myapplication.REPLY")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(replyReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(replyReceiver, filter)
        }

        setContent {
            MyApplicationTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    ConversationalNLPUI(
                        modifier = Modifier.padding(innerPadding),
                        messages = messages,
                        autoStartVoice = intent.getBooleanExtra("start_voice", false),
                        speechRecognizer = speechRecognizer,
                        onAssistantReply = { speak(it) },
                        onUserCommand = { userInput -> processCommandLogic(userInput) }
                    )
                }
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setPitch(1.05f)
        }
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    private fun addAssistantMessage(text: String) {
        if (messages.isNotEmpty()) {
            val last = messages.last()
            if (!last.isUser && last.text == text) return
        }
        messages.add(Message(text, false))
        speak(text)
    }

    private var currentIntent = UIIntent.NONE
    private var savedEntity: String? = null

    private fun processCommandLogic(userInput: String) {
        messages.add(Message(userInput, true))
        val text = userInput.lowercase().trim()

        // 1. Detect Multi-Command Chains
        val isChain = text.contains(" and ") || text.contains(" then ") || text.contains(" next ")
        if (isChain) {
            addAssistantMessage("Sure thing, I'll get started on those tasks for you.")
            sendCommandToService(this, userInput)
            return
        }

        // 2. Handle Social Interactions
        if (text.contains("how are you") || text.contains("how's your day")) {
            addAssistantMessage(listOf("I'm doing great! Ready for your commands.", "Fantastic, thank you for asking!", "I'm having a great time helping you out.").random())
            return
        }

        // 3. Contextual Multi-turn Conversation
        when (currentIntent) {
            UIIntent.NAVIGATE_DEST -> {
                savedEntity = userInput
                addAssistantMessage("Got it. And how would you like to get to $userInput? Bus, walking, or driving?")
                currentIntent = UIIntent.NAVIGATE_MODE
                return
            }
            UIIntent.NAVIGATE_MODE -> {
                val dest = savedEntity ?: "there"
                addAssistantMessage("Perfect. Setting up your route to $dest now.")
                sendCommandToService(this, "navigate to $dest by $text")
                currentIntent = UIIntent.NONE; savedEntity = null
                return
            }
            UIIntent.OPEN_APP -> {
                addAssistantMessage("Opening $userInput for you.")
                sendCommandToService(this, "open $userInput")
                currentIntent = UIIntent.NONE; return
            }
            else -> {}
        }

        // 4. Intent Mapping
        when {
            text.contains("battery") || text.contains("power level") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                addAssistantMessage("Your battery is at $level%. ${if(level < 20) "You should charge it soon!" else "You're good to go."}")
            }
            
            text.contains("navigate") || text.contains("take me to") -> {
                val dest = extractEntity(userInput, listOf("navigate to", "take me to", "go to", "navigate"))
                if (dest.isEmpty() || dest == "somewhere") {
                    addAssistantMessage("I'd be happy to help. Where would you like to go?")
                    currentIntent = UIIntent.NAVIGATE_DEST
                } else {
                    savedEntity = dest
                    addAssistantMessage("I can do that. How do you want to get to $dest?")
                    currentIntent = UIIntent.NAVIGATE_MODE
                }
            }

            text.contains("open") || text.contains("launch") -> {
                val app = extractEntity(userInput, listOf("open", "launch"))
                if (app.isEmpty()) {
                    addAssistantMessage("Sure! Which app should I open?")
                    currentIntent = UIIntent.OPEN_APP
                } else {
                    sendCommandToService(this, userInput)
                }
            }
            
            else -> sendCommandToService(this, userInput)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(replyReceiver) } catch (e: Exception) {}
        tts?.shutdown()
        speechRecognizer?.destroy()
    }
}

@Composable
fun ConversationalNLPUI(
    modifier: Modifier = Modifier,
    messages: List<Message>,
    autoStartVoice: Boolean,
    speechRecognizer: SpeechRecognizer?,
    onAssistantReply: (String) -> Unit,
    onUserCommand: (String) -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var isListening by remember { mutableStateOf(false) }

    val speechRecognizerIntent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { if (it) { isListening = true; speechRecognizer?.startListening(speechRecognizerIntent) } }

    LaunchedEffect(messages.size) { 
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    
    LaunchedEffect(autoStartVoice) {
        if (autoStartVoice) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                isListening = true
                speechRecognizer?.startListening(speechRecognizerIntent)
            } else {
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
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
                if (!matches.isNullOrEmpty()) onUserCommand(matches[0])
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
        speechRecognizer?.setRecognitionListener(listener)
        onDispose { }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(16.dp)
    ) {
        Text("Assistant", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(bottom = 8.dp)
        ) {
            items(messages) { msg -> ChatBubble(msg) }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("How can I help?") },
                trailingIcon = {
                    IconButton(onClick = { 
                        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            isListening = true; speechRecognizer?.startListening(speechRecognizerIntent)
                        } else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }) {
                        Icon(Icons.Default.Mic, null, tint = if (isListening) Color.Red else MaterialTheme.colorScheme.primary)
                    }
                },
                shape = RoundedCornerShape(24.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                )
            )
            
            IconButton(
                onClick = { if (inputText.isNotBlank()) { onUserCommand(inputText); inputText = "" } },
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.Send, null)
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

fun extractEntity(text: String, keywords: List<String>): String {
    val lower = text.lowercase()
    for (kw in keywords) {
        if (lower.contains(kw)) {
            val start = lower.indexOf(kw) + kw.length
            if (start >= text.length) return ""
            return text.substring(start).trim().removePrefix("me to ").removePrefix("to ").trim()
        }
    }
    return ""
}

fun sendCommandToService(context: Context, command: String) {
    val intent = Intent("com.example.myapplication.COMMAND").setPackage(context.packageName).putExtra("command_text", command)
    context.sendBroadcast(intent)
}
