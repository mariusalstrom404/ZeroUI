package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.widget.Button
import com.example.myapplication.automation.AutomationEngine
import com.example.myapplication.automation.FoodGorillaAutomation
import com.example.myapplication.ipc.CommandBridge
import com.example.myapplication.nlp.AppIntent
import com.example.myapplication.nlp.IntentParser
import com.example.myapplication.nlp.ml.ParserFactory
import kotlinx.coroutines.*
import java.util.regex.Pattern

class MyAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingButton: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null
    private val parser: IntentParser by lazy { ParserFactory.create(this) }
    private var lastRawCommand: String? = null

    private val automationEngine by lazy { AutomationEngine(this) }
    private val foodGorillaAuto by lazy { FoodGorillaAutomation(automationEngine) }

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
        
        private val UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event?.let {
            Log.e("ZeroUIAutomation", "event=${it.eventType}, package=${it.packageName}, class=${it.className}")
        }
    }
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        CommandBridge.attachService(this)
        setupFloatingButton()
        Log.e("ZeroUIAutomation", "ACCESSIBILITY SERVICE CONNECTED")
        Log.d("NLPControl", "Service connected and ready")
    }

    /** Entry point invoked by [CommandBridge] when the UI submits a command. */
    fun submitCommand(command: String) {
        Log.d("NLPControl", "Service starting chain: $command")
        currentJob?.cancel()
        currentJob = serviceScope.launch { executeCommandChain(command) }
    }

    private suspend fun executeCommandChain(command: String) {
        CommandBridge.postStatus(CommandBridge.Status.STARTED)
        try {
            val cleanCommand = command.replace(",", "").replace(".", "").replace("?", "")
            val individualCommands = cleanCommand.split(Regex(" then | and | next ", RegexOption.IGNORE_CASE))

            for (cmd in individualCommands) {
                val trimmed = cmd.trim()
                if (trimmed.isNotEmpty()) {
                    processSingleCommand(trimmed)
                    delay(4000)
                }
            }
        } finally {
            CommandBridge.postStatus(CommandBridge.Status.FINISHED)
        }
    }

    private suspend fun processSingleCommand(command: String) {
        Log.d("NLPControl", "Processing: $command")
        val intent = parser.parse(command)
        if (intent !is AppIntent.RepeatLast) {
            lastRawCommand = command
        }
        dispatch(intent, command)
    }

    /** Executes a parsed [AppIntent] using the accessibility/system capabilities. */
    private suspend fun dispatch(intent: AppIntent, rawCommand: String) {
        when (intent) {
            // NCCUbank: Check Balance
            AppIntent.CheckBalance -> {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(URI_DASHBOARD)).setPackage(NCCUBANK_PKG)
                if (safeStartActivity(viewIntent) || openAppByName("NCCUbank")) {
                    delay(2500)
                    val balanceCard = findNodeByContentDescription("Wallet Balance Display")
                    val balance = if (balanceCard != null) findCurrencyTextInSubtree(balanceCard) else null
                    if (balance != null) sendReply("Your balance is $balance.")
                    else sendReply("I opened NCCUbank but couldn't find the balance on screen.")
                } else {
                    sendReply("I couldn't find the NCCUbank app on your device.")
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
                        sendReply("Initiating transfer of $amount to $recipient.")
                        delay(4000)
                        if (findNodeByText("Success") != null) sendReply("Transfer completed successfully!")
                    } else {
                        // Deep link failed, try manual navigation
                        if (openAppByName("NCCUbank")) {
                            delay(2500)
                            if (performClickOnText("Transfer") || performClickOnText("Transfer Button")) {
                                delay(1500)
                                sendReply("I've opened the transfer screen in NCCUbank. Please confirm the details.")
                            } else {
                                sendReply("I couldn't start the transfer. Is the NCCUbank app up to date?")
                            }
                        } else {
                            sendReply("I couldn't find the NCCUbank app.")
                        }
                    }
                } else {
                    sendReply("I need both an amount and a recipient to make a transfer.")
                }
            }

            // NCCUbank: History
            AppIntent.TransactionHistory -> {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(URI_HISTORY)).setPackage(NCCUBANK_PKG)
                if (!safeStartActivity(viewIntent)) {
                    if (openAppByName("NCCUbank")) {
                        delay(2500)
                        if (performClickOnText("History") || performClickOnText("History Button") || performClickOnText("Transactions")) {
                            sendReply("Showing your transaction history.")
                        } else {
                            sendReply("I couldn't find the history button in NCCUbank.")
                        }
                    } else {
                        sendReply("NCCUbank is not installed.")
                    }
                } else {
                    sendReply("Showing your transaction history.")
                }
            }

            // NCCUbank: Top up
            AppIntent.TopUp -> {
                val viewIntent = Intent(Intent.ACTION_VIEW, Uri.parse(URI_TOP_UP)).setPackage(NCCUBANK_PKG)
                if (!safeStartActivity(viewIntent)) {
                    if (openAppByName("NCCUbank")) {
                        delay(2500)
                        if (performClickOnText("Top-up") || performClickOnText("Top Up") || performClickOnText("Top-up Button") || performClickOnText("Deposit")) {
                            sendReply("Opening the top up screen.")
                        } else {
                            sendReply("I couldn't find the top up button.")
                        }
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
                        sendReply("I couldn't open FoodGorilla. Is it installed?")
                    }
                } else {
                    sendReply("What would you like to search for on FoodGorilla?")
                }
            }

            // FoodGorilla: Buy/Order
            is AppIntent.FoodOrder -> {
                val query = intent.query ?: ""
                if (query.isNotEmpty()) {
                    // Optimized extraction for "item from restaurant"
                    val fromIndex = query.indexOf(" from ", ignoreCase = true)
                    val (item, restaurant) = if (fromIndex != -1) {
                        query.substring(0, fromIndex).trim() to query.substring(fromIndex + 6).trim()
                    } else {
                        // Fallback: use query as item, default to "McDonald's" or vice versa
                        query to "MidOnald's"
                    }
                    
                    sendReply("Executing FoodGorilla automation for $item at $restaurant.")
                    Log.e("ZeroUIAutomation", "FOOD AUTOMATION START: $item at $restaurant")
                    val success = foodGorillaAuto.runOrderWorkflow(restaurant, item)
                    if (success) {
                        sendReply("FoodGorilla automation reached checkout successfully.")
                    } else {
                        sendReply("FoodGorilla automation failed. Check logs for details.")
                    }
                } else if (intent.itemId != null) {
                    val uri = Uri.parse("$URI_FG_BUY_NOW?itemId=${intent.itemId}&quantity=1")
                    if (safeStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(FOODGORILLA_PKG))) {
                        sendReply("Ordering item ${intent.itemId} from FoodGorilla.")
                    } else {
                        sendReply("I couldn't complete the order on FoodGorilla.")
                    }
                } else {
                    sendReply("What would you like to order?")
                }
            }

            // FoodGorilla: Cart / Checkout
            AppIntent.FoodCart -> {
                if (safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URI_FG_CART)).setPackage(FOODGORILLA_PKG))) {
                    sendReply("Opening your FoodGorilla cart.")
                } else {
                    sendReply("I couldn't open the FoodGorilla cart.")
                }
            }
            AppIntent.FoodCheckout -> {
                if (safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URI_FG_CHECKOUT)).setPackage(FOODGORILLA_PKG))) {
                    sendReply("Taking you to FoodGorilla checkout.")
                } else {
                    sendReply("I couldn't open the FoodGorilla checkout.")
                }
            }

            // FoodGorilla: Restaurant Menu
            is AppIntent.FoodMenu -> {
                if (intent.restaurant.isNotEmpty()) {
                    val uri = Uri.parse("foodgorilla://app/restaurant/${intent.restaurant}")
                    if (safeStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(FOODGORILLA_PKG))) {
                        sendReply("Opening the menu for ${intent.restaurant} on FoodGorilla.")
                    } else {
                        sendReply("I couldn't find that restaurant menu.")
                    }
                } else {
                    sendReply("Which restaurant's menu would you like to see?")
                }
            }

            // Throats: Create Post
            is AppIntent.ThroatsPost -> {
                if (intent.content.isNotEmpty()) {
                    sendThroatsBroadcast(ACTION_THROATS_POST, mapOf("content" to intent.content, "author" to "VoiceAssistant"))
                    sendReply("I've posted that to Throats for you.")
                } else {
                    sendReply("What would you like me to post?")
                }
            }

            // Throats: Repost
            is AppIntent.ThroatsRepost -> {
                val postId = intent.postId ?: findUuidOnScreen()
                if (postId != null) {
                    sendThroatsBroadcast(ACTION_THROATS_REPOST, mapOf("post_id" to postId, "author" to "VoiceAssistant"))
                    sendReply("Okay, I've reposted that.")
                } else {
                    sendReply("I couldn't find a post ID to repost.")
                }
            }

            // Throats: Comment
            is AppIntent.ThroatsComment -> {
                val postId = intent.postId ?: findUuidOnScreen()
                if (postId != null && intent.content.isNotEmpty()) {
                    sendThroatsBroadcast(ACTION_THROATS_COMMENT, mapOf("parent_post_id" to postId, "content" to intent.content, "author" to "VoiceAssistant"))
                    sendReply("Comment posted successfully.")
                } else {
                    sendReply("I need a post and content to comment.")
                }
            }

            // Flashlight
            is AppIntent.Flashlight -> {
                if (toggleFlashlight(intent.enable)) {
                    sendReply(if (intent.enable) "I've turned the light on for you." else "Flashlight is now off.")
                } else {
                    sendReply("I couldn't toggle the flashlight. Make sure I have camera permissions.")
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
                    setVolume(level); sendReply("Volume set to $level%.")
                }
                AppIntent.Change.UP -> { adjustVolume(true); sendReply("Turning the volume up.") }
                AppIntent.Change.DOWN -> { adjustVolume(false); sendReply("Turning the volume down.") }
            }

            // Brightness
            is AppIntent.Brightness -> {
                if (!canWriteSettings()) {
                    sendReply("I need permission to change system settings. Please grant it in Settings.")
                } else when (intent.change) {
                    AppIntent.Change.SET -> {
                        val level = (intent.level ?: 50).coerceIn(0, 100)
                        adjustBrightness(level); sendReply("Brightness set to $level%.")
                    }
                    AppIntent.Change.UP -> { adjustBrightnessRelative(true); sendReply("Increasing the brightness.") }
                    AppIntent.Change.DOWN -> { adjustBrightnessRelative(false); sendReply("Lowering the brightness.") }
                }
            }

            // Conversation Management
            AppIntent.Greeting -> {
                sendReply("Hi! I'm ZeroUI. You can use me to control your device, navigate, handle banking with NCCUbank, order food, or post on social media. Try saying 'check my balance', 'take a screenshot', or 'repeat'. How can I help?")
            }
            AppIntent.SaveHistory -> {
                sendReply("Conversation history has been saved.")
            }
            AppIntent.ClearHistory -> {
                sendReply("I've cleared the conversation history.")
            }
            AppIntent.RepeatLast -> {
                val last = lastRawCommand
                if (last != null) {
                    sendReply("Repeating your last command: $last")
                    delay(1000)
                    processSingleCommand(last)
                } else {
                    sendReply("I don't have a previous command to repeat yet.")
                }
            }
            AppIntent.TakeScreenshot -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                    sendReply("Taking a screenshot for you.")
                } else {
                    sendReply("Screenshot requires Android 11 or higher.")
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
                    sendReply(if (takePhoto) "Opening camera to take a photo." else "Opening camera.")
                } else {
                    sendReply("I couldn't open the camera app.")
                }
            }

            // Navigation
            is AppIntent.Navigate -> {
                if (!intent.destination.isNullOrEmpty()) {
                    startNavigation(intent.destination, if (intent.mode == "bus") "r" else "d")
                    sendReply("Opening navigation to ${intent.destination}.")
                } else {
                    sendReply("Where would you like to navigate to?")
                }
            }

            // App Launching
            is AppIntent.OpenApp -> {
                if (intent.name.isNotEmpty()) {
                    if (openAppByName(intent.name)) sendReply("Opening ${intent.name}.")
                    else sendReply("I couldn't find an app named ${intent.name}.")
                } else {
                    sendReply("Which app should I open?")
                }
            }

            // Fallback: best-effort click on whatever was said
            is AppIntent.ClickText -> {
                if (performClickOnText(intent.text)) sendReply("Clicked on ${intent.text}.")
            }

            AppIntent.Unknown -> {
                if (performClickOnText(rawCommand)) sendReply("Clicked on $rawCommand.")
            }
        }
    }

    private fun findNodeByContentDescription(desc: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return searchForNode(root) { it.contentDescription?.toString()?.equals(desc, true) == true }
    }

    private fun findNodeByText(text: String): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return searchForNode(root) { it.text?.toString()?.equals(text, true) == true }
    }

    private fun searchForNode(node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchForNode(child, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun findCurrencyTextInSubtree(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString()
        if (!text.isNullOrEmpty() && text.startsWith("$")) return text
        for (i in 0 until node.childCount) {
            val found = findCurrencyTextInSubtree(node.getChild(i) ?: continue)
            if (found != null) return found
        }
        return null
    }

    private fun sendThroatsBroadcast(action: String, extras: Map<String, String>) {
        val intent = Intent(action).setPackage(THROATS_PKG)
        extras.forEach { (key, value) -> intent.putExtra(key, value) }
        sendBroadcast(intent)
    }

    private fun findUuidOnScreen(): String? {
        val root = rootInActiveWindow ?: return null
        return searchForUuid(root)
    }

    private fun searchForUuid(node: AccessibilityNodeInfo): String? {
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        val matcher = UUID_PATTERN.matcher(text)
        if (matcher.find()) return matcher.group()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = searchForUuid(child)
            if (found != null) return found
        }
        return null
    }

    private fun sendReply(text: String) {
        Log.d("NLPControl", "Sending reply: $text")
        CommandBridge.postReply(text)
    }

    private fun openAppByName(name: String): Boolean {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val cleanName = name.lowercase().trim()
        
        // Try searching by label
        var target = apps.find { pm.getApplicationLabel(it).toString().lowercase() == cleanName }
        if (target == null) target = apps.find { pm.getApplicationLabel(it).toString().lowercase().contains(cleanName) }
        
        // Fallback: If searching for NCCUbank, try searching by package name directly
        if (target == null && cleanName.contains("nccu")) {
            target = apps.find { it.packageName == NCCUBANK_PKG }
        }

        return target?.let {
            pm.getLaunchIntentForPackage(it.packageName)?.let { intent ->
                safeStartActivity(intent)
            } ?: false
        } ?: false
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
            val id = cm.cameraIdList.find { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: cm.cameraIdList[0]
            cm.setTorchMode(id, enable)
            true
        } catch (e: Exception) { false }
    }

    private fun startNavigation(dest: String, mode: String) {
        val uri = Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$mode")
        val mapsIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        if (!safeStartActivity(mapsIntent)) {
            val geoIntent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}"))
            safeStartActivity(geoIntent)
        }
    }

    private fun setVolume(p: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (p / 100.0 * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), AudioManager.FLAG_SHOW_UI)
    }

    private fun adjustVolume(inc: Boolean) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (inc) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    private fun canWriteSettings(): Boolean = Settings.System.canWrite(this)

    private fun adjustBrightness(p: Int) = Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (p * 2.55).toInt().coerceIn(0, 255))
    private fun adjustBrightnessRelative(inc: Boolean) { try { val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS); val p = if (inc) ((cur/2.55)+20).toInt().coerceAtMost(100) else ((cur/2.55)-20).toInt().coerceAtLeast(0); adjustBrightness(p) } catch (e: Exception) {} }

    private fun performClickOnText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        
        // 1. Try exact/contains match as provided
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (!nodes.isNullOrEmpty()) { for (n in nodes) if (attemptClick(n)) return true }
        if (deepSearchAndClick(root, text)) return true

        // 2. Try matching after normalizing (removing spaces/hyphens)
        val normalizedTarget = text.replace(" ", "").replace("-", "").lowercase()
        return fuzzySearchAndClick(root, normalizedTarget)
    }

    private fun fuzzySearchAndClick(node: AccessibilityNodeInfo, normalizedTarget: String): Boolean {
        val nodeText = (node.text?.toString() ?: node.contentDescription?.toString() ?: "").replace(" ", "").replace("-", "").lowercase()
        if (nodeText.contains(normalizedTarget) && normalizedTarget.isNotEmpty()) {
            if (attemptClick(node)) return true
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            if (fuzzySearchAndClick(child, normalizedTarget)) return true
        }
        return false
    }

    private fun deepSearchAndClick(node: AccessibilityNodeInfo, text: String): Boolean {
        if (node.text?.toString()?.contains(text, true) == true || node.contentDescription?.toString()?.contains(text, true) == true) if (attemptClick(node)) return true
        for (i in 0 until node.childCount) { val c = node.getChild(i) ?: continue; if (deepSearchAndClick(c, text)) return true }
        return false
    }

    private fun attemptClick(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        var p = node.parent; while (p != null) { if (p.isClickable) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p = p.parent }
        return false
    }

    private fun setupFloatingButton() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = 0; y = 200 }
            floatingButton = Button(this).apply {
                text = "🎤"
                alpha = 0.7f
                setOnTouchListener(object : View.OnTouchListener {
                    private var ix = 0; private var iy = 0; private var itx = 0f; private var ity = 0f
                    override fun onTouch(v: View, e: MotionEvent): Boolean {
                        when (e.action) {
                            MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; itx = e.rawX; ity = e.rawY; return true }
                            MotionEvent.ACTION_MOVE -> { params.x = ix + (itx - e.rawX).toInt(); params.y = iy + (e.rawY - ity).toInt(); windowManager?.updateViewLayout(floatingButton, params); return true }
                            MotionEvent.ACTION_UP -> {
                                if (Math.abs(e.rawX - itx) < 10 && Math.abs(e.rawY - ity) < 10) {
                                    startActivity(Intent(this@MyAccessibilityService, MainActivity::class.java).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                                        putExtra("start_voice", true)
                                    })
                                }
                                return true
                            }
                        }
                        return false
                    }
                })
            }
            // Keep the overlay clear of the status bar / display cutout when the
            // system draws edge-to-edge (Android 15+).
            floatingButton?.setOnApplyWindowInsetsListener { v, insets ->
                val top = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()).top
                } else 0
                if (params.y < top) { params.y = top + 16; windowManager?.updateViewLayout(v, params) }
                insets
            }
            windowManager?.addView(floatingButton, params)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        CommandBridge.detachService(this)
        floatingButton?.let { windowManager?.removeView(it) }
    }
}
