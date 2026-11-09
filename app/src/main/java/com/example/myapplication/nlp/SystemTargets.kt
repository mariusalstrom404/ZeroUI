package com.example.myapplication.nlp

/**
 * The system-level things [FastPathParser] recognises by name. Aliases are lowercase, with
 * hyphens written as spaces, matching [FastPathParser.normalize].
 */

/** A setting that can be switched on or off. [label] is how ZeroUI says it. */
enum class SystemSetting(val label: String, val page: SettingsPage, vararg val aliases: String) {
    WIFI("Wi-Fi", SettingsPage.WIFI, "wifi", "wi fi", "wlan", "wireless"),
    BLUETOOTH("Bluetooth", SettingsPage.BLUETOOTH, "bluetooth", "blue tooth"),
    AIRPLANE_MODE("Airplane mode", SettingsPage.AIRPLANE, "airplane mode", "aeroplane mode", "flight mode", "airplane", "aeroplane"),
    DO_NOT_DISTURB("Do not disturb", SettingsPage.DO_NOT_DISTURB, "do not disturb", "dnd", "do not disturb mode"),
    AUTO_ROTATE("Auto-rotate", SettingsPage.AUTO_ROTATE, "auto rotate", "autorotate", "auto rotation", "screen rotation", "rotation", "rotate"),
    DARK_MODE("Dark mode", SettingsPage.DARK_THEME, "dark mode", "dark theme", "night mode"),
    LOCATION("Location", SettingsPage.LOCATION, "location", "gps", "location services"),
    MOBILE_DATA("Mobile data", SettingsPage.NETWORK, "mobile data", "cellular data", "cellular", "data"),
    HOTSPOT("Hotspot", SettingsPage.NETWORK, "hotspot", "hot spot", "mobile hotspot", "wifi hotspot", "tethering"),
    NFC("NFC", SettingsPage.NFC, "nfc"),
    BATTERY_SAVER("Battery saver", SettingsPage.BATTERY_SAVER, "battery saver", "battery saver mode", "power saver", "power saving", "power saving mode", "low power mode");

    companion object {
        fun find(name: String): SystemSetting? = entries.firstOrNull { name in it.aliases }
    }
}

/** A system-wide action the accessibility service can perform without opening an app. */
enum class SystemAction(val label: String) {
    HOME("Going home"),
    BACK("Going back"),
    RECENTS("Showing recent apps"),
    NOTIFICATIONS("Opening notifications"),
    QUICK_SETTINGS("Opening quick settings"),
    LOCK_SCREEN("Locking the screen"),
    POWER_MENU("Opening the power menu"),
    SPLIT_SCREEN("Toggling split screen")
}

/** A page of the system Settings app. [label] is how ZeroUI says it. */
enum class SettingsPage(val label: String, vararg val aliases: String) {
    MAIN("Settings"),
    WIFI("Wi-Fi settings", "wifi", "wi fi", "wlan"),
    BLUETOOTH("Bluetooth settings", "bluetooth", "blue tooth"),
    NETWORK("network settings", "network", "network and internet", "internet", "mobile network", "mobile data", "hotspot"),
    AIRPLANE("airplane mode settings", "airplane mode", "flight mode"),
    DISPLAY("display settings", "display", "screen", "brightness"),
    DARK_THEME("dark theme settings", "dark theme", "dark mode", "night mode"),
    AUTO_ROTATE("auto-rotate settings", "auto rotate", "autorotate", "screen rotation", "rotation"),
    SOUND("sound settings", "sound", "sounds", "volume", "ringtone", "sound and vibration"),
    DO_NOT_DISTURB("Do not disturb settings", "do not disturb", "dnd"),
    BATTERY("battery settings", "battery", "battery usage"),
    BATTERY_SAVER("battery saver settings", "battery saver", "power saving"),
    APPS("app settings", "app", "apps", "application", "applications"),
    NOTIFICATIONS("notification settings", "notification", "notifications"),
    LOCATION("location settings", "location", "gps"),
    STORAGE("storage settings", "storage"),
    ABOUT_PHONE("About phone", "about phone", "about device", "about", "device info", "phone info"),
    DATE_TIME("date and time settings", "date and time", "date", "time"),
    LANGUAGE("language settings", "language", "languages", "language and input"),
    ACCESSIBILITY("accessibility settings", "accessibility"),
    SECURITY("security settings", "security", "lock screen"),
    PRIVACY("privacy settings", "privacy"),
    NFC("NFC settings", "nfc"),
    DATA_USAGE("data usage settings", "data usage"),
    DEVELOPER("developer options", "developer", "developer options");

    companion object {
        fun find(name: String): SettingsPage? = entries.firstOrNull { name in it.aliases }
    }
}

/**
 * A built-in app found by its role (dialer, calculator…) rather than its name, since the name
 * differs between phone makers. [labels] are tried by name when no app answers for the role.
 */
enum class SystemApp(val labels: List<String>, vararg val aliases: String) {
    PHONE(listOf("Phone", "Dialer"), "phone", "dialer", "phone app", "dial pad", "dialpad", "keypad"),
    CONTACTS(listOf("Contacts"), "contacts", "contact list", "address book", "people"),
    MESSAGES(listOf("Messages", "Messaging"), "messages", "messaging", "sms", "text messages", "texts"),
    CLOCK(listOf("Clock"), "clock", "alarm", "alarms", "timer", "stopwatch"),
    CALENDAR(listOf("Calendar"), "calendar"),
    CALCULATOR(listOf("Calculator"), "calculator"),
    GALLERY(listOf("Gallery", "Photos", "Album"), "gallery", "photos", "photo gallery", "album", "pictures"),
    FILES(listOf("Files", "File Manager", "My Files"), "files", "file manager", "my files", "downloads"),
    BROWSER(listOf("Chrome", "Browser", "Internet"), "browser", "web browser", "chrome", "internet browser"),
    MAPS(listOf("Maps", "Google Maps"), "maps", "map", "google maps"),
    MUSIC(listOf("Music", "YouTube Music"), "music", "music player"),
    EMAIL(listOf("Gmail", "Email"), "email", "mail", "gmail", "inbox"),
    PLAY_STORE(listOf("Play Store", "Google Play Store"), "play store", "google play", "app store", "google play store");

    companion object {
        fun find(name: String): SystemApp? = entries.firstOrNull { name in it.aliases }
    }
}
