<p align="center"><img src="icon.png" width="128" alt="ClipSync"/></p>

<h1 align="center">ClipSync for Android</h1>

<p align="center">
  <b>Phone verification codes & clipboard → synced to desktop in real time, with one-click copy from notification banners.</b><br/>
  <a href="README.md">简体中文</a> ·
  <a href="README.en.md">English</a> ·
  <a href="README.ja.md">日本語</a>
</p>

---

ClipSync is a self-hosted, cross-device message sync tool. This repository contains the Android client, built with **Kotlin + OkHttp + WebSocket** and running in the background as a **foreground keep-alive service**.

Core scenario: **When you receive a verification code or copy something on your phone, a notification instantly pops up on Windows / macOS, letting you copy the code or the full text in one click. Conversely, content copied on the computer is written back to the phone clipboard in real time.**

It doesn't depend on any third-party push service (FCM / Xiaomi / Huawei pushes are not used); all traffic goes through your own WebSocket relay, with optional end-to-end encryption, so privacy stays under your control.

## 🌐 Public Server (Pre-configured)

The default `serverURL` in this client is **`wss://www.95qw.com`**. After installation, **no setup is required** — just register an account and you're in.

- **Self-hosted instance?** Open *Settings → Server URL* and change it to `wss://your-domain` (no path; the reverse proxy handles the path).
- **Fully offline / LAN?** The server is a single Go binary / Docker container. See the [ClipSync-Server deployment guide](https://github.com/JH-Clipsync/ClipSync-Server#-reverse-proxy--path-planning).
- **Local dev override:** add `clipsync.server=ws://your-LAN-IP:8080` to `local.properties` and rebuild.


> Minimum system: **Android 7.0 (API 24)** · targetSdk: **35** · Language: **Kotlin 2.0** · Build: **Gradle 8.9 + AGP 8.7**

---

## ✨ Key Features

| Module | Description |
|------|------|
| 🔐 **Username & password login** | Authenticates with username + password and automatically exchanges for a Token on first connect; when the Token expires, the locally saved credentials are used to log in again automatically. |
| 👥 **Online device list** | The main page shows online devices under the same account in real time, including platform / IP / device ID / online time / capability tags. |
| 🛡️ **End-to-end encryption** | AES-256-GCM with keys derived via PBKDF2-HMAC-SHA256 (200,000 iterations); the settings page displays the key fingerprint. |
| 📩 **Automatic SMS verification-code sync** | `SmsReceiver` broadcast is used first; on ROMs like MIUI/HyperOS that block SMS broadcasts, `NotificationSmsListener` reads the notification shade as a fallback. |
| 🪟 **Notification-listener fallback** | Reads system SMS notifications and queries the SMS database to retrieve the full original text; includes a watchdog that automatically rebinds when it detects a dead listener. |
| 📋 **Bidirectional clipboard sync** | An Accessibility Service reads/writes the clipboard in the background, supporting both text and images; auto-upload and auto-download can be configured separately. |
| 📤 **Share menu push** | In any app, "Share → ClipSync" lets you manually push text/images to the computer, bypassing background clipboard restrictions. |
| 🔔 **Foreground keep-alive service** | `SyncService` runs with `foregroundServiceType="dataSync"`, stays in the notification shade, and lowers the chance of being killed by the system. |
| 🚀 **Launch at boot** | Listens for boot / app-update / quick-boot broadcasts; following an "open when needed" principle, the user starts sync manually from the main page. |
| 🇨🇳 **Domestic-ROM keep-alive adaptation** | Special detection and guidance for MIUI/HyperOS "notification-category SMS", "background pop-ups", "unrestricted battery", and "notification-access truly connected". |
| 🔄 **Automatic reconnect** | Automatically reconnects on network jitter / Token failure with exponential backoff; service restarts automatically when connection parameters change. |
| 📜 **Message history** | SMS and clipboard are persisted separately, each keeping the latest 200 entries, with view, copy, delete, and clear-all support. |
| 📖 **Copyable address / error text** | **Long-press the server address on the status card to copy it** (system copy menu). All connection-failure, code-recognition and permission prompts are also selectable, so you can paste them straight into issues / tickets. |
| 🖼️ **Image compression** | Shared / clipboard images are automatically compressed to JPEG, longest side 1600px, quality 80%, so even large images won't stall the app. |
| ⚙️ **Settings page** | Server address, account, end-to-end encryption, clipboard toggles, permission guidance, history, and version info. |

---

## 🖼️ Interface Overview

| Page | Description |
|------|------|
| Main page | Concentric-ring connection status, server address, clipboard preview, online device list, and start/stop sync button. |
| Settings | Four groups of cards: Connection / End-to-end encryption / Clipboard sync / Other (permissions, history, version). |
| Permissions page | A completion progress bar plus item-by-item checks; granted items collapse into a gray check, while missing ones offer a one-click jump. |
| History | SMS / clipboard merged timeline with image thumbnails; copy and delete supported. |

---

## 📦 Download & Install

Go to [GitHub Releases](https://github.com/JH-Clipsync/ClipSync-Android/releases) and download the latest `ClipSync-<version>.apk`, then allow "apps from this source" on your phone and install it.

CI also packages the APK as an OCI container image and pushes it to GitHub Packages:

```
ghcr.io/<owner>/clipsync-android:<version>
ghcr.io/<owner>/clipsync-android:latest
```

The APK is located inside the image at `/app/clipsync-android.apk`, and can be extracted with `docker cp`, making self-hosted distribution easy.

> The app is not published on Google Play, nor on any vendor store; on first install the system may warn about "unknown sources", which is expected.

---

## 🚀 Quick Start

1. Install the APK and open ClipSync.
2. On the main page or in Settings, fill in:
   - **Server address** (e.g. `192.168.1.10:8080`; `ws://` / `wss://` are supported)
   - **Username / password** (assigned by the server administrator; the client does not offer sign-up)
   - Whether to enable **end-to-end encryption** and the **sync password** (must match the desktop end)
3. Follow the "Permissions" page and grant each item:
   - SMS / notification permissions
   - Notification access (used as a fallback to read SMS notifications)
   - Unrestricted battery policy (to prevent background freezing)
   - MIUI/HyperOS extras: notification-category SMS, background pop-ups
4. Return to the main page and tap "Start sync"; when the status ring turns green, the connection succeeded.
5. Log in with the same account on the Windows / macOS client and you can exchange messages.

---

## 🧩 Project Structure

```
ClipSync-Android/
├── app/src/main/java/com/clipsync/
│   ├── MainActivity.kt                    # Main page: status ring, clipboard preview, online devices
│   │
│   ├── net/
│   │   ├── WsClient.kt                    # OkHttp WebSocket, heartbeat, auto-reconnect
│   │   ├── AuthClient.kt                  # Username/password login, Token cache
│   │   └── ServerAddress.kt               # Address normalization
│   │
│   ├── service/
│   │   ├── SyncService.kt                 # Foreground keep-alive service (dataSync), message dispatch
│   │   └── BootReceiver.kt                # Boot / update broadcast placeholder
│   │
│   ├── sms/
│   │   ├── SmsReceiver.kt                 # SMS broadcast receiver (primary channel)
│   │   ├── SmsDeduplicator.kt             # Deduplication between broadcast and notifications
│   │   ├── PendingSmsQueue.kt             # Queue for uploading after the connection is ready
│   │   └── NotificationSmsListener.kt     # Notification-access fallback + watchdog rebind
│   │
│   ├── accessibility/
│   │   └── ClipSyncAccessibilityService.kt # Accessibility Service, bypasses background clipboard limits
│   │
│   ├── clip/
│   │   └── ShareActivity.kt               # System share-menu entry point (text / images)
│   │
│   ├── clipboard/
│   │   ├── ClipboardManagerHelper.kt      # Clipboard read/write + deduplication
│   │   ├── ClipReaderActivity.kt          # Transparent Activity, triggers clipboard reads in background
│   │   ├── ImageHelper.kt                 # Image compression (JPEG 80%, long edge 1600px)
│   │   ├── ClipboardImageStore.kt         # On-disk image cache
│   │   ├── UploadDeduplicator.kt          # Upload deduplication (prevents loops)
│   │   └── ScreenshotWatcher.kt           # Screenshot watcher (optional)
│   │
│   ├── crypto/
│   │   ├── E2EECrypto.kt                  # AES-256-GCM + PBKDF2 low-level implementation
│   │   ├── EncEnvelope.kt                 # Encryption envelope data structure
│   │   └── PayloadCipher.kt               # Business payload encryption + key cache
│   │
│   ├── history/
│   │   └── HistoryStore.kt                # Local history (SharedPreferences + JSON)
│   │
│   ├── model/
│   │   └── Message.kt                     # SyncMessage / MessagePayload / OnlineDevice
│   │
│   ├── state/
│   │   ├── ConnectionBus.kt               # Global connection-state Flow
│   │   └── PresenceBus.kt                 # Online device list Flow
│   │
│   └── ui/
│       ├── SettingsActivity.kt            # Settings page
│       ├── PermissionSettingsActivity.kt  # Permission guidance page
│       ├── PermissionStatus.kt            # Permission checks (including MIUI extensions)
│       ├── HistoryActivity.kt             # History page
│       ├── Design.kt / DesignToast.kt / DesignDialog.kt / SettingsRows.kt
│       └── ThemeManager.kt
├── app/build.gradle.kts
├── app/src/main/AndroidManifest.xml
└── .github/workflows/release.yml          # GitHub Actions: build APK + Release + GHCR
```

### Tech Stack

- **Kotlin 2.0** + **Coroutines**
- **OkHttp 4.12** + native `WebSocket`
- **kotlinx.serialization** (JSON, no reflection)
- **AndroidX AppCompat / Activity Result / Lifecycle**
- Pure code Views (no XML layouts, no Compose); dark theme is handled by `ThemeManager`
- Minimum supported **Android 7.0 (API 24)**, targetSdk **35**

---

## 🔧 Building from Source

### Prerequisites

- JDK 17 (required by AGP 8.7)
- Android SDK Platform 35 + Build-Tools 35.x
- Gradle 8.9 (the repo's `gradlew` downloads it automatically)

### Command line

```bash
# Clone the repository
git clone https://github.com/JH-Clipsync/ClipSync-Android.git
cd ClipSync-Android

# Debug build
./gradlew :app:assembleDebug

# Release build (uses debug signing if no signing config is provided)
./gradlew :app:assembleRelease

# Outputs
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

### With Android Studio

1. Open the project with Android Studio Lady Gaga (2024.2.1) or later.
2. Wait for Gradle Sync to finish.
3. Connect a phone with USB debugging enabled and click ▶ to run.

### Custom signing

Create `local.properties` in the project root (it's already ignored by `.gitignore`) and add:

```properties
clipsync.storeFile=/absolute/path/your-release.jks
clipsync.storePassword=xxx
clipsync.keyAlias=clipsync
clipsync.keyPassword=xxx
clipsync.server=ws://192.168.1.1:8080
clipsync.token=
```

The default server address, default username, version code, etc. can all be adjusted in `buildFeatures.buildConfig` inside `app/build.gradle.kts`.

---

## 🔐 Permissions

| Permission | Purpose | Required? |
|------|------|----------|
| `INTERNET` / `ACCESS_NETWORK_STATE` | Connect to the self-hosted WebSocket server | ✅ Required |
| `RECEIVE_SMS` / `READ_SMS` | Receive verification codes via the system SMS broadcast; read the original SMS from the last 60 seconds | Recommended |
| `POST_NOTIFICATIONS` (Android 13+) | Show the foreground keep-alive notification and sync-status notifications | ✅ Required |
| `READ_MEDIA_IMAGES` (Android 13+) / `READ_EXTERNAL_STORAGE` | Read images from shares / the clipboard | Needed for image sync |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | Start the foreground keep-alive service as `dataSync` | ✅ Required |
| `RECEIVE_BOOT_COMPLETED` | Detect system boot events (does not auto-start the service by default) | Optional |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Guide the user to add the app to the battery allowlist to prevent background freezing | Recommended |
| Notification access (`NotificationListenerService`) | On MIUI/HyperOS etc. that block SMS broadcasts, read SMS from the notification shade as a fallback | Recommended on domestic ROMs |
| Accessibility Service (`AccessibilityService`) | Read/write the clipboard in the background (bypasses Android 10+ background clipboard limits) | Needed for bidirectional clipboard sync |
| MIUI "notification-category SMS" / "background pop-ups" | Xiaomi/Redmi-specific permissions for receiving verification codes and showing background pop-ups | Recommended on MIUI |

> All permission statuses can be seen in real time on the "Settings → Permissions" page, and items that haven't been granted show a one-click jump button.

---

## 🐛 Troubleshooting

| Symptom | What to check |
|------|------|
| Can't connect to the server | Check address/port and whether the phone and server are on the same network; prefer `ws://IP:port` over `localhost`; if using HTTPS reverse proxy, use `wss://`. |
| Verification codes aren't received | 1) Grant SMS and notification permissions; 2) On MIUI, enable "notification-category SMS" and "background pop-ups" on the permission page; 3) Confirm the "Notification access" switch is in the **Connected** state (not just authorized). |
| Notification access shows authorized but nothing happens | Known MIUI bug; tap "Rebind notification listener" on the permission page, or turn the switch off and on again; the watchdog detects a dead listener every 10 minutes and rebinds automatically. |
| Duplicate SMS pushes | `SmsReceiver` and `NotificationSmsListener` form two channels; the deduplicator keys on sender + a 5-minute window. If duplicates persist, please report logs via Issues. |
| Clipboard doesn't upload | MIUI and other ROMs restrict background clipboard access; enable the Accessibility Service, or use the system "Share → ClipSync" to push manually. |
| The service keeps getting killed | Enable "Unrestricted battery policy" on the permission page; on MIUI additionally lock the app in the background, allow auto-start in system settings, and turn off "battery saver / hidden mode". |
| Messages arrive but decryption fails | The "sync password" differs between ends, or one end hasn't enabled encryption; compare the key fingerprints on the settings page. |
| Image sync fails | Check whether image/storage permissions have been granted; oversized images are compressed automatically, but in extreme cases (> 5MB) the server may reject them. |
| The app doesn't auto-start after boot | By design, BootReceiver does not auto-start the service to avoid disturbing the user; tap "Start sync" on the main page, or enable "Auto-connect on launch". |

Viewing logs:

```bash
# Live logs
adb logcat -s ClipSync SyncService WsClient SmsReceiver NotifSmsListener
```

---

## 🔐 Privacy & Security

| Aspect | Design |
|------|------|
| Data in transit | Goes through your own server; no third-party push / analytics / crash-reporting services are involved. |
| Data storage | The server stores nothing; Android-side data lives in the app's private directory (`/data/data/com.clipsync/`). |
| Account info | Username, password, and Token are only stored in `SharedPreferences` (`clipsync.xml`) for automatic reconnect. |
| History | SMS and clipboard each keep the latest 200 entries (SharedPreferences + JSON), and can be cleared in one click from inside the app. |
| SMS content | SMS is only forwarded to your other devices the moment it arrives; contacts and SMS history are never uploaded. |
| End-to-end encryption | AES-256-GCM; keys are derived from the sync password via PBKDF2-HMAC-SHA256 (200,000 iterations); the salt is fixed on the client and never uploaded. |
| Key cache | A multi-slot LRU cache holds up to 4 derived keys, avoiding 200k PBKDF2 iterations for every message. |
| Least privilege | Doesn't read contacts, location, camera, or microphone; only reads SMS and the clipboard when necessary. |
| Production advice | Put Nginx / Caddy in front of the server with TLS, and use `wss://` addresses in the client. |

---

## 🤝 Related Projects

| Project | Tech stack | Link |
|------|--------|------|
| Server | Go + gorilla/websocket | https://github.com/JH-Clipsync/ClipSync-Server |
| Windows client | .NET 8 + WPF | https://github.com/JH-Clipsync/ClipSync-Windows |
| macOS client | Swift + SwiftUI | https://github.com/JH-Clipsync/ClipSync-Mac |
| Admin backend | Go + Gin | https://github.com/JH-Clipsync/ClipSync-Admin |
| Admin frontend | Vue 3 + Vite | https://github.com/JH-Clipsync/ClipSync-Admin-Web |

---

## 📄 License

A personal, self-use project; feel free to study and modify the code.

---

**Made with ❤️ · Fully self-built across three platforms · Your privacy belongs to you**
