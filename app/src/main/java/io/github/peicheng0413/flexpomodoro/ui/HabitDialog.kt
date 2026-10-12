package io.github.peicheng0413.flexpomodoro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.peicheng0413.flexpomodoro.app
import io.github.peicheng0413.flexpomodoro.data.HabitEntity
import io.github.peicheng0413.flexpomodoro.data.HabitGoalEntity
import io.github.peicheng0413.flexpomodoro.habit.Goal
import io.github.peicheng0413.flexpomodoro.habit.GoalHistory
import kotlinx.coroutines.launch
import java.time.LocalDate

const val DAILY_MINUTES_STEP = 5
const val MAX_DAILY_MINUTES = 480

/**
 * 新增或編輯習慣。habit 為 null 代表新增。
 * 編輯時可以封存／取消封存；改目標預設從今天起生效，打開「也套用到過去」才會改寫歷史。
 */
@Composable
fun HabitDialog(habit: HabitEntity?, onDismiss: () -> Unit, onSaved: (HabitEntity) -> Unit = {}) {
    val app = LocalContext.current.app
    // 先等目標讀進來，欄位才有正確的初始值
    val rows by app.habits.goals.collectAsStateWithLifecycle<List<HabitGoalEntity>?>(null)
    val loaded = rows ?: return
    val defaultPercent by app.settings.defaultBreakPercent.collectAsStateWithLifecycle()
    val goal = remember(habit?.id) {
        GoalHistory(loaded.filter { it.habitId == habit?.id }).at(LocalDate.now())
    }
    HabitDialogContent(habit, habit?.breakPercent ?: defaultPercent, goal, onDismiss, onSaved)
}

@Composable
private fun HabitDialogContent(
    habit: HabitEntity?,
    initialPercent: Int,
    initialGoal: Goal,
    onDismiss: () -> Unit,
    onSaved: (HabitEntity) -> Unit,
) {
    val app = LocalContext.current.app
    val scope = rememberCoroutineScope()
    val isNew = habit == null
    var name by remember { mutableStateOf(habit?.name.orEmpty()) }
    var percent by remember { mutableIntStateOf(initialPercent) }
    var daily by remember { mutableIntStateOf(initialGoal.dailyMinutes) }
    var weekly by remember { mutableIntStateOf(initialGoal.weeklyDays) }
    var applyToPast by remember { mutableStateOf(false) }
    var clash by remember { mutableStateOf(false) }
    val goalChanged = Goal(daily, weekly) != initialGoal

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isNew) "新增習慣" else "編輯習慣") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; clash = false },
                    label = { Text("名稱") },
                    singleLine = true,
                    isError = clash,
                    supportingText = if (clash) {
                        { Text("已經有同名的習慣。要合併請到習慣頁用「併入其他習慣」。") }
                    } else null,
                )
                Stepper(
                    label = "每天目標",
                    value = "$daily 分",
                    onMinus = { daily -= DAILY_MINUTES_STEP },
                    onPlus = { daily += DAILY_MINUTES_STEP },
                    canMinus = daily > DAILY_MINUTES_STEP,
                    canPlus = daily < MAX_DAILY_MINUTES,
                )
                Stepper(
                    label = "每週達成",
                    value = "$weekly 天",
                    onMinus = { weekly-- },
                    onPlus = { weekly++ },
                    canMinus = weekly > 1,
                    canPlus = weekly < 7,
                )
                BreakPercentSlider(percent, onChange = { percent = it })
                if (!isNew && goalChanged) {
                    Row(
                        Modifier.fillMaxWidth().clickable { applyToPast = !applyToPast },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("也套用到過去")
                            Text(
                                if (applyToPast) "過去每一天都改用新目標重新判定" else "新目標從今天起生效，過去照舊",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = applyToPast, onCheckedChange = { applyToPast = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && !clash,
                onClick = {
                    scope.launch {
                        val goal = Goal(daily, weekly)
                        val saved = if (habit == null) {
                            app.habits.create(name, percent, goal)
                        } else {
                            val updated = habit.copy(name = name.trim(), breakPercent = percent)
                            updated.takeIf { app.habits.update(it, goal, applyToPast) }
                        }
                        if (saved == null) {
                            clash = true
                        } else {
                            onSaved(saved)
                            onDismiss()
                        }
                    }
                },
            ) { Text("儲存") }
        },
        dismissButton = {
            Row {
                if (habit != null) {
                    TextButton(onClick = {
                        app.appScope.launch { app.habits.setArchived(habit, !habit.archived) }
                        onDismiss()
                    }) { Text(if (habit.archived) "取消封存" else "封存") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

/** 「− 30 分 +」的加減器，按鈕夠大給手指按。 */
@Composable
fun Stepper(
    label: String,
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    canMinus: Boolean,
    canPlus: Boolean,
    modifier: Modifier = Modifier,
    hint: String? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            hint?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        OutlinedButton(onClick = onMinus, enabled = canMinus) { Text("−") }
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 4.dp),
        )
        OutlinedButton(onClick = onPlus, enabled = canPlus) { Text("+") }
    }
}

/**
 * 選一個習慣（移動紀錄、合併習慣用）。allowUnassigned 時多一個「未歸類」選項，選到會回傳 null。
 */
@Composable
fun HabitPickerDialog(
    title: String,
    habits: List<HabitEntity>,
    onDismiss: () -> Unit,
    onPick: (HabitEntity?) -> Unit,
    allowUnassigned: Boolean = false,
    message: String? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                message?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
                }
                if (habits.isEmpty() && !allowUnassigned) Text("沒有其他習慣可以選。")
                habits.forEach { h ->
                    Text(
                        h.name,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(h); onDismiss() }.padding(vertical = 14.dp),
                    )
                }
                if (allowUnassigned) {
                    Text(
                        "未歸類",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(null); onDismiss() }.padding(vertical = 14.dp),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
