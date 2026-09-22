package io.github.peicheng0413.flexpomodoro

import android.app.Application
import android.content.Context
import io.github.peicheng0413.flexpomodoro.data.AppDatabase
import io.github.peicheng0413.flexpomodoro.data.JsonBackup
import io.github.peicheng0413.flexpomodoro.data.SessionRepository
import io.github.peicheng0413.flexpomodoro.data.Settings
import io.github.peicheng0413.flexpomodoro.timer.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class FlexApp : Application() {
    lateinit var db: AppDatabase
        private set
    lateinit var settings: Settings
        private set
    lateinit var sessions: SessionRepository
        private set
    lateinit var backup: JsonBackup
        private set

    /** 寫入資料用的全域 scope，畫面關掉也不會中斷。 */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        db = AppDatabase.create(this)
        settings = Settings(this)
        sessions = SessionRepository(db, settings)
        backup = JsonBackup(db)
        Notifications.createChannels(this)
    }
}

val Context.app: FlexApp get() = applicationContext as FlexApp
