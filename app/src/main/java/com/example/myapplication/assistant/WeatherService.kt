package com.example.myapplication.assistant

import com.example.myapplication.debug.Timing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Fetches real-time weather using Open-Meteo (free, fast, no API key required).
 *
 * Speed optimizations:
 * - Built-in coordinates table for major Taiwan and world cities avoids a geocoding round-trip.
 * - IP-based current city lookup is cached in memory for 30 minutes, with instant timezone fallback.
 */
object WeatherService {

    data class Coordinates(val lat: Double, val lon: Double, val displayZh: String, val displayEn: String)

    data class Forecast(
        val locationName: String,
        val dayOffset: Int,
        val currentTempC: Double?,
        val apparentTempC: Double?,
        val humidityPct: Int?,
        val weatherCode: Int,
        val minTempC: Double?,
        val maxTempC: Double?,
        val rainChancePct: Int?
    )

    private const val CONNECT_TIMEOUT_MS = 3500
    private const val READ_TIMEOUT_MS = 4500
    private const val IP_CACHE_TTL_MS = 30 * 60 * 1000L

    @Volatile private var cachedIpLocation: Coordinates? = null
    @Volatile private var cachedIpAt: Long = 0L

    private val BUILT_IN_CITIES: Map<String, Coordinates> = buildMap {
        fun add(coords: Coordinates, vararg aliases: String) {
            for (alias in aliases) put(alias.lowercase(Locale.ROOT), coords)
        }
        add(Coordinates(25.0330, 121.5654, "台北", "Taipei"), "台北", "臺北", "台北市", "臺北市", "taipei", "taipei city")
        add(Coordinates(25.0120, 121.4657, "新北", "New Taipei"), "新北", "新北市", "new taipei", "banqiao", "板橋")
        add(Coordinates(24.9936, 121.3010, "桃園", "Taoyuan"), "桃園", "桃園市", "taoyuan")
        add(Coordinates(24.8138, 120.9675, "新竹", "Hsinchu"), "新竹", "新竹市", "新竹縣", "hsinchu")
        add(Coordinates(24.1477, 120.6736, "台中", "Taichung"), "台中", "臺中", "台中市", "臺中市", "taichung")
        add(Coordinates(24.0518, 120.5161, "彰化", "Changhua"), "彰化", "彰化縣", "changhua")
        add(Coordinates(23.4801, 120.4491, "嘉義", "Chiayi"), "嘉義", "嘉義市", "chiayi")
        add(Coordinates(22.9999, 120.2269, "台南", "Tainan"), "台南", "臺南", "台南市", "臺南市", "tainan")
        add(Coordinates(22.6273, 120.3014, "高雄", "Kaohsiung"), "高雄", "高雄市", "kaohsiung")
        add(Coordinates(22.6714, 120.4881, "屏東", "Pingtung"), "屏東", "屏東縣", "pingtung")
        add(Coordinates(25.1276, 121.7392, "基隆", "Keelung"), "基隆", "基隆市", "keelung")
        add(Coordinates(24.7570, 121.7530, "宜蘭", "Yilan"), "宜蘭", "宜蘭縣", "yilan")
        add(Coordinates(23.9872, 121.6016, "花蓮", "Hualien"), "花蓮", "花蓮縣", "hualien")
        add(Coordinates(22.7583, 121.1444, "台東", "Taitung"), "台東", "臺東", "台東縣", "taitung")
        add(Coordinates(23.5711, 119.5793, "澎湖", "Penghu"), "澎湖", "penghu")
        add(Coordinates(22.3193, 114.1694, "香港", "Hong Kong"), "香港", "hong kong", "hk")
        add(Coordinates(22.1987, 113.5439, "澳門", "Macau"), "澳門", "澳门", "macau")
        add(Coordinates(35.6762, 139.6503, "東京", "Tokyo"), "東京", "东京", "tokyo")
        add(Coordinates(34.6937, 135.5023, "大阪", "Osaka"), "大阪", "osaka")
        add(Coordinates(35.0116, 135.7681, "京都", "Kyoto"), "京都", "kyoto")
        add(Coordinates(37.5665, 126.9780, "首爾", "Seoul"), "首爾", "首尔", "seoul")
        add(Coordinates(1.3521, 103.8198, "新加坡", "Singapore"), "新加坡", "singapore")
        add(Coordinates(13.7563, 100.5018, "曼谷", "Bangkok"), "曼谷", "bangkok")
        add(Coordinates(31.2304, 121.4737, "上海", "Shanghai"), "上海", "shanghai")
        add(Coordinates(39.9042, 116.4074, "北京", "Beijing"), "北京", "beijing")
        add(Coordinates(51.5072, -0.1276, "倫敦", "London"), "倫敦", "伦敦", "london")
        add(Coordinates(48.8566, 2.3522, "巴黎", "Paris"), "巴黎", "paris")
        add(Coordinates(40.7128, -74.0060, "紐約", "New York"), "紐約", "纽约", "new york", "nyc")
        add(Coordinates(37.7749, -122.4194, "舊金山", "San Francisco"), "舊金山", "旧金山", "san francisco", "sf")
        add(Coordinates(34.0522, -118.2437, "洛杉磯", "Los Angeles"), "洛杉磯", "洛杉矶", "los angeles", "la")
        add(Coordinates(-33.8688, 151.2093, "雪梨", "Sydney"), "雪梨", "悉尼", "sydney")
    }

    /** Looks up weather and returns a ready-to-speak summary, or an error message if offline. */
    suspend fun queryWeather(location: String?, dayOffset: Int, preferChinese: Boolean): String =
        withContext(Dispatchers.IO) {
            val start = Timing.now()
            val clampedOffset = dayOffset.coerceIn(0, 2)
            val coords = resolveCoordinates(location, preferChinese)
                ?: return@withContext if (preferChinese) {
                    "抱歉，找不到「$location」的天氣資料，請試試看輸入城市名稱。"
                } else {
                    "Sorry, I couldn't find weather data for \"$location\"."
                }

            val forecast = fetchForecast(coords, clampedOffset, preferChinese)
            Timing.log("WeatherService query (${coords.displayEn}): ${Timing.now() - start} ms")
            if (forecast == null) {
                return@withContext if (preferChinese) {
                    "目前無法連線取得天氣資訊，請檢查網路後再試一次。"
                } else {
                    "I couldn't reach the weather service right now. Please check your connection and try again."
                }
            }
            formatForecast(forecast, preferChinese)
        }

    internal fun resolveBuiltIn(location: String): Coordinates? =
        BUILT_IN_CITIES[location.trim().lowercase(Locale.ROOT)]

    private fun resolveCoordinates(location: String?, preferChinese: Boolean): Coordinates? {
        val cleaned = location?.trim()?.takeIf { it.isNotEmpty() }
        if (cleaned != null) {
            resolveBuiltIn(cleaned)?.let { return it }
            return geocodeCity(cleaned, preferChinese)
        }
        return detectCurrentCity()
    }

    private fun geocodeCity(city: String, preferChinese: Boolean): Coordinates? {
        val encoded = URLEncoder.encode(city, "UTF-8")
        val lang = if (preferChinese) "zh" else "en"
        val body = httpGet("https://geocoding-api.open-meteo.com/v1/search?name=$encoded&count=1&language=$lang&format=json")
            ?: return null
        return try {
            val results = JSONObject(body).optJSONArray("results") ?: return null
            if (results.length() == 0) return null
            val first = results.getJSONObject(0)
            val name = first.optString("name", city)
            Coordinates(first.getDouble("latitude"), first.getDouble("longitude"), name, name)
        } catch (e: Exception) {
            null
        }
    }

    private fun detectCurrentCity(): Coordinates {
        val now = System.currentTimeMillis()
        val cached = cachedIpLocation
        if (cached != null && now - cachedIpAt < IP_CACHE_TTL_MS) return cached

        val body = httpGet("https://get.geojs.io/v1/ip/geo.json", connectMs = 2000, readMs = 2500)
        if (body != null) {
            try {
                val json = JSONObject(body)
                val lat = json.optString("latitude").toDoubleOrNull()
                val lon = json.optString("longitude").toDoubleOrNull()
                val city = json.optString("city").ifBlank { json.optString("region") }
                if (lat != null && lon != null) {
                    val mapped = city.takeIf { it.isNotBlank() }?.let { resolveBuiltIn(it) }
                    val coords = mapped ?: Coordinates(
                        lat,
                        lon,
                        city.ifBlank { "目前位置" },
                        city.ifBlank { "your location" }
                    )
                    cachedIpLocation = coords
                    cachedIpAt = now
                    return coords
                }
            } catch (_: Exception) {
            }
        }
        return fallbackByTimeZone()
    }

    private fun fallbackByTimeZone(): Coordinates {
        val tz = TimeZone.getDefault().id
        return when {
            tz.contains("Taipei", ignoreCase = true) -> BUILT_IN_CITIES["taipei"]!!
            tz.contains("Tokyo", ignoreCase = true) -> BUILT_IN_CITIES["tokyo"]!!
            tz.contains("Hong_Kong", ignoreCase = true) -> BUILT_IN_CITIES["hong kong"]!!
            tz.contains("Singapore", ignoreCase = true) -> BUILT_IN_CITIES["singapore"]!!
            tz.contains("Seoul", ignoreCase = true) -> BUILT_IN_CITIES["seoul"]!!
            tz.contains("London", ignoreCase = true) -> BUILT_IN_CITIES["london"]!!
            tz.contains("New_York", ignoreCase = true) -> BUILT_IN_CITIES["new york"]!!
            else -> BUILT_IN_CITIES["taipei"]!!
        }
    }

    private fun fetchForecast(coords: Coordinates, dayOffset: Int, preferChinese: Boolean): Forecast? {
        val url = String.format(
            Locale.ROOT,
            "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=3",
            coords.lat,
            coords.lon
        )
        val body = httpGet(url) ?: return null
        return try {
            val root = JSONObject(body)
            val current = root.optJSONObject("current")
            val daily = root.optJSONObject("daily")
            val codes = daily?.optJSONArray("weather_code")
            val maxArr = daily?.optJSONArray("temperature_2m_max")
            val minArr = daily?.optJSONArray("temperature_2m_min")
            val rainArr = daily?.optJSONArray("precipitation_probability_max")

            val idx = dayOffset.coerceIn(0, ((codes?.length() ?: 1) - 1).coerceAtLeast(0))
            val weatherCode = if (dayOffset == 0 && current != null && current.has("weather_code")) {
                current.optInt("weather_code", 0)
            } else {
                codes?.optInt(idx, 0) ?: 0
            }
            Forecast(
                locationName = if (preferChinese) coords.displayZh else coords.displayEn,
                dayOffset = dayOffset,
                currentTempC = if (dayOffset == 0) current?.optDouble("temperature_2m")?.takeUnless { it.isNaN() } else null,
                apparentTempC = if (dayOffset == 0) current?.optDouble("apparent_temperature")?.takeUnless { it.isNaN() } else null,
                humidityPct = if (dayOffset == 0 && current?.has("relative_humidity_2m") == true) current.optInt("relative_humidity_2m") else null,
                weatherCode = weatherCode,
                minTempC = minArr?.optDouble(idx)?.takeUnless { it.isNaN() },
                maxTempC = maxArr?.optDouble(idx)?.takeUnless { it.isNaN() },
                rainChancePct = if (rainArr != null && idx < rainArr.length() && !rainArr.isNull(idx)) rainArr.optInt(idx) else null
            )
        } catch (e: Exception) {
            null
        }
    }

    internal fun formatForecast(f: Forecast, preferChinese: Boolean): String {
        val desc = describeWeatherCode(f.weatherCode, preferChinese)
        if (preferChinese) {
            val dayLabel = when (f.dayOffset) {
                0 -> "今天"
                1 -> "明天"
                else -> "後天"
            }
            return buildString {
                append("${f.locationName}${dayLabel}${desc}")
                f.currentTempC?.let { cur ->
                    append("，目前 ${cur.roundToInt()}°C")
                    f.apparentTempC?.let { app ->
                        if (kotlin.math.abs(app - cur) >= 1.5) append("（體感 ${app.roundToInt()}°C）")
                    }
                }
                if (f.minTempC != null && f.maxTempC != null) {
                    append("，氣溫 ${f.minTempC.roundToInt()}°C～${f.maxTempC.roundToInt()}°C")
                }
                f.rainChancePct?.let { append("，降雨機率 ${it}%") }
                if (f.dayOffset == 0) {
                    f.humidityPct?.let { append("，濕度 ${it}%") }
                }
                append("。")
            }
        } else {
            val dayLabel = when (f.dayOffset) {
                0 -> "today"
                1 -> "tomorrow"
                else -> "in two days"
            }
            return buildString {
                append("In ${f.locationName} $dayLabel it's $desc")
                f.currentTempC?.let { cur ->
                    append(", currently ${cur.roundToInt()}°C")
                    f.apparentTempC?.let { app ->
                        if (kotlin.math.abs(app - cur) >= 1.5) append(" (feels like ${app.roundToInt()}°C)")
                    }
                }
                if (f.minTempC != null && f.maxTempC != null) {
                    append(", high ${f.maxTempC.roundToInt()}°C and low ${f.minTempC.roundToInt()}°C")
                }
                f.rainChancePct?.let { append(", with a ${it}% chance of rain") }
                append(".")
            }
        }
    }

    internal fun describeWeatherCode(code: Int, zh: Boolean): String = when (code) {
        0 -> if (zh) "晴朗" else "clear and sunny"
        1 -> if (zh) "大致晴朗" else "mainly clear"
        2 -> if (zh) "多雲時晴" else "partly cloudy"
        3 -> if (zh) "陰天" else "overcast"
        45, 48 -> if (zh) "有霧" else "foggy"
        51, 53, 55 -> if (zh) "毛毛雨" else "light drizzle"
        56, 57 -> if (zh) "凍毛雨" else "freezing drizzle"
        61 -> if (zh) "小雨" else "light rain"
        63 -> if (zh) "中雨" else "moderate rain"
        65 -> if (zh) "大雨" else "heavy rain"
        66, 67 -> if (zh) "凍雨" else "freezing rain"
        71, 73, 75, 77 -> if (zh) "降雪" else "snowy"
        80, 81, 82 -> if (zh) "陣雨" else "rain showers"
        85, 86 -> if (zh) "陣雪" else "snow showers"
        95 -> if (zh) "雷陣雨" else "thunderstorms"
        96, 99 -> if (zh) "雷陣雨伴隨冰雹" else "thunderstorms with hail"
        else -> if (zh) "多雲" else "cloudy"
    }

    private fun httpGet(url: String, connectMs: Int = CONNECT_TIMEOUT_MS, readMs: Int = READ_TIMEOUT_MS): String? {
        return try {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectMs
                readTimeout = readMs
                setRequestProperty("Accept", "application/json")
            }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }
            if (status in 200..299) body else null
        } catch (_: IOException) {
            null
        }
    }
}
