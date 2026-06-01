package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.widget.Button
import kotlinx.coroutines.*
import java.util.regex.Pattern

class MyAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingButton: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null

    companion object {
        const val ACTION_COMMAND = "com.example.myapplication.COMMAND"
        const val ACTION_REPLY = "com.example.myapplication.REPLY"
        const val EXTRA_TEXT = "command_text"
        const val EXTRA_REPLY = "reply_text"

        // Throats API Integration
        private const val THROATS_PKG = "com.example.threadssim"
        private const val ACTION_THROATS_POST = "com.example.threadssim.ACTION_CREATE_POST"
        private const val ACTION_THROATS_REPOST = "com.example.threadssim.ACTION_REPOST"
        private const val ACTION_THROATS_COMMENT = "com.example.threadssim.ACTION_CREATE_COMMENT"
        
        // MockFinance Integration
        private const val MOCKFINANCE_PKG = "com.example.mockfinance"
        private const val URI_DASHBOARD = "mockfinance://dashboard"
        private const val URI_TRANSFER = "mockfinance://transfer"
        private const val URI_HISTORY = "mockfinance://history"
        private const val URI_TOP_UP = "mockfinance://topup"

        // FoodGorilla Integration
        private const val FOODGORILLA_PKG = "com.example.foodgorilla"
        private const val URI_FG_RESTAURANTS = "foodgorilla://app/restaurants"
        private const val URI_FG_CART = "foodgorilla://app/cart"
        private const val URI_FG_CHECKOUT = "foodgorilla://app/checkout"
        private const val URI_FG_BUY_NOW = "foodgorilla://api/buy_now"
        
        private val UUID_PATTERN = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_COMMAND) {
                val command = intent.getStringExtra(EXTRA_TEXT) ?: return
                Log.d("NLPControl", "Service starting chain: $command")
                currentJob?.cancel()
                currentJob = serviceScope.launch { executeCommandChain(command) }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onServiceConnected() {
        super.onServiceConnected()
        val filter = IntentFilter(ACTION_COMMAND)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(commandReceiver, filter)
        }
        setupFloatingButton()
        Log.d("NLPControl", "Service connected and ready")
    }

    private suspend fun executeCommandChain(command: String) {
        val cleanCommand = command.replace(",", "").replace(".", "").replace("?", "")
        val individualCommands = cleanCommand.split(Regex(" then | and | next ", RegexOption.IGNORE_CASE))
        
        for (cmd in individualCommands) {
            val trimmed = cmd.trim()
            if (trimmed.isNotEmpty()) {
                processSingleCommand(trimmed)
                delay(4000) 
            }
        }
    }

    private suspend fun processSingleCommand(command: String) {
        Log.d("NLPControl", "Processing: $command")
        val lower = command.lowercase()
        when {
            // MockFinance: Check Balance
            lower.contains("balance") && (lower.contains("finance") || lower.contains("mock")) -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(URI_DASHBOARD)).setPackage(MOCKFINANCE_PKG)
                if (safeStartActivity(intent) || openAppByName("MockFinance")) {
                    delay(2500) // Give it more time to load
                    val balance = findNodeByContentDescription("Balance Amount Value")?.text?.toString()
                    if (balance != null) sendReply("Your balance is $balance.")
                    else sendReply("I opened MockFinance but couldn't find the balance on screen.")
                } else {
                    sendReply("I couldn't find the MockFinance app on your device.")
                }
            }

            // MockFinance: Transfer
            lower.contains("transfer") && lower.contains("to") -> {
                val amount = extractNumber(command)?.toString() ?: ""
                val recipient = extractTargetAfter(command, listOf("to")).split(" ").firstOrNull() ?: ""
                if (amount.isNotEmpty() && recipient.isNotEmpty()) {
                    val uri = Uri.parse("$URI_TRANSFER?amount=$amount&recipient=$recipient")
                    val intent = Intent(Intent.ACTION_VIEW, uri).setPackage(MOCKFINANCE_PKG)
                    
                    if (safeStartActivity(intent)) {
                        sendReply("Initiating transfer of $amount to $recipient.")
                        delay(4000)
                        if (findNodeByText("Success") != null) sendReply("Transfer completed successfully!")
                    } else {
                        // Deep link failed, try manual navigation
                        if (openAppByName("MockFinance")) {
                            delay(2500)
                            if (performClickOnText("Transfer") || performClickOnText("Transfer Button")) {
                                delay(1500)
                                sendReply("I've opened the transfer screen in MockFinance. Please confirm the details.")
                            } else {
                                sendReply("I couldn't start the transfer. Is the MockFinance app up to date?")
                            }
                        } else {
                            sendReply("I couldn't find the MockFinance app.")
                        }
                    }
                } else {
                    sendReply("I need both an amount and a recipient to make a transfer.")
                }
            }

            // MockFinance: History & Top up
            lower.contains("history") -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(URI_HISTORY)).setPackage(MOCKFINANCE_PKG)
                if (!safeStartActivity(intent)) {
                    if (openAppByName("MockFinance")) {
                        delay(2500)
                        if (performClickOnText("History") || performClickOnText("History Button") || performClickOnText("Transactions")) {
                            sendReply("Showing your transaction history.")
                        } else {
                            sendReply("I couldn't find the history button in MockFinance.")
                        }
                    } else {
                        sendReply("MockFinance is not installed.")
                    }
                } else {
                    sendReply("Showing your transaction history.")
                }
            }
            lower.contains("top up") || lower.contains("refill") || lower.contains("top-up") -> {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(URI_TOP_UP)).setPackage(MOCKFINANCE_PKG)
                if (!safeStartActivity(intent)) {
                    if (openAppByName("MockFinance")) {
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
            (lower.contains("foodgorilla") || lower.contains("food gorilla")) && (lower.contains("search") || lower.contains("find")) -> {
                val query = extractTargetAfter(command, listOf("search for", "find", "search")).replace("on foodgorilla", "", true).replace("on food gorilla", "", true).trim()
                if (query.isNotEmpty()) {
                    val uri = Uri.parse("$URI_FG_RESTAURANTS?search=${Uri.encode(query)}")
                    if (safeStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(FOODGORILLA_PKG))) {
                        sendReply("Searching for $query on FoodGorilla.")
                    } else {
                        sendReply("I couldn't open FoodGorilla. Is it installed?")
                    }
                } else {
                    sendReply("What would you like to search for on FoodGorilla?")
                }
            }

            // FoodGorilla: Buy/Order
            (lower.contains("foodgorilla") || lower.contains("food gorilla")) && (lower.contains("buy") || lower.contains("order")) -> {
                val itemId = extractNumber(command)?.toString() ?: ""
                if (itemId.isNotEmpty()) {
                    val uri = Uri.parse("$URI_FG_BUY_NOW?itemId=$itemId&quantity=1")
                    if (safeStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(FOODGORILLA_PKG))) {
                        sendReply("Ordering item $itemId from FoodGorilla.")
                    } else {
                        sendReply("I couldn't complete the order on FoodGorilla.")
                    }
                } else {
                    val query = extractTargetAfter(command, listOf("order", "buy")).replace("from foodgorilla", "", true).replace("from food gorilla", "", true).trim()
                    if (query.isNotEmpty()) {
                        val uri = Uri.parse("$URI_FG_RESTAURANTS?search=${Uri.encode(query)}")
                        safeStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(FOODGORILLA_PKG))
                        sendReply("I'll search for $query on FoodGorilla so you can choose.")
                    } else {
                        sendReply("What would you like to order?")
                    }
                }
            }

            // FoodGorilla: Cart/Checkout
            (lower.contains("foodgorilla") || lower.contains("food gorilla")) && lower.contains("cart") -> {
                if (safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URI_FG_CART)).setPackage(FOODGORILLA_PKG))) {
                    sendReply("Opening your FoodGorilla cart.")
                } else {
                    sendReply("I couldn't open the FoodGorilla cart.")
                }
            }
            (lower.contains("foodgorilla") || lower.contains("food gorilla")) && lower.contains("checkout") -> {
                if (safeStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse(URI_FG_CHECKOUT)).setPackage(FOODGORILLA_PKG))) {
                    sendReply("Taking you to FoodGorilla checkout.")
                } else {
                    sendReply("I couldn't open the FoodGorilla checkout.")
                }
            }

            // FoodGorilla: Restaurant Menu
            (lower.contains("foodgorilla") || lower.contains("food gorilla")) && lower.contains("menu") -> {
                val restaurantId = extractTargetAfter(command, listOf("menu for", "menu of", "menu")).replace("on foodgorilla", "", true).replace("on food gorilla", "", true).trim()
                if (restaurantId.isNotEmpty()) {
                    val uri = Uri.parse("foodgorilla://app/restaurant/$restaurantId")
                    if (safeStartActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(FOODGORILLA_PKG))) {
                        sendReply("Opening the menu for $restaurantId on FoodGorilla.")
                    } else {
                        sendReply("I couldn't find that restaurant menu.")
                    }
                } else {
                    sendReply("Which restaurant's menu would you like to see?")
                }
            }

            // Throats: Create Post
            lower.contains("post") && (lower.contains("throats") || lower.contains("throat")) -> {
                val content = extractTargetAfter(command, listOf("post", "throat", "throats")).replace("on throats", "", true).replace("to throats", "", true).trim()
                if (content.isNotEmpty()) {
                    sendThroatsBroadcast(ACTION_THROATS_POST, mapOf("content" to content, "author" to "VoiceAssistant"))
                    sendReply("I've posted that to Throats for you.")
                } else {
                    sendReply("What would you like me to post?")
                }
            }

            // Throats: Repost
            lower.contains("repost") -> {
                var postId = extractUuid(command) ?: findUuidOnScreen()
                if (postId != null) {
                    sendThroatsBroadcast(ACTION_THROATS_REPOST, mapOf("post_id" to postId, "author" to "VoiceAssistant"))
                    sendReply("Okay, I've reposted that.")
                } else {
                    sendReply("I couldn't find a post ID to repost.")
                }
            }

            // Throats: Comment
            lower.contains("comment") || lower.contains("reply") -> {
                val postId = extractUuid(command) ?: findUuidOnScreen()
                val content = extractTargetAfter(command, listOf("comment", "reply", "on", "to")).split("on").first().trim()
                
                if (postId != null && content.isNotEmpty()) {
                    sendThroatsBroadcast(ACTION_THROATS_COMMENT, mapOf("parent_post_id" to postId, "content" to content, "author" to "VoiceAssistant"))
                    sendReply("Comment posted successfully.")
                } else {
                    sendReply("I need a post and content to comment.")
                }
            }

            // Flashlight
            lower.contains("flashlight") || lower.contains("flash light") || lower.contains("torch") || lower.contains("light") || lower.contains("dark") -> {
                val enable = !lower.contains("off") && !lower.contains("stop")
                if (toggleFlashlight(enable)) {
                    sendReply(if (enable) "I've turned the light on for you." else "Flashlight is now off.")
                } else {
                    sendReply("I couldn't toggle the flashlight. Make sure I have camera permissions.")
                }
            }
            
            // Battery
            lower.contains("battery") || lower.contains("percentage") -> {
                val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                sendReply("Your battery is at $level%.")
            }

            // Navigation
            lower.contains("navigate") || lower.contains("take me to") -> {
                val dest = extractTargetAfter(command, listOf("navigate to", "take me to", "navigate"))
                if (dest.isNotEmpty()) {
                    startNavigation(dest, if (lower.contains("bus")) "r" else "d")
                    sendReply("Opening navigation to $dest.")
                }
            }

            // App Launching
            lower.contains("open") || lower.contains("launch") -> {
                val app = extractTargetAfter(command, listOf("open", "launch"))
                if (app.isNotEmpty()) {
                    if (openAppByName(app)) sendReply("Opening $app.")
                    else sendReply("I couldn't find an app named $app.")
                }
            }

            else -> {
                if (performClickOnText(command)) sendReply("Clicked on $command.")
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

    private fun extractUuid(text: String): String? {
        val matcher = UUID_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group() else null
    }

    private fun sendReply(text: String) {
        Log.d("NLPControl", "Sending reply: $text")
        val intent = Intent(ACTION_REPLY).setPackage(packageName).putExtra(EXTRA_REPLY, text)
        sendBroadcast(intent)
    }

    private fun openAppByName(name: String): Boolean {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val cleanName = name.lowercase().trim()
        
        // Try searching by label
        var target = apps.find { pm.getApplicationLabel(it).toString().lowercase() == cleanName }
        if (target == null) target = apps.find { pm.getApplicationLabel(it).toString().lowercase().contains(cleanName) }
        
        // Fallback: If searching for MockFinance, try searching by package name directly
        if (target == null && cleanName.contains("mock")) {
            target = apps.find { it.packageName == MOCKFINANCE_PKG }
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

    private fun extractNumber(s: String): Int? = Regex("\\d+").find(s)?.value?.toIntOrNull()

    private fun extractTargetAfter(full: String, keywords: List<String>): String {
        val lower = full.lowercase()
        for (kw in keywords) {
            val idx = lower.indexOf(kw)
            if (idx != -1) return full.substring(idx + kw.length).trim().removePrefix("me to ").removePrefix("to ").trim()
        }
        return full.trim()
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
            windowManager?.addView(floatingButton, params)
        } catch (e: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        try { unregisterReceiver(commandReceiver) } catch (e: Exception) {}
        floatingButton?.let { windowManager?.removeView(it) }
    }
}
