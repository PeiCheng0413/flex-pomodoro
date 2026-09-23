# 彈性蕃茄鐘

給 Android（Pixel 9 Pro）用的彈性蕃茄鐘：工作正計時，按下休息時把工作時間的 20% 變成休息倒數，超過就變負數，接著再開始下一輪。

完整規則見 [SPEC.md](SPEC.md)。

## 安裝

1. 在手機上安裝 [Obtainium](https://github.com/ImranR98/Obtainium)。
2. 新增 App，網址填 `https://github.com/PeiCheng0413/flex-pomodoro`。
3. Obtainium 會下載最新的 APK，之後有新版會通知更新。

## 開發

需要 Java 17 與 Android SDK（`brew install openjdk@17 android-commandlinetools android-platform-tools`）。

```bash
./gradlew testDebugUnitTest     # 單元測試
./gradlew installRelease        # 編譯並安裝到已連線的手機（需要 keystore.properties）
```

### 發布新版

```bash
git tag v1.0.0 && git push origin v1.0.0
```

GitHub Actions 會用 repo Secrets 裡的金鑰（`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`）簽署 APK 並發布到 Releases。本機和 CI 必須使用同一把金鑰，手機才能直接覆蓋更新。
