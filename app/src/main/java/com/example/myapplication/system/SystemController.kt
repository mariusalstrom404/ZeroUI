package com.example.myapplication.system

import android.accessibilityservice.AccessibilityService
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.example.myapplication.automation.AutomationEngine
import com.example.myapplication.automation.QuickSettingsTile
import com.example.myapplication.nlp.SettingsPage
import com.example.myapplication.nlp.SystemAction
import com.example.myapplication.nlp.SystemApp
import com.example.myapplication.nlp.SystemSetting
import kotlinx.coroutines.delay

/**
 * Carries out system-level commands: switching settings, system actions, Settings pages and
 * built-in apps.
 *
 * Since Android 10 an ordinary app can't switch most settings itself (Wi-Fi, Bluetooth, airplane
 * mode…), so [toggle] does what a person would: it opens Quick Settings, reads the tile's state
 * and taps it only if it needs changing. The few settings an app may change directly (auto-rotate
 * with "modify system settings", Do not disturb with "Do not disturb access") skip the tiles.
 */
class SystemController(
    private val service: AccessibilityService,
    private val engine: AutomationEngine
) {

    /** The setting's state afterwards, or null when it couldn't be read or changed this way. */
    suspend fun toggle(setting: SystemSetting, enable: Boolean?): Boolean? =
        directToggle(setting, enable) ?: toggleTile(TILE_NAMES.getValue(setting), enable)

    /** Performs [action]; false when this phone doesn't support it. */
    fun perform(action: SystemAction): Boolean {
        val global = when (action) {
            SystemAction.HOME -> AccessibilityService.GLOBAL_ACTION_HOME
            SystemAction.BACK -> AccessibilityService.GLOBAL_ACTION_BACK
            SystemAction.RECENTS -> AccessibilityService.GLOBAL_ACTION_RECENTS
            SystemAction.NOTIFICATIONS -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            SystemAction.QUICK_SETTINGS -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            SystemAction.POWER_MENU -> AccessibilityService.GLOBAL_ACTION_POWER_DIALOG
            SystemAction.SPLIT_SCREEN -> AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN
            SystemAction.LOCK_SCREEN ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                else return false
        }
        return service.performGlobalAction(global)
    }

    /** Opens [page], or the main Settings screen when this phone has no such page; returns which, or null. */
    fun openSettings(page: SettingsPage): SettingsPage? {
        if (SETTINGS_ACTIONS.getValue(page).any { start(Intent(it)) }) return page
        return if (page != SettingsPage.MAIN && start(Intent(Settings.ACTION_SETTINGS))) SettingsPage.MAIN else null
    }

    /**
     * Opens [app] by its role, which works whatever the maker named it, or else by its usual
     * names through [launchByName]. Returns false when neither found it.
     */
    fun openSystemApp(app: SystemApp, launchByName: (String) -> String?): Boolean {
        roleIntent(app)?.let { if (start(it)) return true }
        return app.labels.any { launchByName(it) != null }
    }

    /**
     * Flips [setting]'s switch on the Settings page already open (see [openSettings]): the switch
     * whose row names the setting, or the page's only switch. Returns the state afterwards, or
     * null when no such switch was found or it didn't change.
     */
    suspend fun toggleOnPage(setting: SystemSetting, enable: Boolean?): Boolean? {
        val names = TILE_NAMES.getValue(setting) + setting.aliases
        val switch = findPageSwitch(names) ?: return null.also { Log.d(LOG, "No switch for $setting on this page") }
        val before = switch.isChecked
        val wanted = enable ?: !before
        if (before == wanted) return wanted
        if (!engine.click(switch)) return null
        // A theme change redraws the whole page, so the switch is looked up again.
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < PAGE_SWITCH_TIMEOUT_MS) {
            delay(250)
            val after = findPageSwitch(names)?.isChecked
            if (after == wanted) return wanted
        }
        return null
    }

    private fun findPageSwitch(names: List<String>): AccessibilityNodeInfo? {
        val roots = try {
            service.windows.mapNotNull { it.root }
        } catch (e: Exception) {
            emptyList()
        }.filter { it.packageName != service.packageName && it.packageName != SYSTEM_UI }
        val switches = roots.flatMap { flatten(it) }.filter { it.isCheckable && it.isVisibleToUser }
        val named = switches.firstOrNull { switch ->
            val row = rowOf(switch)
            (listOf(row) + flatten(row)).any { node ->
                listOf(node.text, node.contentDescription).any { label ->
                    val simple = QuickSettingsTile.simplify(label)
                    names.any { name -> simple == name || simple.contains(" $name") || simple.startsWith("$name ") }
                }
            }
        }
        return named ?: switches.singleOrNull()
    }

    /** The settings row holding [switch]: its nearest clickable ancestor, or its parent. */
    private fun rowOf(switch: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val parent = switch.parent ?: return switch
        return clickableSelfOrAncestor(parent) ?: parent
    }

    // --- Settings an app may change itself ---

    private fun directToggle(setting: SystemSetting, enable: Boolean?): Boolean? = try {
        when (setting) {
            SystemSetting.AUTO_ROTATE -> if (!Settings.System.canWrite(service)) null else {
                val resolver = service.contentResolver
                val current = Settings.System.getInt(resolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1
                val wanted = enable ?: !current
                if (Settings.System.putInt(resolver, Settings.System.ACCELEROMETER_ROTATION, if (wanted) 1 else 0)) wanted else null
            }
            SystemSetting.DO_NOT_DISTURB -> {
                val manager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!manager.isNotificationPolicyAccessGranted) null else {
                    val current = manager.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
                    val wanted = enable ?: !current
                    manager.setInterruptionFilter(
                        if (wanted) NotificationManager.INTERRUPTION_FILTER_PRIORITY
                        else NotificationManager.INTERRUPTION_FILTER_ALL
                    )
                    wanted
                }
            }
            else -> null
        }
    } catch (e: Exception) {
        Log.e(LOG, "Couldn't change $setting directly", e)
        null
    }

    // --- Quick Settings ---

    private class Tile(val node: AccessibilityNodeInfo, val state: Boolean?)

    private suspend fun toggleTile(names: List<String>, enable: Boolean?): Boolean? {
        if (!service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)) return null
        try {
            val tile = waitForTile(names) ?: return null.also { Log.d(LOG, "No Quick Settings tile for $names") }
            val before = tile.state
            // Toggling a tile whose state can't be read is fine; switching it "on" blindly is not.
            val wanted = enable ?: before?.not() ?: return null
            if (before == wanted) return wanted
            if (!engine.click(tile.node)) return null
            delay(TILE_SETTLE_MS)
            // Only a tile that now reads the wanted state counts: some tiles open a panel instead.
            val after = findTile(names)?.state
            Log.d(LOG, "Tile $names: $before -> $after (wanted $wanted)")
            return if (after == wanted) wanted else null
        } finally {
            closeShade()
        }
    }

    private suspend fun waitForTile(names: List<String>): Tile? {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < TILE_TIMEOUT_MS) {
            findTile(names)?.let { return it }
            delay(150)
        }
        return null
    }

    /** Prefers a label that's exactly the tile's name, so "Wi-Fi network available" can't pass for it. */
    private fun findTile(names: List<String>): Tile? {
        val nodes = systemUiRoots().flatMap { flatten(it) }.filter { it.isVisibleToUser }
        for (exact in listOf(true, false)) {
            for (node in nodes) {
                val name = listOf(node.contentDescription, node.text).firstNotNullOfOrNull { label ->
                    QuickSettingsTile.matchedName(label, names)
                        ?.takeIf { !exact || QuickSettingsTile.simplify(label) == it }
                } ?: continue
                val tile = clickableSelfOrAncestor(node) ?: continue
                val labels = (listOf(tile) + flatten(tile))
                    .flatMap { listOf(it.text, it.contentDescription, stateDescription(it)) }
                val checked = tile.takeIf { it.isCheckable }?.isChecked
                return Tile(tile, QuickSettingsTile.state(checked, name, *labels.toTypedArray()))
            }
        }
        return null
    }

    private fun systemUiRoots(): List<AccessibilityNodeInfo> {
        val roots = try {
            service.windows.mapNotNull { it.root }
        } catch (e: Exception) {
            emptyList()
        } + listOfNotNull(service.rootInActiveWindow)
        return roots.filter { it.packageName == SYSTEM_UI }.distinct()
    }

    private fun flatten(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            out += node
            if (depth >= MAX_DEPTH) return
            for (i in 0 until node.childCount) visit(node.getChild(i) ?: continue, depth + 1)
        }
        visit(root, 0)
        return out
    }

    private fun clickableSelfOrAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        repeat(4) {
            val n = current ?: return null
            if (n.isClickable) return n
            current = n.parent
        }
        return null
    }

    private fun stateDescription(node: AccessibilityNodeInfo): CharSequence? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) node.stateDescription else null

    private suspend fun closeShade() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        } else {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        delay(300)
    }

    // --- Starting activities ---

    private fun roleIntent(app: SystemApp): Intent? = when (app) {
        SystemApp.PHONE -> Intent(Intent.ACTION_DIAL)
        SystemApp.CLOCK -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
        SystemApp.CONTACTS -> mainFor(Intent.CATEGORY_APP_CONTACTS)
        SystemApp.MESSAGES -> mainFor(Intent.CATEGORY_APP_MESSAGING)
        SystemApp.CALENDAR -> mainFor(Intent.CATEGORY_APP_CALENDAR)
        SystemApp.CALCULATOR -> mainFor(Intent.CATEGORY_APP_CALCULATOR)
        SystemApp.GALLERY -> mainFor(Intent.CATEGORY_APP_GALLERY)
        SystemApp.FILES -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) mainFor(Intent.CATEGORY_APP_FILES) else null
        SystemApp.BROWSER -> mainFor(Intent.CATEGORY_APP_BROWSER)
        SystemApp.MAPS -> mainFor(Intent.CATEGORY_APP_MAPS)
        SystemApp.MUSIC -> mainFor(Intent.CATEGORY_APP_MUSIC)
        SystemApp.EMAIL -> mainFor(Intent.CATEGORY_APP_EMAIL)
        SystemApp.PLAY_STORE -> null
    }

    private fun mainFor(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)

    /** Starts [intent] if something on this phone handles it. */
    private fun start(intent: Intent): Boolean {
        if (intent.resolveActivity(service.packageManager) == null) return false
        return try {
            service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            true
        } catch (e: Exception) {
            Log.e(LOG, "Couldn't start $intent", e)
            false
        }
    }

    private companion object {
        const val LOG = "ZeroUISystem"
        const val SYSTEM_UI = "com.android.systemui"
        const val TILE_TIMEOUT_MS = 1500L
        const val TILE_SETTLE_MS = 700L
        const val PAGE_SWITCH_TIMEOUT_MS = 3000L
        const val MAX_DEPTH = 40

        /** Tile names as phones label them, simplified ([QuickSettingsTile.simplify]). */
        val TILE_NAMES: Map<SystemSetting, List<String>> = mapOf(
            SystemSetting.WIFI to listOf("wi fi", "wifi", "wlan"),
            SystemSetting.BLUETOOTH to listOf("bluetooth"),
            SystemSetting.AIRPLANE_MODE to listOf("airplane mode", "aeroplane mode", "flight mode"),
            SystemSetting.DO_NOT_DISTURB to listOf("do not disturb"),
            SystemSetting.AUTO_ROTATE to listOf("auto rotate", "auto rotate screen", "screen rotation", "rotation"),
            SystemSetting.DARK_MODE to listOf("dark theme", "dark mode", "night mode"),
            SystemSetting.LOCATION to listOf("location"),
            SystemSetting.MOBILE_DATA to listOf("mobile data", "cellular data"),
            SystemSetting.HOTSPOT to listOf("hotspot", "mobile hotspot", "wi fi hotspot"),
            SystemSetting.NFC to listOf("nfc"),
            SystemSetting.BATTERY_SAVER to listOf("battery saver", "power saving", "power saving mode", "stamina mode", "stamina")
        )

        /** Intent actions for each page, best first. */
        val SETTINGS_ACTIONS: Map<SettingsPage, List<String>> = mapOf(
            SettingsPage.MAIN to listOf(Settings.ACTION_SETTINGS),
            SettingsPage.WIFI to listOf(Settings.ACTION_WIFI_SETTINGS),
            SettingsPage.BLUETOOTH to listOf(Settings.ACTION_BLUETOOTH_SETTINGS),
            SettingsPage.NETWORK to listOf(Settings.ACTION_WIRELESS_SETTINGS),
            SettingsPage.AIRPLANE to listOf(Settings.ACTION_AIRPLANE_MODE_SETTINGS),
            SettingsPage.DISPLAY to listOf(Settings.ACTION_DISPLAY_SETTINGS),
            SettingsPage.DARK_THEME to listOf("android.settings.DARK_THEME_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS),
            SettingsPage.AUTO_ROTATE to listOf("android.settings.AUTO_ROTATE_SETTINGS", Settings.ACTION_DISPLAY_SETTINGS),
            SettingsPage.SOUND to listOf(Settings.ACTION_SOUND_SETTINGS),
            SettingsPage.DO_NOT_DISTURB to listOf("android.settings.ZEN_MODE_SETTINGS", Settings.ACTION_SOUND_SETTINGS),
            SettingsPage.BATTERY to listOf(Intent.ACTION_POWER_USAGE_SUMMARY, Settings.ACTION_BATTERY_SAVER_SETTINGS),
            SettingsPage.BATTERY_SAVER to listOf(Settings.ACTION_BATTERY_SAVER_SETTINGS),
            SettingsPage.APPS to listOf(Settings.ACTION_APPLICATION_SETTINGS),
            SettingsPage.NOTIFICATIONS to listOf("android.settings.NOTIFICATION_SETTINGS"),
            SettingsPage.LOCATION to listOf(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
            SettingsPage.STORAGE to listOf(Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
            SettingsPage.ABOUT_PHONE to listOf(Settings.ACTION_DEVICE_INFO_SETTINGS),
            SettingsPage.DATE_TIME to listOf(Settings.ACTION_DATE_SETTINGS),
            SettingsPage.LANGUAGE to listOf(Settings.ACTION_LOCALE_SETTINGS),
            SettingsPage.ACCESSIBILITY to listOf(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            SettingsPage.SECURITY to listOf(Settings.ACTION_SECURITY_SETTINGS),
            SettingsPage.PRIVACY to listOf(Settings.ACTION_PRIVACY_SETTINGS, Settings.ACTION_SECURITY_SETTINGS),
            SettingsPage.NFC to listOf(Settings.ACTION_NFC_SETTINGS),
            SettingsPage.DATA_USAGE to listOf(Settings.ACTION_DATA_USAGE_SETTINGS),
            SettingsPage.DEVELOPER to listOf(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
        )
    }
}
