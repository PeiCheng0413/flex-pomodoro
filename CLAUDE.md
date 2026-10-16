# flex-pomodoro

Android 習慣追蹤＋彈性蕃茄鐘（Kotlin、Jetpack Compose、Room）。規格以 [SPEC.md](SPEC.md) 為準，這裡不重抄；
已定案與刻意不做的事記在 [docs/BACKLOG.md](docs/BACKLOG.md)。

## 結構

- `timer/`：計時規則（`TimerLogic.kt` 純邏輯）、前景服務、通知、鬧鐘。
- `habit/HabitLogic.kt`：每日工作時間、達成判定、週達標、連續週數、熱圖色階。純邏輯，不碰 Android。
- `data/`：Room（`Database.kt`）、`SessionRepository`（狀態切換）、`HabitRepository`（習慣與目標）、`JsonBackup`。
- `ui/`：`HabitsScreen`（首頁兼統計）、`TimerScreen`、`SettingsScreen`、`FocusScreen`（橫向）、`Heatmap`、`HabitDialog`。
  `rememberHabitBoard` 是習慣頁與計時頁共用的進度資料。

## 地雷區

- **計時只能從資料庫的時間戳記推算**，不能在記憶體累加。App 被殺、重開機都要能恢復。
- **改資料表一定要寫 migration**，並把 KSP 產生的 `app/schemas/.../N.json` 一起進版控。不能用 destructive migration。
- **習慣不能刪除，只能封存**；唯一會移除習慣的是「合併」（被併掉的已經沒有紀錄）。
- **改目標不改寫過去**：目標存在 `habit_goals` 變更紀錄裡，每天依當時生效的目標判定。只有使用者勾「也套用到過去」才重寫。
- **每週目標是天數，不是總時數**。
- **手動標記只能往上加、不灌分鐘數**：計時紀錄是事實，標記不能把計時達標的日子點掉，也不能改變總時數。
- 跨午夜的專注要依時間切開分給兩天；一週從星期一開始。
- `sessions.name` 是開始當下的名稱快照，畫面一律用習慣的名稱；`habitId` 為 null 是未歸類（單純計時）。
- 常駐通知上刻意**沒有**「結束」；結束只能在直向畫面長按。
- 版本號由 git 標籤決定（CI 傳 `-PappVersionName`），不要手改 `build.gradle.kts` 的版本。
- 簽署金鑰（`keystore/`、`keystore.properties`）不進版控。本機和 CI 必須用同一把金鑰。

## 常用指令

```bash
./gradlew testDebugUnitTest     # 單元測試（TimerLogicTest、HabitLogicTest）
./gradlew installDebug          # 裝 debug 版到已連線的手機（不需金鑰，和正式版並存）
```

發版：推 `v*` 標籤，GitHub Actions 會跑測試、簽署、發布 Release，手機用 Obtainium 更新。
