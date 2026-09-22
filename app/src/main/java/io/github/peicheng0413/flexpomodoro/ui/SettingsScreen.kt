package io.github.peicheng0413.flexpomodoro.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.peicheng0413.flexpomodoro.app
import io.github.peicheng0413.flexpomodoro.data.JsonBackup
import io.github.peicheng0413.flexpomodoro.data.TemplateEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.app
    val settings = app.settings
    val scope = rememberCoroutineScope()
    val ratio by settings.defaultRatio.collectAsStateWithLifecycle()
    val autoEnd by settings.autoEndMinutes.collectAsStateWithLifecycle()
    val brightness by settings.landscapeBrightness.collectAsStateWithLifecycle()
    val templates by app.db.templateDao().observeAll().collectAsStateWithLifecycle(emptyList())

    var editing by remember { mutableStateOf<TemplateEntity?>(null) }
    var importPlan by remember { mutableStateOf<JsonBackup.ImportPlan?>(null) }

    fun toast(msg: String) = Toast.makeText(context, msg, Toast.LENGTH_LONG).show()

    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val json = app.backup.export()
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray()) }
                }
            }.onSuccess { toast("已匯出") }.onFailure { toast("匯出失敗：${it.message}") }
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                }
                app.backup.plan(app.backup.parse(text))
            }.onSuccess { importPlan = it }.onFailure { toast("無法讀取檔案：${it.message}") }
        }
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("計時")
        RatioStepper(ratio, onChange = settings::setDefaultRatio, label = "預設休息比例")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("自動結束")
                Text("暫停或休息超時超過這個時間就自動結束", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedButton(onClick = { settings.setAutoEndMinutes((autoEnd - 10).coerceAtLeast(10)) }, enabled = autoEnd > 10) { Text("−") }
            Text("$autoEnd 分", modifier = Modifier.padding(horizontal = 8.dp))
            OutlinedButton(onClick = { settings.setAutoEndMinutes((autoEnd + 10).coerceAtMost(240)) }, enabled = autoEnd < 240) { Text("+") }
        }

        HorizontalDivider()
        Section("橫向專注模式")
        Text("亮度：${if (brightness <= 0f) "最低" else "${(brightness * 100).roundToInt()}%"}")
        Slider(value = brightness, onValueChange = settings::setLandscapeBrightness, valueRange = 0f..1f)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Section("模板", Modifier.weight(1f))
            TextButton(onClick = { editing = TemplateEntity(name = "", ratio = ratio) }) {
                Icon(Icons.Filled.Add, null)
                Text("新增")
            }
        }
        templates.forEach { t ->
            Row(
                Modifier.fillMaxWidth().clickable { editing = t }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(t.name, modifier = Modifier.weight(1f))
                Text("÷${t.ratio}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        HorizontalDivider()
        Section("資料")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { exporter.launch("flex-pomodoro-${LocalDate.now()}.json") }) { Text("匯出 JSON") }
            OutlinedButton(onClick = { importer.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("匯入 JSON") }
        }
        Text("匯入會合併資料：已經有的紀錄和同名模板會跳過。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    editing?.let { TemplateDialog(template = it, onDismiss = { editing = null }) }

    importPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { importPlan = null },
            title = { Text("匯入資料") },
            text = { Text("將新增 ${plan.newSessions.size} 筆紀錄、${plan.newTemplates.size} 個模板。") },
            confirmButton = {
                TextButton(
                    enabled = plan.newSessions.isNotEmpty() || plan.newTemplates.isNotEmpty(),
                    onClick = {
                        importPlan = null
                        scope.launch {
                            runCatching { app.backup.apply(plan) }
                                .onSuccess { toast("已匯入") }
                                .onFailure { toast("匯入失敗：${it.message}") }
                        }
                    },
                ) { Text("匯入") }
            },
            dismissButton = { TextButton(onClick = { importPlan = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun Section(title: String, modifier: Modifier = Modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = modifier)
}
