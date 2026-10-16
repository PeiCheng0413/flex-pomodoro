package io.github.peicheng0413.flexpomodoro.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.peicheng0413.flexpomodoro.habit.weekStart
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** 熱圖四階的顏色：底色到休息綠。 */
@Composable
fun heatColors(): List<Color> {
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val green = LocalStateColors.current.rest
    return listOf(empty, lerp(empty, green, 0.35f), lerp(empty, green, 0.65f), green)
}

/**
 * GitHub 式熱圖：一欄一週（星期一在上），最新的一週在最右邊。
 * 資料比畫面寬時可以往左滑看更早的週；比畫面窄時往前補空白週，把寬度填滿。
 *
 * @param level 某天的顏色階（0–3）
 * @param weekMet 每欄下方的週達標標記；null 表示不畫
 * @param onDayClick 點某一天（今天以後的格子不能點）
 */
@Composable
fun Heatmap(
    today: LocalDate,
    firstDay: LocalDate,
    level: (LocalDate) -> Int,
    modifier: Modifier = Modifier,
    weekMet: ((LocalDate) -> Boolean)? = null,
    cell: Dp = 16.dp,
    gap: Dp = 3.dp,
    labels: Boolean = true,
    selected: LocalDate? = null,
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    val colors = heatColors()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val todayColor = MaterialTheme.colorScheme.onSurface
    val selectedColor = MaterialTheme.colorScheme.primary
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)
    val click by rememberUpdatedState(onDayClick)

    val gutter = if (labels) 18.dp else 0.dp
    val monthRow = if (labels) 16.dp else 0.dp
    val marker = if (weekMet != null) 7.dp else 0.dp
    val step = cell + gap
    val height = monthRow + step * 7 - gap + marker

    BoxWithConstraints(modifier) {
        val fit = ((maxWidth - gutter + gap) / step).toInt().coerceAtLeast(1)
        val thisWeek = today.weekStart()
        val dataWeeks = ChronoUnit.WEEKS.between(firstDay.weekStart(), thisWeek).toInt() + 1
        val weeks = maxOf(fit, dataWeeks)
        val start = thisWeek.minusWeeks(weeks - 1L)

        Row {
            if (labels) {
                // 星期標籤固定在左邊，不跟著滑動
                Canvas(Modifier.size(gutter, height)) {
                    listOf(0 to "一", 2 to "三", 4 to "五").forEach { (row, text) ->
                        drawText(measurer, text, Offset(0f, (monthRow + step * row).toPx() + (cell.toPx() - 14.sp.toPx()) / 2), labelStyle)
                    }
                }
            }
            Box(Modifier.horizontalScroll(rememberScrollState(Int.MAX_VALUE))) {
                Canvas(
                    Modifier
                        .size(step * weeks - gap, height)
                        // 不能點的熱圖（例如卡片上的小熱圖）不攔截點擊，讓外層卡片收得到
                        .then(
                            if (onDayClick == null) Modifier
                            else Modifier.pointerInput(start, weeks, today) {
                                detectTapGestures { o ->
                                    val col = (o.x / step.toPx()).toInt()
                                    val row = ((o.y - monthRow.toPx()) / step.toPx()).toInt()
                                    if (o.y < monthRow.toPx() || row !in 0..6 || col !in 0 until weeks) return@detectTapGestures
                                    val date = start.plusDays(col * 7L + row)
                                    if (!date.isAfter(today)) click?.invoke(date)
                                }
                            }
                        ),
                ) {
                    val c = cell.toPx()
                    val s = step.toPx()
                    val top = monthRow.toPx()
                    val radius = CornerRadius(c * 0.2f)
                    var lastLabelCol = -10
                    for (col in 0 until weeks) {
                        val monday = start.plusWeeks(col.toLong())
                        val x = col * s
                        // 月份標在包含 1 號的那一欄
                        if (labels) {
                            val first = (0L until 7L).map { monday.plusDays(it) }.firstOrNull { it.dayOfMonth == 1 }
                            if (first != null && col - lastLabelCol >= 3) {
                                drawText(measurer, "${first.monthValue}月", Offset(x, 0f), labelStyle)
                                lastLabelCol = col
                            }
                        }
                        for (row in 0 until 7) {
                            val date = monday.plusDays(row.toLong())
                            if (date.isAfter(today)) break
                            val topLeft = Offset(x, top + row * s)
                            drawRoundRect(colors[level(date).coerceIn(0, 3)], topLeft, Size(c, c), radius)
                            when (date) {
                                selected -> drawRoundRect(selectedColor, topLeft, Size(c, c), radius, style = Stroke(2.dp.toPx()))
                                today -> drawRoundRect(todayColor, topLeft, Size(c, c), radius, style = Stroke(1.dp.toPx()))
                            }
                        }
                        if (weekMet != null) {
                            val met = weekMet(monday)
                            // 本週還沒達標不畫，免得看起來像失敗
                            if (met || monday != thisWeek) {
                                drawRoundRect(
                                    if (met) colors[3] else colors[0],
                                    Offset(x, top + 7 * s - gap.toPx() + 3.dp.toPx()),
                                    Size(c, 4.dp.toPx()),
                                    CornerRadius(2.dp.toPx()),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

/**
 * 本週週曆（星期一到日）：格子裡是日期，顏色階和熱圖一樣，達成的那天亮成綠色。
 * 今天加外框，還沒到的日子只畫淡框。onDayClick 不為 null 時，今天以前的格子可以點（點擊會輕震）。
 */
@Composable
fun WeekStrip(
    today: LocalDate,
    level: (LocalDate) -> Int,
    modifier: Modifier = Modifier,
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    val colors = heatColors()
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(8.dp)
    val monday = today.weekStart()
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WEEKDAY_LABELS.forEachIndexed { i, label ->
            val date = monday.plusDays(i.toLong())
            val future = date.isAfter(today)
            val lv = if (future) 0 else level(date).coerceIn(0, 3)
            val fill = if (future) Color.Transparent else colors[lv]
            // 亮綠底上的字依底色深淺選黑或白，其他格子用一般文字色
            val textColor = when {
                future -> MaterialTheme.colorScheme.onSurfaceVariant
                lv == 3 -> if (fill.luminance() > 0.4f) Color.Black else Color.White
                else -> MaterialTheme.colorScheme.onSurface
            }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (date == today) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(shape)
                        .background(fill)
                        .then(
                            if (onDayClick == null || future) Modifier
                            else Modifier.clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDayClick(date)
                            }
                        )
                        .then(
                            when {
                                date == today -> Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, shape)
                                future -> Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                                else -> Modifier
                            }
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${date.dayOfMonth}", style = MaterialTheme.typography.titleSmall, color = textColor)
                }
            }
        }
    }
}

/** 熱圖下方的圖例。 */
@Composable
fun HeatLegend(labels: List<String>, modifier: Modifier = Modifier) {
    val colors = heatColors()
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        labels.forEachIndexed { i, text ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.size(12.dp)) { drawRoundRect(colors[i], cornerRadius = CornerRadius(2.dp.toPx())) }
                Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
