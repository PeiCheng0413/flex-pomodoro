package io.github.peicheng0413.flexpomodoro

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import io.github.peicheng0413.flexpomodoro.timer.Notifications
import io.github.peicheng0413.flexpomodoro.timer.TimerService
import io.github.peicheng0413.flexpomodoro.timer.TimerSnapshot
import io.github.peicheng0413.flexpomodoro.ui.FlexTheme
import io.github.peicheng0413.flexpomodoro.ui.FocusScreen
import io.github.peicheng0413.flexpomodoro.ui.HabitsScreen
import io.github.peicheng0413.flexpomodoro.ui.SettingsScreen
import io.github.peicheng0413.flexpomodoro.ui.TimerScreen
import io.github.peicheng0413.flexpomodoro.ui.rememberHabitBoard
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        // 打開 App 時順便把進行中的專注接回來（例如服務曾被系統終止）
        lifecycleScope.launch {
            app.sessions.checkAutoEnd()?.let { Notifications.showAutoEnded(this@MainActivity, it) }
            if (app.sessions.snapshot() != null) TimerService.start(this@MainActivity)
        }
        setContent { FlexTheme { AppRoot() } }
    }
}

/** 包一層，才能分辨「還在讀取」（null）和「沒有進行中的專注」（snapshot = null）。 */
private class Active(val snapshot: TimerSnapshot?)

@Composable
private fun AppRoot() {
    val app = LocalContext.current.app
    val active by remember { app.sessions.active.map { Active(it) } }
        .collectAsStateWithLifecycle(initialValue = null)
    val loaded = active ?: return
    val snapshot = loaded.snapshot
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (landscape && snapshot != null) {
        FocusScreen(snapshot)
    } else {
        MainScaffold(snapshot)
    }
}

private const val TAB_HABITS = 0
private const val TAB_TIMER = 1
private const val TAB_SETTINGS = 2

@Composable
private fun MainScaffold(snapshot: TimerSnapshot?) {
    // 打開 App 時如果正在專注，直接停在計時頁
    var tab by rememberSaveable { mutableIntStateOf(if (snapshot != null) TAB_TIMER else TAB_HABITS) }
    val board = rememberHabitBoard(snapshot)
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_HABITS, onClick = { tab = TAB_HABITS },
                    icon = { Icon(Icons.Filled.DateRange, null) }, label = { Text("習慣") },
                )
                NavigationBarItem(
                    selected = tab == TAB_TIMER, onClick = { tab = TAB_TIMER },
                    icon = { Icon(Icons.Filled.PlayArrow, null) }, label = { Text("計時") },
                )
                NavigationBarItem(
                    selected = tab == TAB_SETTINGS, onClick = { tab = TAB_SETTINGS },
                    icon = { Icon(Icons.Filled.Settings, null) }, label = { Text("設定") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            TAB_HABITS -> HabitsScreen(board, snapshot, onGoTimer = { tab = TAB_TIMER }, modifier = modifier)
            TAB_TIMER -> TimerScreen(snapshot, board, modifier)
            else -> SettingsScreen(modifier)
        }
    }
}
