package com.example.myapplication

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.example.myapplication.automation.AccessibilityScreenDevice
import com.example.myapplication.automation.ActionGuard
import com.example.myapplication.automation.AgentUser
import com.example.myapplication.automation.AutomationEngine
import com.example.myapplication.automation.ScreenAgent
import com.example.myapplication.automation.BookingAutomation
import com.example.myapplication.automation.FoodDeliveryAutomation
import com.example.myapplication.automation.FoodGorillaAutomation
import com.example.myapplication.automation.InstalledApp
import com.example.myapplication.automation.AppMatcher
import com.example.myapplication.automation.Selector
import com.example.myapplication.debug.AccessibilityTreeDumper
import com.example.myapplication.debug.Timing
import com.example.myapplication.ipc.CommandBridge
import com.example.myapplication.nlp.AppIntent
import com.example.myapplication.nlp.HotelQuery
import com.example.myapplication.nlp.OpenAiLlmClient
import com.example.myapplication.nlp.StayDate
import com.example.myapplication.nlp.SystemAction
import com.example.myapplication.overlay.FloatingMicButton
import com.example.myapplication.overlay.StatusIndicator
import com.example.myapplication.overlay.StatusIndicator.Phase
import com.example.myapplication.speech.Speaker
import com.example.myapplication.speech.SpeechManager
import com.example.myapplication.ui.ProcessViewModels
import com.example.myapplication.ui.VoiceViewModel
import androidx.lifecycle.ViewModelProvider
import com.example.myapplication.system.SystemController
import kotlinx.coroutines.*
import java.util.regex.Pattern

class MyAccessibilityService : AccessibilityService() {

    private var floatingButton: FloatingMicButton? = null
    /** For the floating button's state: the dialog's mic is open, a request is being understood, chains running. */
    private var listening = false
    private var understanding = false
    private var runningChains = 0
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null

    private val automationEngine by lazy { AutomationEngine(this) }
    private val foodGorillaAuto by lazy { FoodGorillaAutomation(automationEngine) }
    private val foodDeliveryAuto by lazy { FoodDeliveryAutomation(automationEngine) }
    private val bookingAuto by lazy { BookingAutomation(automationEngine) }
    private val systemController by lazy { SystemController(this, automationEngine) }
    private var dumpJob: Job? = null

    private var statusIndicator: StatusIndicator? = null

    /** Listens in the status pill, for the next request after one is done, while the chat is closed. */
    private val pillSpeechHolder = lazy { createPillSpeech() }
    private val pillSpeech by pillSpeechHolder
    private var pillListening = false
    private var followUpJob: Job? = null

    /** How the command currently being processed ended; set by [fail] and [ask]. */
    private enum class Outcome { OK, NEEDS_INPUT, FAILED }
    private var outcome = Outcome.OK
    private var lastReply: String? = null

    object DebugConfig {
        /**
         * Dumps the foreground app's whole tree to logcat after each screen change. Useful when
         * writing a script for a new app, but it walks the tree on the main thread while ZeroUI
         * works, so it's off unless needed.
         */
        const val ENABLE_ACCESSIBILITY_TREE_DUMP = false
        /** Logs every accessibility event; there can be dozens a second. */
        const val LOG_EVENTS = false
    }

    companion object {
        // Throats API Integration
        private const val THROATS_PKG = "com.example.threadssim"
        private const val ACTION_THROATS_POST = "com.example.threadssim.ACTION_CREATE_POST"
        private const val ACTION_THROATS_REPOST = "com.example.threadssim.ACTION_REPOST"
        private const val ACTION_THROATS_COMMENT = "com.example.threadssim.ACTION_CREATE_COMMENT"

        // NCCUbank Integration
        private const val NCCUBANK_PKG = "com.example.nccubank"
        private const val URI_DASHBOARD = "nccubank://dashboard"
        private const val URI_TRANSFER = "nccubank://transfer"
        private const val URI_HISTORY = "nccubank://history"
        private const val URI_TOP_UP = "nccubank://topup"

        // FoodGorilla Integration
        private const val FOODGORILLA_PKG = "com.example.foodgorilla"
        private const val URI_FG_RESTAURANTS = "foodgorilla://app/restaurants"
        private const val URI_FG_CART = "foodgorilla://app/cart"
        private const val URI_FG_CHECKOUT = "foodgorilla://app/checkout"
        private const val URI_FG_BUY_NOW = "foodgorilla://api/buy_now"

        /** How long a launched app gets to come to the front before the agent starts anyway. */
        private const val APP_START_TIMEOUT_MS = 5000L
        private const val ANSWER_TIMEOUT_MS = 60000L

        private val UUID_PATTERN =Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            if (DebugConfig.LOG_EVENTS) {
                Log.d("ZeroUIAutomation", "event=${it.eventType}, package=${it.packageName}, class=${it.className}")
            }
            automationEngine.onScreenEvent(it)

            // Our own overlays/dialog change constantly while we work; don't dump the tree for them.
            if (DebugConfig.ENABLE_ACCESSIBILITY_TREE_DUMP && it.packageName != packageName) {
                if (it.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
                    it.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                ) {
                    // Debounce dumping to avoid flooding Logcat
                    dumpJob?.cancel()
                    dumpJob = serviceScope.launch {
                        delay(1000) // Wait for UI to settle
                        AccessibilityTreeDumper.dump(rootInActiveWindow)
                    }
                }
            }
        }
    }

    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        CommandBridge.attachService(this)
        Speaker.init(this)
        val windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        floatingButton = FloatingMicButton(this, windowManager) { startActivity(voiceDialogIntent()) }.also { it.show() }
        statusIndicator = StatusIndicator(this, windowManager)
        automationEngine.onProgress = { statusIndicator?.updateDetail(it) }
        automationEngine.onGesturePassThrough = { statusIndicator?.setPassThrough(it) }
        statusIndicator?.onStop = { stopCurrentCommand() }
        statusIndicator?.onMic = { onPillMic() }
        statusIndicator?.onDismiss = {
            cancelFollowUp()
            statusIndicator?.hide()
        }
        statusIndicator?.onOpenChat = {
            cancelFollowUp()
            startActivity(voiceDialogIntent(listen = false))
        }
        Log.e("ZeroUIAutomation", "ACCESSIBILITY SERVICE CONNECTED")
        Log.d("NLPControl", "Service connected and ready")
    }

    /** Entry point invoked by [CommandBridge] with commands the UI has understood and confirmed. */
    fun submitCommands(commands: List<AppIntent>) {
        Log.d("NLPControl", "Service starting chain of ${commands.size}")
        currentJob?.cancel()
        currentJob = serviceScope.launch { executeCommandChain(commands) }
    }

    fun showUnderstanding(utterance: String) {
        understanding = true
        refreshFloatingButton()
        statusIndicator?.show(Phase.UNDERSTANDING, "Understanding your request…", "“$utterance”")
    }

    /** Takes down the "understanding" state when nothing is left to run for that utterance. */
    fun clearUnderstanding() {
        understanding = false
        refreshFloatingButton()
        if (currentJob?.isActive != true) statusIndicator?.hide()
    }

    /** The voice dialog's mic opened or closed. */
    fun setListening(on: Boolean) {
        listening = on
        refreshFloatingButton()
    }

    private fun refreshFloatingButton() {
        floatingButton?.setState(
            when {
                listening || pillListening -> FloatingMicButton.State.LISTENING
                understanding || runningChains > 0 -> FloatingMicButton.State.WORKING
                else -> FloatingMicButton.State.READY
            }
        )
    }

    /** The stop button on the indicator or in the chat: cancels the running command wherever it is. */
    fun stopCurrentCommand() {
        understanding = false
        refreshFloatingButton()
        val stoppedRequest = CommandBridge.postStop()
        val job = currentJob?.takeIf { it.isActive }
        if (job == null && !stoppedRequest) return
        Log.d("NLPControl", "Stopped by the user")
        job?.cancel()
        cancelFollowUp()
        Speaker.stop()
        statusIndicator?.show(Phase.STOPPED, "Stopped", "Nothing else will be tapped")
        sendReply("Okay, I stopped.")
    }

    private suspend fun executeCommandChain(commands: List<AppIntent>) {
        CommandBridge.postStatus(CommandBridge.Status.STARTED)
        cancelFollowUp()
        val chainStart = Timing.now()
        var listenAfter = false
        var question: String? = null
        understanding = false
        runningChains++
        refreshFloatingButton()
        try {
            var failure: String? = null
            for ((index, intent) in commands.withIndex()) {
                val progress = if (commands.size > 1) "Command ${index + 1} of ${commands.size}" else null
                processSingleCommand(intent, progress)
                when (outcome) {
                    Outcome.FAILED -> failure = lastReply
                    Outcome.NEEDS_INPUT -> question = lastReply
                    Outcome.OK -> {}
                }
                // Later commands may depend on this one, so they don't run on top of a failure.
                if (outcome != Outcome.OK) {
                    val skipped = commands.size - index - 1
                    if (skipped > 0) sendReply("I didn't do the $skipped remaining ${if (skipped == 1) "command" else "commands"}.")
                    break
                }
                if (index < commands.lastIndex) {
                    statusIndicator?.show(Phase.WORKING, "Waiting for the screen to settle", "Next: ${describe(commands[index + 1])}")
                    // As long as the app takes to finish loading, not a fixed time.
                    automationEngine.waitForScreenToSettle()
                }
            }

            when {
                failure != null -> statusIndicator?.show(Phase.ERROR, "Something went wrong", failure)
                question != null -> statusIndicator?.show(Phase.NEEDS_INPUT, "Need more information", question)
                else -> {
                    statusIndicator?.show(Phase.SUCCESS, "Done", lastReply)
                    // Success replies are spoken by sendReply; say something when there wasn't one.
                    if (lastReply == null) Speaker.speak("Done.", interrupt = false)
                }
            }
            listenAfter = listensAfter(commands.last())
        } finally {
            Timing.log("Command chain of ${commands.size}: ${Timing.now() - chainStart} ms")
            runningChains--
            refreshFloatingButton()
            CommandBridge.postStatus(CommandBridge.Status.FINISHED)
        }
        // Not reached when the chain was stopped. ZeroUI stays with the user instead of going quiet.
        if (listenAfter) CommandBridge.listenForMore(question)
    }

    /** Not after the screen was locked or the power menu opened: nobody is about to talk to ZeroUI. */
    private fun listensAfter(intent: AppIntent): Boolean =
        !(intent is AppIntent.SystemControl &&
            (intent.action == SystemAction.LOCK_SCREEN || intent.action == SystemAction.POWER_MENU))

    // --- Listening in the status pill ---

    /**
     * Opens the pill's mic for the user's next request once ZeroUI has finished speaking. [prompt]
     * is the pill's title, such as a question that was just asked. Called through
     * [CommandBridge.listenForMore] when the chat isn't on screen.
     */
    fun startFollowUp(prompt: String?) {
        followUpJob?.cancel()
        followUpJob = serviceScope.launch {
            Speaker.awaitSilence()
            // A newer request may have started, or the chat opened, while ZeroUI was talking.
            if (runningChains > 0 || understanding) return@launch
            if (CommandBridge.listenInDialog()) return@launch
            listenInPill(prompt ?: "Anything else?", "Listening… just say it")
        }
    }

    /** Closes the pill's mic without acting on what was heard, e.g. when the chat opens. */
    fun cancelFollowUp() {
        followUpJob?.cancel()
        followUpJob = null
        if (pillListening) {
            pillSpeech.cancel()
            pillListening = false
            refreshFloatingButton()
            if (statusIndicator?.currentPhase == Phase.LISTENING) statusIndicator?.hide()
        }
    }

    fun offerMic(title: String) {
        cancelFollowUp()
        clearUnderstanding()
        statusIndicator?.show(Phase.READY, title, "Tap the mic to try again")
    }

    fun endConversation() {
        cancelFollowUp()
        understanding = false
        refreshFloatingButton()
        statusIndicator?.hide()
    }

    /** The pill's mic button: speak now, or finish speaking. */
    private fun onPillMic() {
        if (pillListening) {
            pillSpeech.stopListening()
            return
        }
        followUpJob?.cancel()
        Speaker.stop() // the user wants to talk; ZeroUI stops talking
        listenInPill("I'm listening", "Say what you need, or give feedback")
    }

    private fun listenInPill(title: String, detail: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            statusIndicator?.show(Phase.READY, "Tap here to open ZeroUI", "It needs the microphone to hear you")
            return
        }
        statusIndicator?.show(Phase.LISTENING, title, detail)
        pillListening = true
        refreshFloatingButton()
        pillSpeech.startListening()
    }

    private fun createPillSpeech() = SpeechManager(this).also { speech ->
        speech.resultListener = { text ->
            pillListening = false
            refreshFloatingButton()
            // Handled exactly like a request in the chat, which also keeps it in the history.
            voiceViewModel().onUserCommand(text)
        }
        speech.errorListener = { error ->
            pillListening = false
            refreshFloatingButton()
            val message = if (SpeechManager.heardNothing(error)) null else SpeechManager.messageFor(error)
            statusIndicator?.show(
                Phase.READY,
                message ?: "Tap the mic to say more",
                if (message == null) "Or tap here to type" else null
            )
        }
        serviceScope.launch {
            speech.partialText.collect { heard ->
                if (pillListening && heard.isNotBlank()) statusIndicator?.updateDetail("“$heard”")
            }
        }
    }

    /** The process-wide chat, created here if ZeroUI hasn't been opened since the process started. */
    private fun voiceViewModel(): VoiceViewModel =
        ViewModelProvider(
            ProcessViewModels,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application)
        )[VoiceViewModel::class.java]

    private suspend fun processSingleCommand(intent: AppIntent, progress: String? = null) {
        Log.d("NLPControl", "Processing: $intent")
        outcome = Outcome.OK
        lastReply = null
        statusIndicator?.show(Phase.WORKING, describe(intent), progress)
        try {
            Timing.measure("Command ${intent::class.simpleName}") { dispatch(intent) }
        } catch (e: CancellationException) {
            throw e // replaced by a newer command; that one owns the indicator now
        } catch (e: Exception) {
            Log.e("NLPControl", "Command failed: $intent", e)
            fail("Sorry, something went wrong while doing that.")
        }
    }

    /** What the indicator says while [intent] runs. */
    private fun describe(intent: AppIntent): String = when (intent) {
        AppIntent.CheckBalance -> "Checking your balance"
        is AppIntent.Transfer -> "Transferring money"
        AppIntent.TransactionHistory -> "Opening transaction history"
        AppIntent.TopUp -> "Opening top up"
        is AppIntent.FoodSearch -> "Searching FoodGorilla"
        is AppIntent.FoodOrder -> "Ordering on FoodGorilla"
        AppIntent.FoodCart -> "Opening your cart"
        AppIntent.FoodCheckout -> "Going to checkout"
        is AppIntent.FoodMenu -> "Opening the menu"
        is AppIntent.ThroatsPost -> "Posting to Throats"
        is AppIntent.ThroatsRepost -> "Reposting"
        is AppIntent.ThroatsComment -> "Commenting"
        is AppIntent.Flashlight -> if (intent.enable) "Turning on the flashlight" else "Turning off the flashlight"
        AppIntent.Battery -> "Checking the battery"
        is AppIntent.Volume -> "Adjusting the volume"
        is AppIntent.Brightness -> "Adjusting the brightness"
        AppIntent.Greeting -> "Saying hello"
        AppIntent.SaveHistory -> "Saving history"
        AppIntent.ClearHistory -> "Clearing history"
        AppIntent.RepeatLast -> "Repeating your last command"
        AppIntent.TakeScreenshot -> "Taking a screenshot"
        is AppIntent.CameraAction -> "Opening the camera"
        is AppIntent.SystemToggle -> when (intent.enable) {
            true -> "Turning on ${intent.setting.label}"
            false -> "Turning off ${intent.setting.label}"
            null -> "Switching ${intent.setting.label}"
        }
        is AppIntent.SystemControl -> intent.action.label
        is AppIntent.OpenSettings -> "Opening ${intent.page.label}"
        is AppIntent.OpenSystemApp -> "Opening ${intent.app.labels.first()}"
        is AppIntent.Navigate -> "Starting navigation"
        is AppIntent.OpenApp -> "Opening ${intent.name}".trim()
        is AppIntent.AppOrder -> "Ordering ${intent.item} on ${intent.app}"
        is AppIntent.HotelSearch -> when {
            intent.book && intent.hotel != null -> "Booking ${intent.hotel}"
            intent.hotel != null -> "Looking up ${intent.hotel}"
            else -> "Finding stays in ${intent.destination}"
        }
        is AppIntent.TypeText -> if (intent.submit) "Searching for “${intent.text}”" else "Typing “${intent.text}”"
        is AppIntent.ClickText -> "Looking for “${intent.text}” on screen"
        is AppIntent.AppTask -> intent.goal
        AppIntent.Unknown -> "Working on it"
    }

    /** Executes a parsed [AppIntent] using the accessibility/system capabilities. */
    private suspend fun dispatch(intent: AppIntent) {
        when (intent) {
            // NCCUbank: Check Balance
            AppIntent.CheckBalance -> {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(URI_DASHBOARD)).setPackage(NCCUBANK_PKG)
                val launched = if (safeStartActivity(viewIntent)) NCCUBANK_PKG else launchAppByName("NCCUbank")
                if (launched != null) {
                    val balanceCard = automationEngine.waitForElement(
                        Selector(contentDescription = "Wallet Balance Display", exact = true),
                        timeoutMs = 6000,
                        packageName = launched
                    )
                    val balance = if (balanceCard != null) findCurrencyTextInSubtree(balanceCard) else null
                    if (balance != null) sendReply("Your balance is $balance.")
                    else fail("I opened NCCUbank but couldn't find the balance on screen.")
                } else {
                    fail("I couldn't find the NCCUbank app on your device.")
                }
            }

            // NCCUbank: Transfer
            is AppIntent.Transfer -> {
                val amount = intent.amount
                val recipient = intent.recipient
                if (amount != null && recipient != null) {
                    val uri = Uri.parse("$URI_TRANSFER?amount=$amount&recipient=$recipient")
                    val viewIntent = Intent(Intent.ACTION_VIEW, uri).setPackage(NCCUBANK_PKG)

                    if (safeStartActivity(viewIntent)) {
                        note("Initiating transfer of $amount to $recipient.")
                        val success = automationEngine.waitForElement(
                            Selector(text = "Success", exact = true),
                            timeoutMs = 6000,
                            packageName = NCCUBANK_PKG
                        )
                        if (success != null) sendReply("Transfer completed successfully!")
                        else fail("I started the transfer but couldn't confirm it went through. Please check NCCUbank.")
                    } else {
                        // Deep link failed, try manual navigation
                        val pkg = launchAppByName("NCCUbank")
                        if (pkg != null) {
                            if (performClickOnText("Transfer", "Transfer Button", packageName = pkg, timeoutMs = 6000)) {
                                delay(1500)
                                sendReply("I've opened the transfer screen in NCCUbank. Please confirm the details.")
                            } else {
                                fail("I couldn't start the transfer. Is the NCCUbank app up to date?")
                            }
                        } else {
                            fail("I couldn't find the NCCUbank app.")
                        }
                    }
                } else {
                    ask("I need both an amount and a recipient to make a transfer.")
                }
            }

            // NCCUbank: History
            AppIntent.TransactionHistory -> {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(URI_HISTORY)).setPackage(NCCUBANK_PKG)
                if (!safeStartActivity(viewIntent)) {
                    val pkg = launchAppByName("NCCUbank")
                    if (pkg != null) {
                        if (performClickOnText("History", "History Button", "Transactions", packageName = pkg, timeoutMs = 6000)) {
                            sendReply("Showing your transaction history.")
                        } else {
                            fail("I couldn't find the history button in NCCUbank.")
                        }
                    } else {
                        fail("NCCUbank is not installed.")
                    }
                } else {
                    sendReply("Showing your transaction history.")
                }
            }

            // NCCUbank: Top up
            AppIntent.TopUp -> {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(URI_TOP_UP)).setPackage(NCCUBANK_PKG)
                if (!safeStartActivity(viewIntent)) {
                    val pkg = launchAppByName("NCCUbank")
                    if (pkg != null) {
                        if (performClickOnText("Top-up", "Top Up", "Top-up Button", "Deposit", packageName = pkg, timeoutMs = 6000)) {
                            sendReply("Opening the top up screen.")
                        } else {
                            fail("I couldn't find the top up button.")
                        }
                    } else {
                        fail("NCCUbank is not installed.")
                    }
                } else {
                    sendReply("Opening the top up screen.")
                }
            }

            // FoodGorilla: Search
            is AppIntent.FoodSearch -> {
                if (intent.query.isNotEmpty()) {
                    val uri = Uri.parse("$URI_FG_RESTAURANTS?search=${Uri.encode(intent.query)}")
                    if (safeStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(FOODGORILLA_PKG))) {
                        sendReply("Searching for ${intent.query} on FoodGorilla.")
                    } else {
                        fail("I couldn't open FoodGorilla. Is it installed?")
                    }
                } else {
                    ask("What would you like to search for on FoodGorilla?")
                }
            }

            // FoodGorilla: Buy/Order
            is AppIntent.FoodOrder -> {
                val query = intent.query ?: ""

                if (query.isNotEmpty()) {
                    // Optimized extraction for "item from restaurant"
                    val fromIndex = query.indexOf(" from ", ignoreCase = true)

                    // CommandConversation asks for the restaurant, so this is only a safety net.
                    if (fromIndex == -1) {
                        ask("Which restaurant should I order $query from?")
                        return
                    }
                    val item = query.substring(0, fromIndex).trim()
                    val restaurant = query.substring(fromIndex + 6).trim()

                    note("Running the FoodGorilla order for $item at $restaurant.")
                    Log.e("ZeroUIAutomation", "FOOD AUTOMATION START: $item at $restaurant")

                    val success = foodGorillaAuto.runOrderWorkflow(restaurant, item)

                    if (success) {
                        sendReply("FoodGorilla automation reached checkout successfully.")
                    } else {
                        fail("FoodGorilla automation failed. Check logs for details.")
                    }
                } else if (intent.itemId != null) {
                    val uri = Uri.parse("$URI_FG_BUY_NOW?itemId=${intent.itemId}&quantity=1")

                    if (safeStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(FOODGORILLA_PKG))) {
                        sendReply("Ordering item ${intent.itemId} from FoodGorilla.")
                    } else {
                        fail("I couldn't complete the order on FoodGorilla.")
                    }
                } else {
                    ask("What would you like to order?")
                }
            }

            // FoodGorilla: Cart / Checkout
            AppIntent.FoodCart -> {
                if (safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URI_FG_CART)).setPackage(FOODGORILLA_PKG))) {
                    sendReply("Opening your FoodGorilla cart.")
                } else {
                    fail("I couldn't open the FoodGorilla cart.")
                }
            }

            AppIntent.FoodCheckout -> {
                if (safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URI_FG_CHECKOUT)).setPackage(FOODGORILLA_PKG))) {
                    sendReply("Taking you to FoodGorilla checkout.")
                } else {
                    fail("I couldn't open the FoodGorilla checkout.")
                }
            }

            // FoodGorilla: Restaurant Menu
            is AppIntent.FoodMenu -> {
                if (intent.restaurant.isNotEmpty()) {
                    val uri = Uri.parse("foodgorilla://app/restaurant/${intent.restaurant}")
                    if (safeStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(FOODGORILLA_PKG))) {
                        sendReply("Opening the menu for ${intent.restaurant} on FoodGorilla.")
                    } else {
                        fail("I couldn't find that restaurant menu.")
                    }
                } else {
                    ask("Which restaurant's menu would you like to see?")
                }
            }

            // Throats: Create Post
            is AppIntent.ThroatsPost -> {
                if (intent.content.isNotEmpty()) {
                    sendThroatsBroadcast(
                        ACTION_THROATS_POST,
                        mapOf(
                            "content" to intent.content,
                            "author" to "VoiceAssistant"
                        )
                    )
                    sendReply("I've posted that to Throats for you.")
                } else {
                    ask("What would you like me to post?")
                }
            }

            // Throats: Repost
            is AppIntent.ThroatsRepost -> {
                val postId = intent.postId ?: findUuidOnScreen()
                if (postId != null) {
                    sendThroatsBroadcast(
                        ACTION_THROATS_REPOST,
                        mapOf(
                            "post_id" to postId,
                            "author" to "VoiceAssistant"
                        )
                    )
                    sendReply("Okay, I've reposted that.")
                } else {
                    fail("I couldn't find a post ID to repost.")
                }
            }

            // Throats: Comment
            is AppIntent.ThroatsComment -> {
                val postId = intent.postId ?: findUuidOnScreen()
                if (postId != null && intent.content.isNotEmpty()) {
                    sendThroatsBroadcast(
                        ACTION_THROATS_COMMENT,
                        mapOf(
                            "parent_post_id" to postId,
                            "content" to intent.content,
                            "author" to "VoiceAssistant"
                        )
                    )
                    sendReply("Comment posted successfully.")
                } else {
                    ask("I need a post and content to comment.")
                }
            }

            // Flashlight
            is AppIntent.Flashlight -> {
                if (toggleFlashlight(intent.enable)) {
                    sendReply(
                        if (intent.enable)
                            "I've turned the light on for you."
                        else
                            "Flashlight is now off."
                    )
                } else {
                    fail("I couldn't toggle the flashlight. Make sure I have camera permissions.")
                }
            }

            // Battery
            AppIntent.Battery -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                sendReply("Your battery is at $level%.")
            }

            // Volume
            is AppIntent.Volume -> when (intent.change) {
                AppIntent.Change.SET -> {
                    val level = (intent.level ?: 50).coerceIn(0, 100)
                    setVolume(level)
                    sendReply("Volume set to $level%.")
                }

                AppIntent.Change.UP -> {
                    adjustVolume(true)
                    sendReply("Turning the volume up.")
                }

                AppIntent.Change.DOWN -> {
                    adjustVolume(false)
                    sendReply("Turning the volume down.")
                }
            }

            // Brightness
            is AppIntent.Brightness -> {
                if (!canWriteSettings()) {
                    fail("I need permission to change system settings. Please grant it in Settings.")
                } else when (intent.change) {
                    AppIntent.Change.SET -> {
                        val level = (intent.level ?: 50).coerceIn(0, 100)
                        adjustBrightness(level)
                        sendReply("Brightness set to $level%.")
                    }

                    AppIntent.Change.UP -> {
                        adjustBrightnessRelative(true)
                        sendReply("Increasing the brightness.")
                    }

                    AppIntent.Change.DOWN -> {
                        adjustBrightnessRelative(false)
                        sendReply("Lowering the brightness.")
                    }
                }
            }

            // Conversation Management
            AppIntent.Greeting -> {
                sendReply(
                    "Hi! I'm ZeroUI. You can use me to control your device, navigate, handle banking with NCCUbank, order food, or post on social media. Try saying 'check my balance', 'take a screenshot', or 'repeat'. How can I help?"
                )
            }

            AppIntent.SaveHistory -> {
                sendReply("Conversation history has been saved.")
            }

            AppIntent.ClearHistory -> {
                sendReply("I've cleared the conversation history.")
            }

            // CommandConversation expands a repeat before anything reaches the service.
            AppIntent.RepeatLast -> {
                fail("I don't have a previous command to repeat yet.")
            }

            AppIntent.TakeScreenshot -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    statusIndicator?.hideNow() // keep the indicator out of the screenshot
                    delay(300)
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                    delay(1000)
                    sendReply("Taking a screenshot for you.")
                } else {
                    fail("Screenshot requires Android 11 or higher.")
                }
            }

            is AppIntent.CameraAction -> {
                val takePhoto = intent.takePhoto
                val camIntent = if (takePhoto) {
                    Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)
                } else {
                    Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                }

                if (safeStartActivity(camIntent)) {
                    sendReply(
                        if (takePhoto)
                            "Opening camera to take a photo."
                        else
                            "Opening camera."
                    )
                } else {
                    fail("I couldn't open the camera app.")
                }
            }

            is AppIntent.SystemToggle -> toggleSetting(intent)

            is AppIntent.SystemControl -> {
                // These act on the app underneath, so the voice dialog gets out of the way first.
                if (intent.action == SystemAction.BACK || intent.action == SystemAction.SPLIT_SCREEN) delay(400)
                if (systemController.perform(intent.action)) {
                    sendReply("${intent.action.label}.")
                } else {
                    fail("This phone doesn't let me do that.")
                }
            }

            is AppIntent.OpenSettings -> when (systemController.openSettings(intent.page)) {
                intent.page -> sendReply("Opening ${intent.page.label}.")
                null -> fail("I couldn't open Settings.")
                else -> sendReply("This phone has no ${intent.page.label} page I can open directly, so I opened Settings.")
            }

            is AppIntent.OpenSystemApp -> {
                val name = intent.app.labels.first()
                if (systemController.openSystemApp(intent.app) { launchAppByName(it) }) {
                    sendReply("Opening $name.")
                } else {
                    fail("I couldn't find a $name app on this phone.")
                }
            }

            // Navigation
            is AppIntent.Navigate -> {
                if (!intent.destination.isNullOrEmpty()) {
                    startNavigation(
                        intent.destination,
                        if (intent.mode == "bus") "r" else "d"
                    )
                    sendReply("Opening navigation to ${intent.destination}.")
                } else {
                    ask("Where would you like to navigate to?")
                }
            }

            // App Launching
            is AppIntent.OpenApp -> {
                if (intent.name.isNotEmpty()) {
                    if (openAppByName(intent.name)) {
                        sendReply("Opening ${intent.name}.")
                    } else {
                        fail("I couldn't find an app named ${intent.name}.")
                    }
                } else {
                    ask("Which app should I open?")
                }
            }

            // Real delivery apps: "order Hawaiian Pizza from Pizza Hut on Foodpanda"
            is AppIntent.AppOrder -> {
                val restaurant = intent.restaurant
                when {
                    intent.item.isEmpty() -> ask("What would you like to order on ${intent.app}?")
                    restaurant.isNullOrEmpty() -> ask("Which restaurant should I order ${intent.item} from on ${intent.app}?")
                    else -> {
                        val pkg = launchAppByName(intent.app)
                        if (pkg == null) {
                            fail("I couldn't find an app named ${intent.app}.")
                        } else {
                            note("Looking for ${intent.item} from $restaurant on ${intent.app}.")
                            when (val result = foodDeliveryAuto.order(pkg, restaurant, intent.item)) {
                                FoodDeliveryAutomation.Result.ADDED ->
                                    sendReply("I added ${intent.item} from $restaurant to your ${intent.app} cart. Please review it and check out yourself.")
                                FoodDeliveryAutomation.Result.ADDED_UNVERIFIED ->
                                    sendReply("I tapped Add to cart for ${intent.item}. Please check your ${intent.app} cart to make sure it's there.")
                                else -> recoverOrder(intent, restaurant, result)
                            }
                        }
                    }
                }
            }

            // Booking.com: "find a hotel in Tokyo from Oct 10 to 12 on booking.com"
            is AppIntent.HotelSearch -> {
                val hotel = intent.hotel
                val guests = intent.guests
                when {
                    hotel == null && intent.destination.isEmpty() -> ask("Where would you like to stay?")
                    // The chat UI normally collects these itself before sending the command.
                    hotel == null && intent.book ->
                        ask("Which hotel in ${intent.destination} would you like to book? Say its name, or say “any” to see the hotels there.")
                    (intent.children ?: 0) > intent.childAges.size ->
                        ask("How old are the children? Booking.com needs each child's age.")
                    else -> {
                        val pkg = launchAppByName("Booking.com") ?: launchPackage(BookingAutomation.PACKAGE)
                        when {
                            pkg == null -> fail("I couldn't find the Booking.com app.")
                            hotel != null && intent.book ->
                                reserveHotel(pkg, hotel, intent.destination, intent.checkIn, intent.checkOut, guests, intent.room)
                            hotel != null -> searchStays(pkg, "$hotel ${intent.destination}".trim(), intent.checkIn, intent.checkOut, guests)
                            else -> searchStays(pkg, intent.destination, intent.checkIn, intent.checkOut, guests)
                        }
                    }
                }
            }

            // Any app: type into its search bar ("search for pizza on foodpanda")
            is AppIntent.TypeText -> {
                // "on foodpanda" names an app only if one is installed; otherwise it's part of the text.
                val appPkg = intent.app?.takeIf { it.length >= 3 }?.let { findAppByName(it) }
                val text = if (intent.app != null && appPkg == null) intent.fullText else intent.text
                val foreground = automationEngine.finder.roots().firstOrNull()?.packageName?.toString()

                val pkg = when {
                    text.isEmpty() -> null
                    appPkg == null -> foreground
                    appPkg == foreground -> appPkg
                    else -> launchAppByName(intent.app!!)
                }
                // Booking.com has no plain search bar; "search Tokyo" there means a stay search.
                if (pkg == BookingAutomation.PACKAGE) {
                    searchStays(pkg, text, null, null, null)
                    return
                }
                when {
                    text.isEmpty() -> ask("What should I search for?")
                    pkg == null && appPkg != null -> fail("I couldn't open ${intent.app}.")
                    pkg == null -> fail("Open the app you want to search in first.")
                    automationEngine.typeIntoApp(text, intent.submit, pkg, timeoutMs = if (pkg != foreground) 15000 else 8000) ->
                        sendReply(
                            when {
                                !automationEngine.lastInputVerified -> "I entered $text but couldn't confirm it appeared. Please check the screen."
                                intent.submit -> "Searching for $text."
                                else -> "Typed $text."
                            }
                        )
                    else -> fail("I couldn't find a search bar to type “$text” into.")
                }
            }

            // Fallback: best-effort click on whatever was said
            is AppIntent.ClickText -> {
                // Long enough for a screen that's still loading, and a few scrolls for a label below the fold.
                val node = automationEngine.waitForElement(
                    Selector(text = intent.text), timeoutMs = 6000, scrollIfNotFound = true, packageName = null, maxScrolls = 3
                )
                when {
                    node == null -> fail("I couldn't find “${intent.text}” on screen.")
                    // The user named the button, so other taps are fine, but ZeroUI never pays.
                    ActionGuard.forTap(automationEngine.finder.tapLabels(node)) == ActionGuard.Verdict.BLOCK ->
                        sendReply("That looks like a payment step, so I left it for you to tap yourself.")
                    automationEngine.click(node) -> sendReply("Clicked on ${intent.text}.")
                    else -> fail("I couldn't tap “${intent.text}”.")
                }
            }

            // Any app: the screen agent works it out from what's on screen.
            is AppIntent.AppTask -> {
                val pkg = intent.app?.let { launchAppByName(it) }
                if (intent.app != null && pkg == null) {
                    fail("I couldn't find an app named ${intent.app}.")
                } else {
                    // As long as the app takes to come up, not a fixed time; the agent's first read waits for it to settle.
                    if (pkg != null) automationEngine.waitForApp(pkg, APP_START_TIMEOUT_MS)
                    reportAgent(runAgent(intent.goal))
                }
            }

            // CommandConversation answers these itself; never tap on a guess.
            AppIntent.Unknown -> {
                fail("Sorry, I didn't understand that.")
            }
        }
    }

    /**
     * A direct switch or Quick Settings first; when neither works on this phone, the switch on the
     * setting's own Settings page, and only if that can't be found either, the screen agent.
     */
    private suspend fun toggleSetting(intent: AppIntent.SystemToggle) {
        val setting = intent.setting
        val state = systemController.toggle(setting, intent.enable)
        if (state != null) {
            sendReply("${setting.label} is ${if (state) "on" else "off"}.")
            return
        }
        if (systemController.openSettings(setting.page) == null) {
            fail("I couldn't change ${setting.label} on this phone.")
            return
        }
        automationEngine.waitForScreenToSettle()
        systemController.toggleOnPage(setting, intent.enable)?.let { now ->
            sendReply("${setting.label} is ${if (now) "on" else "off"}.")
            return
        }
        val change = when (intent.enable) {
            true -> "Turn ${setting.label} on."
            false -> "Turn ${setting.label} off."
            null -> "Switch ${setting.label} to the other state."
        }
        reportAgent(runAgent("$change Change only this one setting, then stop."))
    }

    private suspend fun runAgent(goal: String): ScreenAgent.Outcome =
        Timing.measure("Screen agent run") {
            ScreenAgent(
                AccessibilityScreenDevice(automationEngine),
                OpenAiLlmClient(assets),
                AgentUser { question, choices -> askByVoice(question, choices) },
                onProgress = { automationEngine.report(it) }
            ).run(goal)
        }

    /** The script couldn't finish an order: the agent carries on from whatever is on screen. */
    private suspend fun recoverOrder(intent: AppIntent.AppOrder, restaurant: String, result: FoodDeliveryAutomation.Result) {
        statusIndicator?.show(Phase.WORKING, "Trying another way", "${intent.item} from $restaurant")
        val goal = "In ${intent.app}, add ${intent.item} from $restaurant to the cart. " +
            "If it has required choices, ask me which ones I want. Stop once it's in the cart."
        when (val outcome = runAgent(goal)) {
            is ScreenAgent.Outcome.Done ->
                sendReply("${outcome.summary ?: "I added it."} Please review your cart and check out yourself.")
            // The script's own message says more than "the language service is unreachable" here.
            ScreenAgent.Outcome.Unavailable, is ScreenAgent.Outcome.GaveUp ->
                when (result) {
                    FoodDeliveryAutomation.Result.NEEDS_OPTIONS ->
                        ask("${intent.item} needs a few choices (like size or toppings). Please pick them on screen, then tap Add to cart.")
                    FoodDeliveryAutomation.Result.SEARCH_FAILED -> fail("I couldn't use the search bar in ${intent.app}.")
                    FoodDeliveryAutomation.Result.RESTAURANT_NOT_FOUND -> fail("I couldn't find $restaurant in the ${intent.app} search results.")
                    FoodDeliveryAutomation.Result.ITEM_NOT_FOUND -> fail("I opened $restaurant but couldn't find ${intent.item} on the menu.")
                    else -> fail("I found ${intent.item} but couldn't find the Add to cart button.")
                }
            else -> reportAgent(outcome)
        }
    }

    private fun reportAgent(outcome: ScreenAgent.Outcome) {
        when (outcome) {
            is ScreenAgent.Outcome.Done -> sendReply(outcome.summary ?: "Done.")
            is ScreenAgent.Outcome.HandedOver ->
                sendReply("I stopped at “${outcome.what}”. I don't pay or enter private details, so nothing has been submitted; please finish this step yourself.")
            ScreenAgent.Outcome.Declined -> sendReply("Okay, I stopped there.")
            ScreenAgent.Outcome.NoAnswer -> ask("I didn't get an answer, so I stopped there.")
            is ScreenAgent.Outcome.GaveUp -> fail("I couldn't finish that${outcome.reason?.let { ": $it" } ?: "."}")
            ScreenAgent.Outcome.Unavailable -> fail("I can't reach my language service right now, so I stopped.")
        }
    }

    /**
     * Says [question], opens the voice dialog over the app and waits for the reply. It stays a
     * stoppable phase, so the stop button keeps working while waiting. Typing in the dialog also
     * answers, which is the way out if speech recognition fails.
     */
    private suspend fun askByVoice(question: String, choices: List<String> = emptyList()): String? {
        // At the top, so the pill doesn't cover the dialog's text field and mic.
        statusIndicator?.show(Phase.WORKING, "Waiting for your answer", question, atTop = true)
        // Expected from now on, so tapping a choice while the question is still read out answers it.
        val answer = CommandBridge.expectAnswer()
        try {
            CommandBridge.postReply(question, choices)
            // The dialog opens while the question is read out instead of after it; the mic only
            // starts once it has been said, so it doesn't hear ZeroUI's own voice.
            startActivity(voiceDialogIntent(listen = false))
            Speaker.speakAndWait(question)
            if (!answer.isCompleted) startActivity(voiceDialogIntent())
            return withTimeoutOrNull(ANSWER_TIMEOUT_MS) { answer.await() }
                ?.also { statusIndicator?.show(Phase.WORKING, "Continuing", it) }
        } finally {
            CommandBridge.clearAnswer(answer)
        }
    }

    private suspend fun searchStays(
        pkg: String,
        destination: String,
        checkIn: StayDate?,
        checkOut: StayDate?,
        guests: HotelQuery.Guests?
    ) {
        val wanted = stayLabel(checkIn, checkOut)
        val details = listOfNotNull(wanted, guests?.label()).joinToString(", ")
        note("Searching Booking.com for stays in $destination${if (details.isNotEmpty()) ", $details" else ""}.")
        when (val result = bookingAuto.searchStays(pkg, destination, checkIn, checkOut, guests)) {
            is BookingAutomation.Result.Found -> {
                val found = result.properties?.let { "Found $it" } ?: "Here are the stays"
                val where = result.searchSummary?.replace(Regex("\\s*[·•]\\s*"), ", ") ?: destination
                val who = if (guests != null && result.guestsApplied) " for ${guests.label()}" else ""
                val top = result.topResult?.let { " The first result is $it." } ?: ""
                sendReply("$found in $where$who.$top${warnings(wanted, result.datesApplied, guests, result.guestsApplied)}")
            }
            else -> bookingFailure(result, destination)
        }
    }

    private fun stayLabel(checkIn: StayDate?, checkOut: StayDate?): String? =
        checkIn?.let { "${it.label()} – ${(checkOut ?: it.plusDays(1)).label()}" }

    /** " I couldn't set …" for whatever was asked for but not applied in the app. */
    private fun warnings(wanted: String?, datesApplied: Boolean, guests: HotelQuery.Guests?, guestsApplied: Boolean): String =
        buildString {
            if (!datesApplied && wanted != null) append(" I couldn't set $wanted, so please check the dates.")
            if (!guestsApplied && guests != null) append(" I couldn't set ${guests.label()}, so please check the guests.")
        }

    /** Opens [hotel], selects a room and taps Reserve; the user finishes on the guest-details form. */
    private suspend fun reserveHotel(
        pkg: String,
        hotel: String,
        destination: String,
        checkIn: StayDate?,
        checkOut: StayDate?,
        guests: HotelQuery.Guests?,
        room: String?
    ) {
        val wanted = stayLabel(checkIn, checkOut)
        val details = listOfNotNull(wanted, guests?.label()).joinToString(", ")
        note("Booking ${room?.let { "a $it at " } ?: ""}$hotel on Booking.com${if (details.isNotEmpty()) " ($details)" else ""}.")
        when (val result = bookingAuto.reserve(pkg, hotel, destination, checkIn, checkOut, guests, room)) {
            is BookingAutomation.Result.Reserved -> {
                val what = result.room ?: "a room"
                val dates = result.dates?.let { " for $it" } ?: ""
                val who = if (guests != null && result.guestsApplied) " for ${guests.label()}" else ""
                val price = result.price?.let { " ($it)" } ?: ""
                val next = if (result.formShown) {
                    "It isn't booked yet: fill in your details on the form and complete the booking yourself."
                } else {
                    "Please check the screen and complete the booking yourself."
                }
                sendReply(
                    "I picked $what at ${result.hotel}$dates$who$price and tapped Reserve. $next" +
                        warnings(wanted, result.datesApplied, guests, result.guestsApplied)
                )
            }
            is BookingAutomation.Result.RoomNotOffered -> {
                val forWhom = guests?.let { " for ${it.label()}" } ?: ""
                val options = if (result.available.isEmpty()) "" else " The rooms available are: ${result.available.joinToString("; ")}."
                ask(
                    "${result.hotel} doesn't have a ${result.room}$forWhom, so I haven't reserved anything.$options " +
                        "Pick one on the screen, or ask me again with the room you want."
                )
            }
            is BookingAutomation.Result.NoRooms ->
                fail("I opened ${result.hotel} but couldn't select a room. It may be sold out for those dates; please pick one on screen.")
            BookingAutomation.Result.HotelNotFound -> fail("Booking.com didn't find a hotel called “$hotel”.")
            else -> bookingFailure(result, hotel)
        }
    }

    private fun bookingFailure(result: BookingAutomation.Result, what: String) = when (result) {
        BookingAutomation.Result.SearchBoxNotFound -> fail("I couldn't get to the Booking.com search screen.")
        BookingAutomation.Result.DestinationNotFound -> fail("Booking.com didn't suggest anything for “$what”.")
        BookingAutomation.Result.HotelNotFound -> fail("Booking.com didn't find a hotel called “$what”.")
        else -> fail("I set up the search for $what but the results didn't load.")
    }

    private fun findCurrencyTextInSubtree(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()

        if (!text.isNullOrEmpty() && text.startsWith("$")) {
            return text
        }

        for (i in 0 until node.childCount) {
            val found = findCurrencyTextInSubtree(node.getChild(i) ?: continue)
            if (found != null) return found
        }

        return null
    }

    private fun sendThroatsBroadcast(action: String, extras: Map<String, String>) {
        val intent = Intent(action).setPackage(THROATS_PKG)
        extras.forEach { (key, value) ->
            intent.putExtra(key, value)
        }
        sendBroadcast(intent)
    }

    /** Looks for a post UUID in the foreground app's windows (never ZeroUI's own transcript). */
    private fun findUuidOnScreen(): String? {
        val finder = automationEngine.finder
        for (root in finder.roots()) {
            for (node in finder.flatten(root)) {
                val text = node.text?.toString()
                    ?: node.contentDescription?.toString()
                    ?: continue
                val matcher = UUID_PATTERN.matcher(text)
                if (matcher.find()) return matcher.group()
            }
        }
        return null
    }

    /**
     * Progress worth seeing in the chat and on the indicator, but not read aloud: speech queued
     * behind it would lag behind what ZeroUI is actually doing.
     */
    private fun note(text: String) {
        Log.d("NLPControl", "Progress: $text")
        statusIndicator?.updateDetail(text)
        CommandBridge.postReply(text)
    }

    private fun sendReply(text: String) {
        Log.d("NLPControl", "Sending reply: $text")
        lastReply = text
        // Spoken here rather than by the UI so errors and questions are heard while another app is in front.
        Speaker.speak(text, interrupt = false)
        CommandBridge.postReply(text)
    }

    /** Replies and marks the current command as failed (shown as an error on the indicator). */
    private fun fail(text: String) {
        sendReply(text)
        outcome = Outcome.FAILED
    }

    /** Replies with a follow-up question; the command needs more details from the user. */
    private fun ask(text: String) {
        sendReply(text)
        if (outcome != Outcome.FAILED) outcome = Outcome.NEEDS_INPUT
    }

    private fun openAppByName(name: String): Boolean = launchAppByName(name) != null

    /** Launches the app best matching [name] and returns its package, or null if none could be started. */
    private fun launchAppByName(name: String): String? {
        return launchPackage(findAppByName(name) ?: return null)
    }

    private fun launchPackage(pkg: String): String? {
        val launchIntent = packageManager.getLaunchIntentForPackage(pkg) ?: return null
        // CLEAR_TASK drops whatever screen the app was left on so it starts from its home page
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return if (safeStartActivity(launchIntent)) pkg else null
    }

    /**
     * Package of the launchable app that best matches [name], or null. Only apps with a launcher
     * icon are candidates, so "Google Play" can't stop at the iconless "Google Play services".
     */
    private fun findAppByName(name: String): String? {
        if (name.isBlank()) return null
        val match = AppMatcher.best(name, launcherApps())
        Log.d("NLPControl", "App \"$name\" -> ${match?.let { "${it.label} (${it.packageName})" } ?: "no match"}")
        return match?.packageName
    }

    private fun launcherApps(): List<InstalledApp> {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(launcher, 0)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName to it.label }
    }

    private fun safeStartActivity(intent: Intent): Boolean {
        return try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.e("NLPControl", "Failed to start activity: ${intent.data}", e)
            false
        }
    }

    private fun toggleFlashlight(enable: Boolean): Boolean {
        return try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.find {
                cm.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: cm.cameraIdList[0]

            cm.setTorchMode(id, enable)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startNavigation(dest: String, mode: String) {
        val uri = Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$mode")
        val mapsIntent = Intent(Intent.ACTION_VIEW, uri)
            .setPackage("com.google.android.apps.maps")

        if (!safeStartActivity(mapsIntent)) {
            val geoIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=${Uri.encode(dest)}")
            )
            safeStartActivity(geoIntent)
        }
    }

    private fun setVolume(p: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (p / 100.0 * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(),
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun adjustVolume(inc: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (inc) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
            AudioManager.FLAG_SHOW_UI
        )
    }

    private fun canWriteSettings(): Boolean = Settings.System.canWrite(this)

    private fun adjustBrightness(p: Int) =
        Settings.System.putInt(
            contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            (p * 2.55).toInt().coerceIn(0, 255)
        )

    private fun adjustBrightnessRelative(inc: Boolean) {
        try {
            val cur = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS
            )

            val p = if (inc) {
                ((cur / 2.55) + 20).toInt().coerceAtMost(100)
            } else {
                ((cur / 2.55) - 20).toInt().coerceAtLeast(0)
            }

            adjustBrightness(p)
        } catch (e: Exception) {
        }
    }

    /**
     * Clicks the on-screen element best matching the first of [texts] that appears (text or
     * content description). Searches [packageName]'s windows, or any app except ZeroUI when null.
     */
    private suspend fun performClickOnText(
        vararg texts: String,
        packageName: String? = null,
        timeoutMs: Long = 2000
    ): Boolean = automationEngine.clickAny(texts.map { Selector(text = it) }, timeoutMs, packageName)

    /**
     * Brings up the voice dialog and starts listening. SINGLE_TOP hands an open dialog the intent
     * (MainActivity.onNewIntent) rather than CLEAR_TOP destroying and recreating it.
     */
    private fun voiceDialogIntent(listen: Boolean = true) = Intent(this, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        .putExtra(MainActivity.EXTRA_START_VOICE, listen)

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        CommandBridge.detachService(this)
        if (pillSpeechHolder.isInitialized()) pillSpeech.destroy()
        statusIndicator?.destroy()
        floatingButton?.destroy()
    }
}