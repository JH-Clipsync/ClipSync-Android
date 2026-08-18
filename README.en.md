<p align="center">
  <a href="README.md">中文</a> · <a href="README.en.md">English</a> · <a href="README.ja.md">日本語</a>
</p>

<p align="center">
  <img src="icon.png" width="120" alt="ClipSync"/>
</p>

# ClipSync-Android

<p align="center">
  <b>The Android client of the ClipSync three-device sync system</b><br/>
  Automatically listens for SMS verification codes, monitors the clipboard, and pushes text/images via the share menu — syncing to your computer in real time.<br/>
  Kotlin + Foreground Service + Accessibility Service, deeply adapted to keep background processes alive on Chinese ROMs.
</p>

<p align="center">
  <a href="https://github.com/JH-Clipsync/ClipSync-Android/releases">⬇️ Download APK</a> ·
  <a href="https://github.com/orgs/JH-Clipsync/packages">📦 Packages (ghcr.io)</a> ·
  <a href="https://github.com/JH-Clipsync/ClipSync-Server">🖧 Server</a> ·
  <a href="https://github.com/JH-Clipsync/ClipSync-Mac">🖥️ Mac Client</a>
</p>

---

## 1. What It Can Do

| Feature | Description | Implementation |
|---|---|---|
| **SMS verification code sync** | Automatically detects 4–8 digit codes from incoming verification SMS and pushes them to your computer | `SmsReceiver` broadcast + `NotificationSmsListener` notification listener (bypasses MIUI interception) |
| **Automatic clipboard sync** | Copy text/image on your phone → your computer receives it and can automatically write it to the clipboard | Accessibility Service reads clipboard in background (bypasses Android 10+ restrictions) |
| **Share menu push** | In any app, long-press to select text/image → Share → choose ClipSync | `ShareActivity` (independent task; returns to the source app after pushing) |
| **History** | Locally saves pushed content for later review | `HistoryActivity` + local storage |
| **Auto-start on boot** | Automatically restores the sync service after reboot/upgrade | `BootReceiver` |
| **Background keep-alive** | Persistent foreground notification + request to ignore battery optimizations | `SyncService` (foregroundServiceType=dataSync) |

## 2. Download and Installation

Download `ClipSync-vX.Y.Z-release.apk` from [Releases](https://github.com/JH-Clipsync/ClipSync-Android/releases) (signed with v2 signature scheme).

1. Transfer it to your phone and open to install (you may need to enable "Allow installation of apps from unknown sources" the first time)
2. Open the app → in Settings, fill in the **Server address** (e.g. `ws://192.168.1.100:8080`) and **Token** (use the same token as the Mac client to pair automatically)
3. Follow the prompts to grant permissions (see next section) → tap "Start sync service" → a persistent notification appears in the notification shade when successful

### Permission List and Their Uses

| Permission | Purpose |
|---|---|
| SMS (RECEIVE/READ_SMS) | Listen for verification codes |
| Notification access | Fallback channel when MIUI etc. intercept SMS broadcasts |
| Accessibility Service | Read/write clipboard in background (a compliant workaround under Android 10+ restrictions) |
| Foreground service / Wake lock | Keep alive in background |
| Ignore battery optimizations | Prevent Chinese ROMs from killing the background process |
| Read media library | Sync screenshots/images from the clipboard |

## 3. Message Protocol (Agreed with the Server)

```json
{ "type": "notify_pc", "kind": "sms_code", "text": "【Some Bank】Verification code 314159" }
```

- `notify_pc`: sent only to the PC client (for verification code scenarios)
- `clipboard`: broadcast to all devices; each receiver decides whether to write to the clipboard according to its own switch
- Devices sharing the same token are automatically grouped; the server does not store any data

## 4. Development Environment Setup

### 1. Install Android Studio

Get it from https://developer.android.com/studio . On first launch, follow the wizard to install the SDK (API 34).

### 2. Command-line environment variables

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
source ~/.zshrc
```

### 3. On-device debugging

Settings → About phone → tap Build number 7 times → Developer options → USB debugging → connect to computer via data cable and select "File transfer".

### 4. Run

In Android Studio, Open this directory → Gradle Sync → select device → ▶️ (Shift+F10).
Or from the command line: `./gradlew installDebug`.

## 5. Building

### Debug build (for personal use)

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk
```

### Signed release build (for distribution)

Signing configuration is read from `local.properties` (this file is gitignored — **no keys are committed to the repository**):

```properties
clipsync.storeFile=../keystore/release.jks
clipsync.storePassword=***
clipsync.keyAlias=clipsync
clipsync.keyPassword=***
```

```bash
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk (automatically signed)
```

If there is no keystore, `signingConfigs` is not created, and the resulting APK will be unsigned (cannot be installed; used only as a CI fallback).

### Automated CI build (recommended)

Push a tag to automatically build a signed APK, publish a Release, and push the container image:

```bash
git tag v1.2.0 && git push origin v1.2.0
```

The signing key is stored as base64 in repository Secrets (four entries such as `ANDROID_KEYSTORE_B64`); the workflow injects them automatically, and no local configuration is needed.

## 6. Project Structure

```
app/src/main/java/...
├── MainActivity.kt              # Home screen: connection status, start/stop service
├── clip/ShareActivity.kt        # Share menu entry point
├── clipboard/ClipReaderActivity.kt  # MIUI clipboard reading fallback (transparent one-frame activity)
├── sms/SmsReceiver.kt           # SMS broadcast receiver
├── sms/NotificationSmsListener.kt   # Notification listener fallback
├── accessibility/ClipSyncAccessibilityService.kt  # Background clipboard
├── service/SyncService.kt       # Persistent foreground sync service (WS client host)
├── service/BootReceiver.kt      # Auto-start on boot
└── ui/                          # Settings / feature settings / permission settings / history
```

## 7. FAQ

| Problem | Solution |
|---|---|
| Not receiving verification codes | Grant SMS permission + notification access; disable battery optimization |
| Clipboard not syncing | Enable the "ClipSync clipboard sync" accessibility service |
| Background process gets killed | Lock the app / add it to the whitelist / allow auto-start |
| Cannot connect to server | Same WiFi; allow port 8080 through the firewall; use address `ws://IP:8080` |
| ClipSync not appearing in the share menu | Reboot the system or reinstall the app |
