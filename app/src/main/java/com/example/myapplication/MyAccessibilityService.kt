package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothManager
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
        // Clean the command of common punctuation that confuses matching
        val cleanCommand = command.replace(",", "").replace(".", "").replace("?", "")
        val individualCommands = cleanCommand.split(Regex(" then | and | next ", RegexOption.IGNORE_CASE))
        
        Log.d("NLPControl", "Split into ${individualCommands.size} steps")
        
        for (cmd in individualCommands) {
            val trimmed = cmd.trim()
            if (trimmed.isNotEmpty()) {
                Log.d("NLPControl", "Executing step: $trimmed")
                processSingleCommand(trimmed)
                delay(4000) // 4s delay to allow apps/UI to load
            }
        }
    }

    private suspend fun processSingleCommand(command: String) {
        val lower = command.lowercase()
        when {
            // Flashlight (Now handles "flash light" as two words)
            lower.contains("flashlight") || lower.contains("flash light") || lower.contains("torch") || lower.contains("light") || lower.contains("dark") -> {
                val enable = !lower.contains("off") && !lower.contains("stop")
                if (toggleFlashlight(enable)) {
                    sendReply(if (enable) "I've turned the light on for you." else "Flashlight is now off.")
                } else {
                    sendReply("I couldn't access the flashlight. Check permissions.")
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

            // App Launching (Using the improved fuzzy search)
            lower.contains("open") || lower.contains("launch") -> {
                val app = extractTargetAfter(command, listOf("open", "launch"))
                if (app.isNotEmpty()) {
                    if (openAppByName(app)) sendReply("Opening $app for you.")
                    else sendReply("I couldn't find an app named $app.")
                }
            }

            // System Actions
            lower.contains("screenshot") -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
                    sendReply("Screenshot captured.")
                }
            }
            lower.contains("home") -> { performGlobalAction(GLOBAL_ACTION_HOME); sendReply("Going home.") }
            lower.contains("back") -> { performGlobalAction(GLOBAL_ACTION_BACK); sendReply("Okay, going back.") }
            
            // Volume & Brightness
            lower.contains("volume") -> {
                val p = extractNumber(lower)
                if (p != null) setVolume(p) else adjustVolume(lower.contains("up") || lower.contains("increase"))
                sendReply("Volume adjusted.")
            }
            lower.contains("brightness") -> {
                val p = extractNumber(lower)
                if (Settings.System.canWrite(this)) {
                    if (p != null) adjustBrightness(p) else adjustBrightnessRelative(lower.contains("up"))
                    sendReply("Brightness adjusted.")
                } else {
                    sendReply("I need permission to change brightness. Opening settings.")
                    startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }

            else -> {
                if (performClickOnText(command)) sendReply("Clicked on $command.")
            }
        }
    }

    private fun sendReply(text: String) {
        val intent = Intent(ACTION_REPLY).setPackage(packageName).putExtra(EXTRA_REPLY, text)
        sendBroadcast(intent)
    }

    private fun openAppByName(name: String): Boolean {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val cleanName = name.lowercase().trim()
        
        // Exact match
        var target = apps.find { pm.getApplicationLabel(it).toString().lowercase() == cleanName }
        // Partial match
        if (target == null) target = apps.find { pm.getApplicationLabel(it).toString().lowercase().contains(cleanName) }
        
        return target?.let {
            pm.getLaunchIntentForPackage(it.packageName)?.let { intent ->
                startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } ?: false
        } ?: false
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
        try { startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
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
        val nodes = root.findAccessibilityNodeInfosByText(text)
        if (!nodes.isNullOrEmpty()) { for (n in nodes) if (attemptClick(n)) return true }
        return deepSearchAndClick(root, text)
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
