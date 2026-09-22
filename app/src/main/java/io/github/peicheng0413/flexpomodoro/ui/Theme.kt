package io.github.peicheng0413.flexpomodoro.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** 四種狀態的固定顏色：白＝工作、灰＝暫停、綠＝休息、紅＝超時。 */
@Immutable
data class StateColors(val work: Color, val paused: Color, val rest: Color, val overtime: Color)

/** 橫向 OLED 模式：全部壓暗，發光像素少、晚上不刺眼。 */
val FocusColors = StateColors(
    work = Color(0xFF8C8C8C),
    paused = Color(0xFF3F3F3F),
    rest = Color(0xFF2F6B45),
    overtime = Color(0xFF7A2A2A),
)

private val DarkStateColors = StateColors(
    work = Color(0xFFECECEC),
    paused = Color(0xFF8A8A8A),
    rest = Color(0xFF66BB6A),
    overtime = Color(0xFFEF5350),
)

private val LightStateColors = StateColors(
    work = Color(0xFF1F1F1F),
    paused = Color(0xFF9E9E9E),
    rest = Color(0xFF2E7D32),
    overtime = Color(0xFFC62828),
)

val LocalStateColors = staticCompositionLocalOf { DarkStateColors }

@Composable
fun FlexTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    MaterialTheme(
        colorScheme = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context),
    ) {
        androidx.compose.runtime.CompositionLocalProvider(
            LocalStateColors provides if (dark) DarkStateColors else LightStateColors,
            content = content,
        )
    }
}
