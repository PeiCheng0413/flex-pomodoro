package io.github.peicheng0413.flexpomodoro.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.peicheng0413.flexpomodoro.app
import io.github.peicheng0413.flexpomodoro.data.SegmentType
import io.github.peicheng0413.flexpomodoro.timer.NameGroup
import io.github.peicheng0413.flexpomodoro.timer.SessionSummary
import io.github.peicheng0413.flexpomodoro.timer.groupByName
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

private enum class Range(val label: String) { WEEK("本週"), MONTH("本月"), ALL("全部") }

private fun Range.startMillis(): Long {
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    return when (this) {
        Range.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay(zone).toInstant().toEpochMilli()
        Range.MONTH -> today.withDayOfMonth(1).atStartOfDay(zone).toInstant().toEpochMilli()
        Range.ALL -> Long.MIN_VALUE
    }
}

private fun groupLabel(name: String) = name.ifEmpty { "未命名" }

@Composable
fun StatsScreen(modifier: Modifier = Modifier) {
    val app = LocalContext.current.app
    val all by app.sessions.ended.collectAsStateWithLifecycle(emptyList())
    var range by rememberSaveable { mutableIntStateOf(Range.ALL.ordinal) }
    var openGroup by rememberSaveable { mutableStateOf<String?>(null) }

    val from = Range.entries[range].startMillis()
    val groups = remember(all, range) { groupByName(all.filter { it.session.startedAt >= from }) }

    val selected = openGroup
    if (selected != null) {
        BackHandler { openGroup = null }
        val group = groups.firstOrNull { it.name == selected } ?: NameGroup(selected, emptyList())
        GroupDetail(group, onBack = { openGroup = null }, onRenamed = { openGroup = it }, modifier = modifier)
        return
    }

    Column(modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(16.dp)) {
            Range.entries.forEachIndexed { i, r ->
                SegmentedButton(
                    selected = range == i,
                    onClick = { range = i },
                    shape = SegmentedButtonDefaults.itemShape(i, Range.entries.size),
                ) { Text(r.label) }
            }
        }
        if (groups.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("還沒有紀錄", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return
        }
        LazyColumn {
            items(groups, key = { it.name }) { g ->
                Row(
                    Modifier.fillMaxWidth().clickable { openGroup = g.name }.padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(groupLabel(g.name), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildString {
                                append("${g.count} 次")
                                if (g.overtimeMs > 0) append(" · 超時 ${formatDurationWords(g.overtimeMs)}")
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (g.overtimeMs > 0) LocalStateColors.current.overtime else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(formatDurationWords(g.workMs), style = MaterialTheme.typography.titleMedium)
                }
                HorizontalDivider()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupDetail(group: NameGroup, onBack: () -> Unit, onRenamed: (String) -> Unit, modifier: Modifier) {
    val app = LocalContext.current.app
    var renamingGroup by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<SessionSummary?>(null) }
    var deleting by remember { mutableStateOf<SessionSummary?>(null) }

    Column(modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(groupLabel(group.name), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
            actions = { IconButton(onClick = { renamingGroup = true }) { Icon(Icons.Filled.Edit, "整組改名") } },
            windowInsets = WindowInsets(0),
        )
        Text(
            "共 ${formatDurationWords(group.workMs)} · ${group.count} 次" +
                if (group.overtimeMs > 0) " · 超時 ${formatDurationWords(group.overtimeMs)}" else "",
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            items(group.sessions, key = { it.session.id }) { s ->
                SessionRow(s, onRename = { renaming = s }, onDelete = { deleting = s })
                HorizontalDivider()
            }
        }
    }

    if (renamingGroup) {
        NameDialog(
            title = "整組改名",
            initial = group.name,
            hint = "改成已存在的名稱會合併成同一組",
            onDismiss = { renamingGroup = false },
            onConfirm = { new ->
                app.appScope.launch { app.sessions.renameGroup(group.name, new) }
                onRenamed(new.trim())
            },
        )
    }
    renaming?.let { s ->
        NameDialog(
            title = "改這筆紀錄的名稱",
            initial = s.session.name,
            onDismiss = { renaming = null },
            onConfirm = { new -> app.appScope.launch { app.sessions.rename(s.session.id, new) } },
        )
    }
    deleting?.let { s ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除這筆紀錄？") },
            text = { Text("${formatDateTime(s.session.startedAt)}，工作 ${formatDurationWords(s.workMs)}") },
            confirmButton = {
                TextButton(onClick = {
                    app.appScope.launch { app.sessions.delete(s.session.id) }
                    deleting = null
                }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SessionRow(s: SessionSummary, onRename: () -> Unit, onDelete: () -> Unit) {
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
                    DropdownMenuItem(text = { Text("改名") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("刪除") }, onClick = { menu = false; onDelete() })
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

@Composable
fun NameDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit, hint: String? = null) {
    var name by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                    placeholder = { Text("留空＝未命名") })
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(name); onDismiss() }) { Text("確定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
