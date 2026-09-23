package io.github.peicheng0413.flexpomodoro.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.roundToInt

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _defaultBreakPercent = MutableStateFlow(
        // 舊版存的是除數（÷5），換算成百分比
        prefs.getInt(KEY_PERCENT, prefs.getInt(KEY_OLD_RATIO, 0).let { if (it > 0) (100.0 / it).roundToInt() else 20 })
    )
    val defaultBreakPercent: StateFlow<Int> = _defaultBreakPercent.asStateFlow()

    private val _autoEndMinutes = MutableStateFlow(prefs.getInt(KEY_AUTO_END, 60))
    val autoEndMinutes: StateFlow<Int> = _autoEndMinutes.asStateFlow()

    /** 橫向模式的視窗亮度，0 = 最低。 */
    private val _landscapeBrightness = MutableStateFlow(prefs.getFloat(KEY_BRIGHTNESS, 0f))
    val landscapeBrightness: StateFlow<Float> = _landscapeBrightness.asStateFlow()

    fun setDefaultBreakPercent(value: Int) {
        _defaultBreakPercent.value = value
        prefs.edit { putInt(KEY_PERCENT, value) }
    }

    fun setAutoEndMinutes(value: Int) {
        _autoEndMinutes.value = value
        prefs.edit { putInt(KEY_AUTO_END, value) }
    }

    fun setLandscapeBrightness(value: Float) {
        _landscapeBrightness.value = value
        prefs.edit { putFloat(KEY_BRIGHTNESS, value) }
    }

    // 提醒進度：避免同一次休息重複震動
    var alertedBreakSegmentId: Long
        get() = prefs.getLong(KEY_ALERTED_BREAK, -1)
        set(value) = prefs.edit { putLong(KEY_ALERTED_BREAK, value) }

    var lastOvertimePing: Int
        get() = prefs.getInt(KEY_LAST_PING, 0)
        set(value) = prefs.edit { putInt(KEY_LAST_PING, value) }

    private companion object {
        const val KEY_OLD_RATIO = "default_ratio"
        const val KEY_PERCENT = "default_break_percent"
        const val KEY_AUTO_END = "auto_end_minutes"
        const val KEY_BRIGHTNESS = "landscape_brightness"
        const val KEY_ALERTED_BREAK = "alerted_break_segment"
        const val KEY_LAST_PING = "last_overtime_ping"
    }
}
