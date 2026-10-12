package io.github.peicheng0413.flexpomodoro.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.peicheng0413.flexpomodoro.app
import io.github.peicheng0413.flexpomodoro.data.HabitEntity
import io.github.peicheng0413.flexpomodoro.timer.MINUTE_MS
import io.github.peicheng0413.flexpomodoro.timer.Phase
import io.github.peicheng0413.flexpomodoro.timer.breakAllowance
import io.github.peicheng0413.flexpomodoro.timer.TimerService
import io.github.peicheng0413.flexpomodoro.timer.TimerSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 畫面上用的「現在時間」，預設每 200ms 更新一次。 */
@Composable
fun rememberNow(intervalMs: Long = 200): State<Long> = produceState(System.currentTimeMillis(), intervalMs) {
    while (true) {
        value = System.currentTimeMillis()
        delay(intervalMs)
    }
}

val TimerDigits = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.Light)

/** 計時數字、顏色、狀態文字，直向與橫向共用。 */
data class TimerDisplay(val text: String, val label: String, val kind: Kind) {
    enum class Kind { WORK, PAUSED, REST, OVERTIME }
}

fun TimerSnapshot.display(now: Long): TimerDisplay = when (phase) {
    Phase.WORKING -> TimerDisplay(formatDuration(workMs(now)), "工作中", TimerDisplay.Kind.WORK)
    Phase.PAUSED -> TimerDisplay(formatDuration(workMs(now)), "已暫停", TimerDisplay.Kind.PAUSED)
    Phase.BREAK -> {
        val remaining = breakRemainingMs(now)
        TimerDisplay(
            formatCountdown(remaining),
            if (remaining > 0) "休息中" else "超時",
            if (remaining > 0) TimerDisplay.Kind.REST else TimerDisplay.Kind.OVERTIME,
        )
    }
}

fun StateColors.of(kind: TimerDisplay.Kind): Color = when (kind) {
    TimerDisplay.Kind.WORK -> work
    TimerDisplay.Kind.PAUSED -> paused
    TimerDisplay.Kind.REST -> rest
    TimerDisplay.Kind.OVERTIME -> overtime
}

@Composable
fun TimerScreen(snapshot: TimerSnapshot?, board: HabitBoard, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        if (snapshot == null) StartPanel(board) else ActivePanel(snapshot, board)
    }
}

/** 開始面板的選擇：還沒選、單純計時，或某個習慣的 id。 */
private const val PICK_NONE = 0L
private const val PICK_PLAIN = -1L

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartPanel(board: HabitBoard) {
    val context = LocalContext.current
    val app = context.app
    val defaultPercent by app.settings.defaultBreakPercent.collectAsStateWithLifecycle()
    val habits = board.current.map { it.habit }

    var picked by rememberSaveable { mutableLongStateOf(PICK_NONE) }
    var percent by rememberSaveable(defaultPercent) { mutableIntStateOf(defaultPercent) }
    var editing by remember { mutableStateOf<HabitEntity?>(null) }
    var creating by remember { mutableStateOf(false) }
    // 選到的習慣被封存或合併掉時就當作沒選
    val habit = habits.firstOrNull { it.id == picked }
    val ready = habit != null || picked == PICK_PLAIN

    Column(Modifier.fillMaxSize().padding(vertical = 24.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("這次要做什麼？", style = MaterialTheme.typography.titleLarge)
            if (habits.isNotEmpty()) {
                Text("習慣（長按可編輯）", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                habits.forEach { h ->
                    PickChip(
                        label = h.name,
                        selected = h.id == picked,
                        onClick = { picked = h.id; percent = h.breakPercent },
                        onLongClick = { editing = h },
                    )
                }
                PickChip(label = "＋ 新增習慣", selected = false, onClick = { creating = true })
            }
            PickChip(
                label = "不選習慣，單純計時",
                selected = picked == PICK_PLAIN,
                onClick = { picked = PICK_PLAIN; percent = defaultPercent },
            )
            if (picked == PICK_PLAIN) {
                Text("紀錄會先放在「未歸類」，之後可以歸入習慣。", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            habit?.let { h ->
                board.of(h.id)?.let { p ->
                    val today = p.today
                    Text(
                        "今天已做 ${p.workMs(today) / MINUTE_MS} / ${p.goal(today).dailyMinutes} 分",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
            BreakPercentSlider(percent, onChange = { percent = it })
        }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = ready,
            onClick = {
                app.appScope.launch {
                    app.sessions.start(habit, percent)
                    TimerService.start(context)
                }
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) { Text(if (ready) "開始" else "先選一個習慣", fontSize = 20.sp) }
    }

    editing?.let { h -> HabitDialog(habit = h, onDismiss = { editing = null }) }
    if (creating) {
        HabitDialog(
            habit = null,
            onDismiss = { creating = false },
            onSaved = { picked = it.id; percent = it.breakPercent },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PickChip(label: String, selected: Boolean, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val haptic = LocalHapticFeedback.current
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick?.let { action -> { haptic.performHapticFeedback(HapticFeedbackType.LongPress); action() } },
        ),
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.titleMedium,
        )
    }
}

const val MIN_BREAK_PERCENT = 5
const val MAX_BREAK_PERCENT = 50

/** 休息比例：工作時間的百分之多少可以拿來休息。 */
@Composable
fun BreakPercentSlider(value: Int, onChange: (Int) -> Unit, label: String = "休息比例") {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f))
            Text("$value%", style = MaterialTheme.typography.titleMedium)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.roundToInt().coerceIn(MIN_BREAK_PERCENT, MAX_BREAK_PERCENT)) },
            valueRange = MIN_BREAK_PERCENT.toFloat()..MAX_BREAK_PERCENT.toFloat(),
        )
        Text(
            "工作 50 分鐘可以休息 ${formatDurationWords(breakAllowance(50 * 60_000L, value))}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ActivePanel(s: TimerSnapshot, board: HabitBoard) {
    val context = LocalContext.current
    val app = context.app
    val sessions = app.sessions
    val haptic = LocalHapticFeedback.current
    val now by rememberNow()
    val d = s.display(now)
    val color = LocalStateColors.current.of(d.kind)
    fun run(block: suspend () -> Unit) { app.appScope.launch { block() } }

    Column(Modifier.fillMaxSize().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val progress = s.session.habitId?.let(board::of)
        Text(
            progress?.habit?.name ?: sessionTitle(s.session.name),
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )
        progress?.let { p ->
            val today = p.today
            Text(
                "今天 ${p.workMs(today) / MINUTE_MS} / ${p.goal(today).dailyMinutes} 分" + if (p.done(today)) " · 已達成" else "",
                style = MaterialTheme.typography.titleMedium,
                color = if (p.done(today)) LocalStateColors.current.rest else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
        Spacer(Modifier.weight(1f))
        Text(d.label, style = MaterialTheme.typography.titleMedium, color = color)
        Text(d.text, style = TimerDigits, fontSize = if (d.text.length > 6) 64.sp else 88.sp, color = color)
        val info = when (s.phase) {
            Phase.BREAK -> "第 ${s.round} 輪 · 休息額度 ${formatDuration(s.current.allowanceMs)}"
            else -> "第 ${s.round} 輪 · 下次休息 ${formatDuration(s.nextAllowanceMs(now))}"
        }
        Text(info, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val big = Modifier.weight(1f).height(64.dp)
            when (s.phase) {
                Phase.WORKING -> {
                    FilledTonalButton(onClick = { run { sessions.pause() } }, modifier = big) { Text("暫停", fontSize = 18.sp) }
                    Button(onClick = { run { sessions.takeBreak() } }, modifier = big) { Text("休息", fontSize = 18.sp) }
                }
                Phase.PAUSED -> {
                    FilledTonalButton(onClick = { run { sessions.resume() } }, modifier = big) { Text("繼續", fontSize = 18.sp) }
                    Button(onClick = { run { sessions.takeBreak() } }, modifier = big) { Text("休息", fontSize = 18.sp) }
                }
                Phase.BREAK -> {
                    Button(onClick = { run { sessions.startWork() } }, modifier = big) { Text("開始工作", fontSize = 18.sp) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Surface(
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)),
            color = Color.Transparent,
            modifier = Modifier.fillMaxWidth().height(48.dp).combinedClickable(
                onClick = { Toast.makeText(context, "長按以結束這次專注", Toast.LENGTH_SHORT).show() },
                onLongClick = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    run { sessions.end() }
                },
            ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text("長按結束", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
