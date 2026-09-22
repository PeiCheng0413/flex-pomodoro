package io.github.peicheng0413.flexpomodoro.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

/** 12:34 或 1:02:03。 */
fun formatDuration(ms: Long): String {
    val total = abs(ms) / 1000
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

/**
 * 休息倒數的顯示：正數無條件進位（倒數到剛好 0 才顯示 00:00），負數加上負號。
 */
fun formatCountdown(remainingMs: Long): String =
    if (remainingMs > 0) formatDuration((remainingMs + 999) / 1000 * 1000)
    else "-" + formatDuration(remainingMs)

/** 「2 小時 15 分」「8 分」，用在統計與通知。 */
fun formatDurationWords(ms: Long): String {
    val totalMin = ms / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "$h 小時 $m 分"
        h > 0 -> "$h 小時"
        ms in 1..59_999 -> "${ms / 1000} 秒"
        else -> "$m 分"
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("M/d（E）HH:mm", Locale.TAIWAN)

fun formatDateTime(epochMs: Long): String =
    dateFormatter.format(Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()))
