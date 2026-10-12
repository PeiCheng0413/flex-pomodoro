package io.github.peicheng0413.flexpomodoro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.peicheng0413.flexpomodoro.app
import io.github.peicheng0413.flexpomodoro.data.HabitEntity
import io.github.peicheng0413.flexpomodoro.data.SegmentType
import io.github.peicheng0413.flexpomodoro.habit.HabitProgress
import io.github.peicheng0413.flexpomodoro.habit.overviewLevel
import io.github.peicheng0413.flexpomodoro.habit.weekStart
import io.github.peicheng0413.flexpomodoro.timer.MINUTE_MS
import io.github.peicheng0413.flexpomodoro.timer.SessionSummary
import io.github.peicheng0413.flexpomodoro.timer.TimerService
import io.github.peicheng0413.flexpomodoro.timer.TimerSnapshot
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 習慣首頁（兼統計）：總覽熱圖、每個習慣的卡片、未歸類紀錄、已封存的習慣。
 * 點卡片進入詳細頁看大熱圖與紀錄。
 */
@Composable
fun HabitsScreen(board: HabitBoard, snapshot: TimerSnapshot?, onGoTimer: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.app
    var openHabit by rememberSaveable { mutableStateOf<Long?>(null) }
    var openUnassigned by rememberSaveable { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    fun start(habit: HabitEntity) {
        app.appScope.launch {
            app.sessions.start(habit, habit.breakPercent)
            TimerService.start(context)
        }
        onGoTimer()
    }

    // 習慣被合併掉時 of() 會找不到，自然回到列表
    val detail = openHabit?.let(board::of)
    if (detail != null) {
        BackHandler { openHabit = null }
        HabitDetail(detail, board, onOpenHabit = { openHabit = it }, modifier = modifier)
        return
    }
    if (openUnassigned) {
        BackHandler { openUnassigned = false }
        UnassignedDetail(board, onBack = { openUnassigned = false }, modifier = modifier)
        return
    }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (board.current.isNotEmpty()) item { Overview(board) }
        items(board.current, key = { it.habit.id }) { p ->
            HabitCard(
                p, snapshot,
                onOpen = { openHabit = p.habit.id },
                onStart = { start(p.habit) },
                onGoTimer = onGoTimer,
            )
        }
        item {
            OutlinedButton(onClick = { creating = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Icon(Icons.Filled.Add, null)
                Text("新增習慣", modifier = Modifier.padding(start = 8.dp))
            }
        }
        if (board.unassigned.isNotEmpty()) {
            item {
                ListRow(
                    title = "未歸類",
                    detail = "${board.unassigned.size} 筆 · 共 ${formatDurationWords(board.unassigned.sumOf { it.workMs })}",
                    onClick = { openUnassigned = true },
                )
            }
        }
        if (board.archived.isNotEmpty()) {
            item {
                Text("已封存", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp))
            }
            items(board.archived, key = { it.habit.id }) { p ->
                ListRow(p.habit.name, "共 ${formatDurationWords(p.totalMs())}", onClick = { openHabit = p.habit.id })
            }
        }
    }

    if (creating) HabitDialog(habit = null, onDismiss = { creating = false })
}

/** 最上方的總覽：今天達成幾個習慣，加上所有習慣合併的熱圖。 */
@Composable
private fun Overview(board: HabitBoard) {
    val current = board.current
    val today = board.today
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                "今天達成 ${current.count { it.done(today) }} / ${current.size} 個習慣",
                style = MaterialTheme.typography.titleLarge,
            )
            Heatmap(
                today = today,
                firstDay = current.minOfOrNull { it.firstDay } ?: today,
                level = { overviewLevel(current, it) },
                cell = 14.dp,
            )
            HeatLegend(listOf("沒做", "有做", "達成部分", "全部達成"))
        }
    }
}

@Composable
private fun HabitCard(
    p: HabitProgress,
    snapshot: TimerSnapshot?,
    onOpen: () -> Unit,
    onStart: () -> Unit,
    onGoTimer: () -> Unit,
) {
    val today = p.today
    val goal = p.goal(today)
    val work = p.workMs(today)
    val week = today.weekStart()
    val green = LocalStateColors.current.rest
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.habit.name,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (snapshot?.session?.habitId == p.habit.id) {
                    FilledTonalButton(onClick = onGoTimer) { Text("計時中") }
                } else {
                    Button(onClick = onStart, enabled = snapshot == null) {
                        Icon(Icons.Filled.PlayArrow, null)
                        Text("開始")
                    }
                }
            }
            WeekStrip(today = today, level = p::level)
            // 目標頻率一行：左邊是簡寫的目標，右邊是本週進度
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(goal.shortLabel(), style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    weekShort(p, week),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (p.weekMet(week)) green else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("今天 ${work / MINUTE_MS} / ${goal.dailyMinutes} 分", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (p.done(today)) Text("已達成", style = MaterialTheme.typography.titleSmall, color = green)
            }
            LinearProgressIndicator(
                progress = { (work.toFloat() / goal.dailyMs).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = green,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}

/** 卡片右側的本週進度：「本週 3/5 · 連續2週」。 */
private fun weekShort(p: HabitProgress, week: LocalDate) = buildString {
    append("本週 ${p.daysDone(week)}/${p.weekTarget(week)}")
    if (p.streakWeeks > 0) append(" · 連續${p.streakWeeks}週")
}

private fun weekLine(p: HabitProgress, week: LocalDate) = buildString {
    append("本週 ${p.daysDone(week)} / ${p.weekTarget(week)} 天")
    if (p.streakWeeks > 0) append(" · 連續 ${p.streakWeeks} 週達標")
}

@Composable
private fun ListRow(title: String, detail: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HabitDetail(p: HabitProgress, board: HabitBoard, onOpenHabit: (Long?) -> Unit, modifier: Modifier) {
    val app = LocalContext.current.app
    val habit = p.habit
    var selectedDay by rememberSaveable(habit.id) { mutableLongStateOf(p.today.toEpochDay()) }
    var editing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var mergeInto by remember { mutableStateOf<HabitEntity?>(null) }
    var moving by remember { mutableStateOf<SessionSummary?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }
    val records = board.records(habit.id)
    val others = board.current.map { it.habit }.filter { it.id != habit.id }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(habit.name + if (habit.archived) "（已封存）" else "", maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            navigationIcon = { IconButton(onClick = { onOpenHabit(null) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = {
                IconButton(onClick = { editing = true }) { Icon(Icons.Filled.Edit, "編輯") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("併入其他習慣…") }, onClick = { menu = false; merging = true })
                        DropdownMenuItem(
                            text = { Text(if (habit.archived) "取消封存" else "封存") },
                            onClick = {
                                menu = false
                                app.appScope.launch { app.habits.setArchived(habit, !habit.archived) }
                            },
                        )
                    }
                }
            },
            windowInsets = WindowInsets(0),
        )
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            item {
                HabitSummary(p, LocalDate.ofEpochDay(selectedDay), onSelect = { selectedDay = it.toEpochDay() })
            }
            item {
                Text("紀錄", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp))
            }
            if (records.isEmpty()) {
                item {
                    Text("還沒有紀錄", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                }
            }
            items(records, key = { it.session.id }) { s ->
                SessionRow(s, actions = listOf("移到其他習慣" to { moving = s }, "刪除" to { deleting = s }))
                HorizontalDivider()
            }
        }
    }

    if (editing) HabitDialog(habit = habit, onDismiss = { editing = false })
    if (merging) {
        HabitPickerDialog(
            title = "併入其他習慣",
            message = "「${habit.name}」的 ${records.size} 筆紀錄會移到選的習慣，目標以對方為準，然後移除「${habit.name}」。",
            habits = others,
            onDismiss = { merging = false },
            onPick = { mergeInto = it },
        )
    }
    mergeInto?.let { target ->
        AlertDialog(
            onDismissRequest = { mergeInto = null },
            title = { Text("確定合併？") },
            text = { Text("把「${habit.name}」併入「${target.name}」。合併後無法分開。") },
            confirmButton = {
                TextButton(onClick = {
                    mergeInto = null
                    app.appScope.launch { app.habits.merge(habit.id, target.id) }
                    onOpenHabit(target.id)
                }) { Text("合併") }
            },
            dismissButton = { TextButton(onClick = { mergeInto = null }) { Text("取消") } },
        )
    }
    moving?.let { s ->
        HabitPickerDialog(
            title = "移到其他習慣",
            habits = others,
            allowUnassigned = true,
            onDismiss = { moving = null },
            onPick = { h -> app.appScope.launch { app.sessions.assign(s.session.id, h?.id) } },
        )
    }
    deleting?.let { s -> DeleteRecordDialog(s, onDismiss = { deleting = null }) }
}

/** 詳細頁上半部：目標、本週進度、大熱圖（可點選某天）、各期間總時數。 */
@Composable
private fun HabitSummary(p: HabitProgress, selected: LocalDate, onSelect: (LocalDate) -> Unit) {
    val today = p.today
    val goal = p.goal(today)
    val dayGoal = p.goal(selected)
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("目標 ${goal.shortLabel()}", style = MaterialTheme.typography.bodyLarge)
        Text(weekLine(p, today.weekStart()), style = MaterialTheme.typography.titleMedium)
        Heatmap(
            today = today,
            firstDay = p.firstDay,
            level = p::level,
            weekMet = p::weekMet,
            selected = selected,
            onDayClick = onSelect,
        )
        Text(
            "${formatDate(selected)}　${p.workMs(selected) / MINUTE_MS} / ${dayGoal.dailyMinutes} 分" +
                if (p.done(selected)) " · 已達成" else "",
            style = MaterialTheme.typography.titleMedium,
        )
        HeatLegend(listOf("沒做", "未滿一半", "一半以上", "達成"))
        Text("每欄下方的綠條：那一週達成每週目標", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Total("本週", p.totalMs(today.weekStart()), Modifier.weight(1f))
            Total("本月", p.totalMs(today.withDayOfMonth(1)), Modifier.weight(1f))
            Total("全部", p.totalMs(), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Total(label: String, ms: Long, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(formatDurationWords(ms), style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnassignedDetail(board: HabitBoard, onBack: () -> Unit, modifier: Modifier) {
    val app = LocalContext.current.app
    var moving by remember { mutableStateOf<SessionSummary?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("未歸類") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            windowInsets = WindowInsets(0),
        )
        Text(
            "單純計時的紀錄。歸入習慣後，才會算進那個習慣每天的達成。",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (board.unassigned.isEmpty()) {
            Text("沒有未歸類的紀錄", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(board.unassigned, key = { it.session.id }) { s ->
                SessionRow(s, actions = listOf("歸入習慣" to { moving = s }, "刪除" to { deleting = s }))
                HorizontalDivider()
            }
        }
    }

    moving?.let { s ->
        HabitPickerDialog(
            title = "歸入習慣",
            habits = board.current.map { it.habit },
            onDismiss = { moving = null },
            onPick = { h -> h?.let { app.appScope.launch { app.sessions.assign(s.session.id, it.id) } } },
        )
    }
    deleting?.let { s -> DeleteRecordDialog(s, onDismiss = { deleting = null }) }
}

@Composable
private fun DeleteRecordDialog(s: SessionSummary, onDismiss: () -> Unit) {
    val app = LocalContext.current.app
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("刪除這筆紀錄？") },
        text = { Text("${formatDateTime(s.session.startedAt)}，工作 ${formatDurationWords(s.workMs)}") },
        confirmButton = {
            TextButton(onClick = {
                app.appScope.launch { app.sessions.delete(s.session.id) }
                onDismiss()
            }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun SessionRow(s: SessionSummary, actions: List<Pair<String, () -> Unit>>) {
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    formatDateTime(s.session.startedAt) + if (s.session.autoEnded) " · 自動結束" else "",
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    buildString {
                        append("工作 ${formatDuration(s.workMs)} · ${s.rounds} 輪")
                        if (s.overtimeMs > 0) append(" · 超時 ${formatDuration(s.overtimeMs)}")
                        if (s.pauseMs > 0) append(" · 暫停 ${formatDuration(s.pauseMs)}")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "更多") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    actions.forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                    }
                }
            }
        }
        TimelineStrip(s, Modifier.padding(top = 8.dp, end = 16.dp).fillMaxWidth().height(12.dp))
    }
}

/** 一次專注的時間軸色帶：白＝工作、灰＝暫停、綠＝休息、紅＝超時。 */
@Composable
fun TimelineStrip(s: SessionSummary, modifier: Modifier = Modifier) {
    val colors = LocalStateColors.current
    val start = s.session.startedAt
    val span = (s.end - start).coerceAtLeast(1).toFloat()
    Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
        fun bar(from: Long, to: Long, color: Color) {
            val x0 = (from - start) / span * size.width
            val x1 = (to - start) / span * size.width
            if (x1 > x0) drawRect(color, Offset(x0, 0f), Size(x1 - x0, size.height))
        }
        s.segments.forEach { g ->
            val end = g.end ?: s.end
            when (g.type) {
                SegmentType.WORK -> bar(g.start, end, colors.work)
                SegmentType.PAUSE -> bar(g.start, end, colors.paused)
                SegmentType.BREAK -> {
                    val allowanceEnd = minOf(end, g.start + g.allowanceMs)
                    bar(g.start, allowanceEnd, colors.rest)
                    bar(allowanceEnd, end, colors.overtime)
                }
            }
        }
    }
}
