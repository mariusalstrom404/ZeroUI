package com.example.myapplication

import android.accessibilityservice.AccessibilityService
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.PixelFormat
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
import android.widget.Toast
import kotlinx.coroutines.*

class MyAccessibilityService : AccessibilityService() {

    private var windowManager: WindowManager? = null
    private var floatingButton: View? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null

    companion object {
        const val ACTION_COMMAND = "com.example.myapplication.COMMAND"
        const val EXTRA_TEXT = "command_text"
    }

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_COMMAND) {
                val command = intent.getStringExtra(EXTRA_TEXT) ?: return
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
        showToast("NLP Service Ready")
    }

    private suspend fun executeCommandChain(command: String) {
        val individualCommands = command.split(Regex(" then | and | next ", RegexOption.IGNORE_CASE))
        for (cmd in individualCommands) {
            val trimmed = cmd.trim()
            if (trimmed.isNotEmpty()) {
                processSingleCommand(trimmed)
                delay(3000) 
            }
        }
    }

    private suspend fun processSingleCommand(command: String) {
        val lower = command.lowercase()
        Log.d("NLPControl", "Executing Purpose: $lower")

        when {
            // 1. Utilities (Flashlight)
            lower.contains("flashlight") || lower.contains("torch") || lower.contains("light") || lower.contains("dark") -> {
                val enable = !lower.contains("off") && !lower.contains("stop")
                toggleFlashlight(enable)
            }

            // 2. Battery
            lower.contains("battery") || lower.contains("percentage") || lower.contains("dying") -> {
                checkBattery()
            }

            // 3. Media Controls (Play/Pause/Skip)
            lower.contains("play") || lower.contains("pause") || lower.contains("resume") || lower.contains("stop") -> {
                toggleMedia()
            }
            lower.contains("next") || lower.contains("skip") -> {
                sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            }

            // 4. Connectivity
            lower.contains("wifi") || lower.contains("internet") || lower.contains("offline") -> {
                toggleWifi(!lower.contains("off") && !lower.contains("offline"))
            }
            lower.contains("bluetooth") -> {
                toggleBluetooth(!lower.contains("off"))
            }

            // 5. Existing Controls
            lower.contains("navigate") || lower.contains("take me to") -> startNavigation(extractTargetAfter(command, listOf("navigate to", "take me to")), if (lower.contains("bus")) "r" else "d")
            lower.contains("open") -> openAppByName(extractTargetAfter(command, listOf("open")))
            lower.contains("screenshot") -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
            lower.contains("volume") -> { val p = extractNumber(lower); if (p != null) setVolume(p) else adjustVolume(lower.contains("up")) }
            lower.contains("brightness") -> { val p = extractNumber(lower); if (p != null) adjustBrightness(p) else adjustBrightnessRelative(lower.contains("up")) }
            lower.contains("photo") || lower.contains("selfie") -> takePhoto(lower.contains("selfie"))
            lower.contains("home") -> performGlobalAction(GLOBAL_ACTION_HOME)
            lower.contains("back") -> performGlobalAction(GLOBAL_ACTION_BACK)
            lower.contains("click") -> performClickOnText(extractTargetAfter(command, listOf("click")))
            
            else -> showToast("Command recognized: $command")
        }
    }

    private fun toggleFlashlight(enable: Boolean) {
        try {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList[0]
            cameraManager.setTorchMode(cameraId, enable)
            showToast("Flashlight ${if (enable) "Enabled" else "Disabled"}")
        } catch (e: Exception) { showToast("Flashlight failed") }
    }

    private fun checkBattery() {
        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        showToast("Battery is at $level%")
    }

    private fun toggleMedia() {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
        am.dispatchMediaKeyEvent(event)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
        showToast("Toggled Media")
    }

    private fun sendMediaKey(keyCode: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    private fun openAppByName(name: String) {
        if (name.isEmpty()) return
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
        val target = apps.firstOrNull { pm.getApplicationLabel(it).toString().contains(name, true) }
        target?.let { 
            pm.getLaunchIntentForPackage(it.packageName)?.let { i -> 
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(i)
                showToast("Opening ${pm.getApplicationLabel(target)}")
            }
        } ?: showToast("App '$name' not found")
    }

    private fun startNavigation(dest: String, mode: String) {
        if (dest.isEmpty()) return
        val uri = Uri.parse("google.navigation:q=${Uri.encode(dest)}&mode=$mode")
        val i = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (e: Exception) { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(dest)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun setVolume(p: Int) { val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager; am.setStreamVolume(AudioManager.STREAM_MUSIC, (p / 100.0 * am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)).toInt(), AudioManager.FLAG_SHOW_UI) }
    private fun adjustVolume(inc: Boolean) = (getSystemService(Context.AUDIO_SERVICE) as AudioManager).adjustStreamVolume(AudioManager.STREAM_MUSIC, if (inc) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    private fun adjustBrightness(p: Int) { if (Settings.System.canWrite(this)) { Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, (p * 2.55).toInt().coerceIn(0, 255)); showToast("Brightness $p%") } else startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun adjustBrightnessRelative(inc: Boolean) { try { val cur = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS); val p = if (inc) ((cur/2.55)+20).toInt().coerceAtMost(100) else ((cur/2.55)-20).toInt().coerceAtLeast(0); adjustBrightness(p) } catch (e: Exception) {} }
    private fun toggleWifi(e: Boolean) { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else (getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled = e }
    private fun toggleBluetooth(e: Boolean) { val a = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter ?: return; if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) else if (e) a.enable() else a.disable() }
    private fun takePhoto(s: Boolean) { val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); if (s) i.putExtra("android.intent.extras.CAMERA_FACING", 1).putExtra("android.intent.extra.USE_FRONT_CAMERA", true); startActivity(i); serviceScope.launch { delay(4000); val shut = listOf("shutter", "take", "capture", "photo", "camera", "button"); for (kw in shut) if (performClickOnText(kw)) break } }
    private fun performClickOnText(t: String): Boolean { val r = rootInActiveWindow ?: return false; val ns = r.findAccessibilityNodeInfosByText(t); if (!ns.isNullOrEmpty()) { for (n in ns) if (attemptClick(n)) return true }; return deepSearchAndClick(r, t) }
    private fun deepSearchAndClick(n: AccessibilityNodeInfo, t: String): Boolean { if (n.text?.toString()?.contains(t, true) == true || n.contentDescription?.toString()?.contains(t, true) == true) if (attemptClick(n)) return true; for (i in 0 until n.childCount) { val c = n.getChild(i) ?: continue; if (deepSearchAndClick(c, t)) return true }; return false }
    private fun attemptClick(n: AccessibilityNodeInfo): Boolean { if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK); var p = n.parent; while (p != null) { if (p.isClickable) return p.performAction(AccessibilityNodeInfo.ACTION_CLICK); p = p.parent }; return false }
    private fun extractNumber(s: String): Int? = Regex("\\d+").find(s)?.value?.toIntOrNull()
    private fun extractTargetAfter(f: String, kws: List<String>): String { val l = f.lowercase(); for (kw in kws) { val idx = l.indexOf(kw); if (idx != -1) return f.substring(idx + kw.length).trim() }; return f.trim() }
    private fun showToast(msg: String) = android.os.Handler(android.os.Looper.getMainLooper()).post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
    private fun setupFloatingButton() {
        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.END; x = 0; y = 200 }
            floatingButton = Button(this).apply { text = "🎤"; alpha = 0.7f; setOnTouchListener(object : View.OnTouchListener {
                private var ix = 0; private var iy = 0; private var itx = 0f; private var ity = 0f
                override fun onTouch(v: View, e: MotionEvent): Boolean {
                    when (e.action) {
                        MotionEvent.ACTION_DOWN -> { ix = params.x; iy = params.y; itx = e.rawX; ity = e.rawY; return true }
                        MotionEvent.ACTION_MOVE -> { params.x = ix + (itx - e.rawX).toInt(); params.y = iy + (e.rawY - ity).toInt(); windowManager?.updateViewLayout(floatingButton, params); return true }
                        MotionEvent.ACTION_UP -> { if (Math.abs(e.rawX - itx) < 10 && Math.abs(e.rawY - ity) < 10) startActivity(Intent(this@MyAccessibilityService, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP); putExtra("start_voice", true) }); return true }
                    }
                    return false
                }
            }) }
            windowManager?.addView(floatingButton, params)
        } catch (e: Exception) {}
    }
    override fun onDestroy() { super.onDestroy(); serviceScope.cancel(); try { unregisterReceiver(commandReceiver) } catch (e: Exception) {}; floatingButton?.let { windowManager?.removeView(it) } }
}
