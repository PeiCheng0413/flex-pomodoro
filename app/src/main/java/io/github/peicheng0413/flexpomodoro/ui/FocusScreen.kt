package io.github.peicheng0413.flexpomodoro.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.peicheng0413.flexpomodoro.app
import io.github.peicheng0413.flexpomodoro.timer.TimerSnapshot
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * 橫向專注模式：純黑背景只顯示數字。單擊＝工作 ↔ 暫停，長按＝進入休息／回到工作。
 */
@Composable
fun FocusScreen(s: TimerSnapshot) {
    val app = LocalContext.current.app
    val haptic = LocalHapticFeedback.current
    val brightness by app.settings.landscapeBrightness.collectAsStateWithLifecycle()
    FocusWindow(brightness)

    val now by rememberNow()
    val d = s.display(now)

    // 防烙印：每分鐘換一個位置，最多偏移 24dp
    val minute = now / 60_000
    val shift = remember(minute) { Random(minute).let { (it.nextInt(-24, 25)) to (it.nextInt(-24, 25)) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { app.appScope.launch { app.sessions.togglePause() } },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        app.appScope.launch { app.sessions.toggleBreak() }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            d.text,
            style = TimerDigits,
            fontSize = if (d.text.length > 6) 120.sp else 160.sp,
            color = FocusColors.of(d.kind),
            modifier = Modifier.offset(shift.first.dp, shift.second.dp),
        )
    }
}

/** 橫向時：隱藏系統列、螢幕常亮、視窗亮度壓低；離開時全部還原。 */
@Composable
private fun FocusWindow(brightness: Float) {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.attributes = window.attributes.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    DisposableEffect(window, brightness) {
        // 0 在部分機型會被當成「關閉背光」，最低給 0.01
        window.attributes = window.attributes.apply { screenBrightness = brightness.coerceIn(0.01f, 1f) }
        onDispose { }
    }
}
