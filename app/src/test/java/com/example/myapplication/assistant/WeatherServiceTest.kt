package com.example.myapplication.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherServiceTest {

    @Test
    fun resolvesBuiltInCitiesWithoutNetwork() {
        val taipei = WeatherService.resolveBuiltIn("台北")
        assertNotNull(taipei)
        assertEquals("Taipei", taipei!!.displayEn)

        val tokyo = WeatherService.resolveBuiltIn("Tokyo")
        assertNotNull(tokyo)
        assertEquals("東京", tokyo!!.displayZh)
    }

    @Test
    fun formatsForecastInChineseAndEnglish() {
        val forecast = WeatherService.Forecast(
            locationName = "台北",
            dayOffset = 0,
            currentTempC = 24.4,
            apparentTempC = 26.8,
            humidityPct = 70,
            weatherCode = 2,
            minTempC = 21.0,
            maxTempC = 27.6,
            rainChancePct = 30
        )
        val zh = WeatherService.formatForecast(forecast, preferChinese = true)
        assertTrue(zh, zh.contains("台北今天多雲時晴"))
        assertTrue(zh, zh.contains("目前 24°C"))
        assertTrue(zh, zh.contains("體感 27°C"))
        assertTrue(zh, zh.contains("降雨機率 30%"))

        val en = WeatherService.formatForecast(forecast.copy(locationName = "Taipei", dayOffset = 1, currentTempC = null), preferChinese = false)
        assertTrue(en, en.contains("In Taipei tomorrow it's partly cloudy"))
        assertTrue(en, en.contains("high 28°C and low 21°C"))
        assertTrue(en, en.contains("30% chance of rain"))
    }
}
