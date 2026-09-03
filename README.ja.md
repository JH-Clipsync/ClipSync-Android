<p align="center"><img src="icon.png" width="128" alt="ClipSync"/></p>

<h1 align="center">ClipSync for Android</h1>

<p align="center">
  <b>スマホの認証コード & クリップボード → パソコンへリアルタイム同期、通知バナーからワンクリックでコピー。</b><br/>
  <a href="README.md">简体中文</a> ·
  <a href="README.en.md">English</a> ·
  <a href="README.ja.md">日本語</a>
</p>

---

ClipSync は、セルフホスト型のクロスデバイス・メッセージ同期ツールです。本リポジトリは Android クライアントで、**Kotlin + OkHttp + WebSocket** で開発され、**フォアグラウンド常駐サービス**としてバックグラウンドで動作します。

主なユースケース：**スマホで認証コードを受信したり、何かをコピーしたりすると、Windows / macOS に即座に通知が表示され、認証コードや全文をワンクリックでコピーできます。逆に、パソコンでコピーした内容もリアルタイムでスマホのクリップボードへ書き戻されます。**

サードパーティのプッシュサービス（FCM / Xiaomi / Huawei プッシュはいずれも不使用）に依存せず、すべての通信は自分自身の WebSocket 中継サーバーを経由します。エンドツーエンド暗号化も任意で有効化でき、プライバシーは自分自身で管理できます。

## 🌐 公開サーバー（デフォルトで設定済み）

本クライアントのデフォルト `serverURL` は **`wss://www.95qw.com`** です。インストール後、**設定は一切不要**で、アカウントを登録すればすぐ利用できます。

- **自前サーバーを利用する場合**：「設定 → サーバーアドレス」を開いて `wss://あなたのドメイン` に変更してください（パスは不要、リバースプロキシが処理します）。
- **完全オフライン / LAN 環境**：サーバーは Go の単一バイナリ / Docker で 1 行で起動できます。詳細は [ClipSync-Server デプロイ手順](https://github.com/JH-Clipsync/ClipSync-Server#-reverse-proxy--path-planning) を参照してください。
- **ローカル開発時の上書き**：`local.properties` に `clipsync.server=ws://あなたのLAN内IP:8080` を追加し、Gradle で再ビルドしてください。


> 最小システム：**Android 7.0（API 24）** · targetSdk：**35** · 言語：**Kotlin 2.0** · ビルド：**Gradle 8.9 + AGP 8.7**

---

## ✨ 主な機能

| モジュール | 説明 |
|------|------|
| 🔐 **ユーザー名/パスワードログイン** | ユーザー名 + パスワードで認証し、初回接続時に Token を自動取得します。Token の期限切れ時は、ローカルに保存した認証情報で自動的に再ログインします。 |
| 👥 **オンラインデバイス一覧** | メインページに、同じアカウントのオンラインデバイスのプラットフォーム / IP / デバイス ID / オンライン時刻 / 機能タグをリアルタイム表示します。 |
| 🛡️ **エンドツーエンド暗号化** | AES-256-GCM で暗号化し、鍵は PBKDF2-HMAC-SHA256（20 万回）で導出します。設定ページに鍵フィンガープリントを表示します。 |
| 📩 **SMS 認証コードの自動同期** | まず `SmsReceiver` ブロードキャストを優先し、MIUI/HyperOS など SMS ブロードキャストを遮断する機種では `NotificationSmsListener` が通知欄を読み取ってフォールバックします。 |
| 🪟 **通知リスナーによるフォールバック** | システムの SMS 通知を読み取り、SMS データベースを照会して完全な原文を取得します。リスナーの偽死活を検知すると自動で再バインドするウォッチドッグも備えています。 |
| 📋 **クリップボード双方向同期** | ユーザー補助サービスがバックグラウンドでクリップボードを読み書きし、テキストと画像の両方に対応します。自動アップロード / 自動ダウンロードは個別に設定できます。 |
| 📤 **共有メニューからのプッシュ** | 任意のアプリで「共有 → ClipSync」からテキスト / 画像を手動でパソコンへプッシュでき、バックグラウンドのクリップボード制限を回避できます。 |
| 🔔 **フォアグラウンド常駐サービス** | `SyncService` は `foregroundServiceType="dataSync"` で実行され、通知欄に常駐してシステムによるプロセスキルの確率を下げます。 |
| 🚀 **起動時自動起動** | ブート / アプリ更新 / クイックブートのブロードキャストを監視します。「必要なときに開く」方針に従い、同期の開始はユーザーがメインページから手動で行います。 |
| 🇨🇳 **国産 ROM 向け常駐チューニング** | MIUI/HyperOS の「通知型 SMS」「バックグラウンドポップアップ」「バッテリー無制限」「通知アクセスの真接続」について専用の検出と案内を行います。 |
| 🔄 **自動再接続** | ネットワークの揺らぎ / Token 失効時に指数バックオフで自動再接続します。接続パラメータ変更時はサービスを自動的に再起動します。 |
| 📜 **メッセージ履歴** | SMS とクリップボードを分類して永続化し、それぞれ直近 200 件を保存、閲覧、コピー、削除、全件消去に対応します。 |
| 📖 **アドレス / エラー文をコピー可能** | **ステータスカードのサーバーアドレスを長押しでコピー**（システムのコピーメニュー）。接続失敗・コード認識失敗・権限未付与のメッセージも全て選択でき、そのまま Issue / チケットに貼れます。 |
| 🖼️ **画像圧縮** | 共有 / クリップボードの画像を自動的に JPEG へ圧縮します。最長辺 1600px、クオリティ 80% で、大きな画像でも固まりません。 |
| ⚙️ **設定ページ** | サーバーアドレス、アカウント、エンドツーエンド暗号化、クリップボードの切り替え、権限ガイド、履歴、バージョン情報を提供します。 |

---

## 🖼️ 画面構成

| 画面 | 説明 |
|------|------|
| メインページ | 同心円リングの接続状態、サーバーアドレス、クリップボードプレビュー、オンラインデバイス一覧、同期開始 / 停止ボタン。 |
| 設定 | 4 グループのカード：接続 / エンドツーエンド暗号化 / クリップボード同期 / その他（権限、履歴、バージョン）。 |
| 権限ページ | 達成度プログレスバーと項目別チェック。許可済み項目はグレーのチェックに畳まれ、未許可項目にはワンクリック遷移ボタンを表示します。 |
| 履歴 | SMS / クリップボードを統合したタイムラインと画像サムネイル。コピーと削除に対応します。 |

---

## 📦 ダウンロードとインストール

[GitHub Releases](https://github.com/JH-Clipsync/ClipSync-Android/releases) から最新の `ClipSync-<バージョン>.apk` をダウンロードし、スマホで「提供元不明のアプリ」のインストールを許可してインストールしてください。

CI では APK を OCI コンテナイメージとしてパッケージし、GitHub Packages へプッシュしています：

```
ghcr.io/<owner>/clipsync-android:<バージョン>
ghcr.io/<owner>/clipsync-android:latest
```

APK はイメージ内の `/app/clipsync-android.apk` にあり、`docker cp` で取り出せるため、セルフ配布にも便利です。

> アプリは Google Play にも各ベンダーのアプリストアにも公開していません。初回インストール時に「提供元不明」と警告される場合がありますが、正常な動作です。

---

## 🚀 クイックスタート

1. APK をインストールし、ClipSync を開きます。
2. メインページまたは設定で以下を入力します：
   - **サーバーアドレス**（例: `192.168.1.10:8080`。`ws://` / `wss://` に対応）
   - **ユーザー名 / パスワード**（サーバー管理者が割り当てます。クライアントからの新規登録機能はありません）
   - **エンドツーエンド暗号化** を有効にするかどうかと、**同期パスワード**（パソコン側と一致させる必要があります）
3. 「権限」ページの案内に従って各項目を許可します：
   - SMS / 通知権限
   - 通知アクセス（SMS 通知の読み取りフォールバック用）
   - バッテリー最適化の無制限（バックグラウンド停止を防ぐため）
   - MIUI/HyperOS の追加項目：通知型 SMS、バックグラウンドポップアップ
4. メインページに戻って「同期開始」をタップし、状態リングが緑色になれば接続成功です。
5. Windows / macOS クライアントで同じアカウントにログインすると、相互にメッセージを送受信できます。

---

## 🧩 プロジェクト構成

```
ClipSync-Android/
├── app/src/main/java/com/clipsync/
│   ├── MainActivity.kt                    # メインページ: 状態リング、クリップボードプレビュー、オンラインデバイス
│   │
│   ├── net/
│   │   ├── WsClient.kt                    # OkHttp WebSocket、ハートビート、自動再接続
│   │   ├── AuthClient.kt                  # ユーザー名/パスワードログイン、Token キャッシュ
│   │   └── ServerAddress.kt               # アドレス正規化
│   │
│   ├── service/
│   │   ├── SyncService.kt                 # フォアグラウンド常駐サービス（dataSync）、メッセージ振り分け
│   │   └── BootReceiver.kt                # ブート / 更新ブロードキャスト受信プレースホルダ
│   │
│   ├── sms/
│   │   ├── SmsReceiver.kt                 # SMS ブロードキャストレシーバー（優先チャネル）
│   │   ├── SmsDeduplicator.kt             # ブロードキャストと通知の重複除去
│   │   ├── PendingSmsQueue.kt             # 接続準備後にアップロードするためのキュー
│   │   └── NotificationSmsListener.kt     # 通知アクセスによるフォールバック + ウォッチドッグ再バインド
│   │
│   ├── accessibility/
│   │   └── ClipSyncAccessibilityService.kt # ユーザー補助サービス、バックグラウンドクリップボード制限を回避
│   │
│   ├── clip/
│   │   └── ShareActivity.kt               # システム共有メニューのエントリ（テキスト / 画像）
│   │
│   ├── clipboard/
│   │   ├── ClipboardManagerHelper.kt      # クリップボード読み書き + 重複除去
│   │   ├── ClipReaderActivity.kt          # 透過 Activity、バックグラウンド時のクリップボード読み取りをトリガー
│   │   ├── ImageHelper.kt                 # 画像圧縮（JPEG 80%、長辺 1600px）
│   │   ├── ClipboardImageStore.kt         # 画像のディスクキャッシュ
│   │   ├── UploadDeduplicator.kt          # アップロード重複除去（ループ防止）
│   │   └── ScreenshotWatcher.kt           # スクリーンショット監視（任意）
│   │
│   ├── crypto/
│   │   ├── E2EECrypto.kt                  # AES-256-GCM + PBKDF2 の下位実装
│   │   ├── EncEnvelope.kt                 # 暗号化エンベロープのデータ構造
│   │   └── PayloadCipher.kt               # 業務ペイロードの暗号化ラップ + 鍵キャッシュ
│   │
│   ├── history/
│   │   └── HistoryStore.kt                # ローカル履歴（SharedPreferences + JSON）
│   │
│   ├── model/
│   │   └── Message.kt                     # SyncMessage / MessagePayload / OnlineDevice
│   │
│   ├── state/
│   │   ├── ConnectionBus.kt               # グローバル接続状態 Flow
│   │   └── PresenceBus.kt                 # オンラインデバイス一覧 Flow
│   │
│   └── ui/
│       ├── SettingsActivity.kt            # 設定ページ
│       ├── PermissionSettingsActivity.kt  # 権限ガイドページ
│       ├── PermissionStatus.kt            # 権限チェック（MIUI 拡張項目を含む）
│       ├── HistoryActivity.kt             # 履歴ページ
│       ├── Design.kt / DesignToast.kt / DesignDialog.kt / SettingsRows.kt
│       └── ThemeManager.kt
├── app/build.gradle.kts
├── app/src/main/AndroidManifest.xml
└── .github/workflows/release.yml          # GitHub Actions: APK ビルド + Release + GHCR
```

### 技術スタック

- **Kotlin 2.0** + **Coroutines**
- **OkHttp 4.12** + ネイティブ `WebSocket`
- **kotlinx.serialization**（JSON、リフレクション不使用）
- **AndroidX AppCompat / Activity Result / Lifecycle**
- 純粋なコード View（XML レイアウトなし、Compose なし）、ダークテーマは `ThemeManager` が管理
- 最小サポート **Android 7.0（API 24）**、targetSdk **35**

---

## 🔧 ソースからのビルド

### 前提条件

- JDK 17（AGP 8.7 で必要）
- Android SDK Platform 35 + Build-Tools 35.x
- Gradle 8.9（リポジトリ同梱の `gradlew` が自動的にダウンロードします）

### コマンドライン

```bash
# リポジトリをクローン
git clone https://github.com/JH-Clipsync/ClipSync-Android.git
cd ClipSync-Android

# Debug ビルド
./gradlew :app:assembleDebug

# Release ビルド（署名設定がない場合は debug 署名を使用）
./gradlew :app:assembleRelease

# 成果物
# app/build/outputs/apk/debug/app-debug.apk
# app/build/outputs/apk/release/app-release.apk
```

### Android Studio を使う場合

1. Android Studio Lady Gaga（2024.2.1）以降でプロジェクトを開きます。
2. Gradle Sync の完了を待ちます。
3. USB デバッグを有効にしたスマホを接続し、▶ をクリックして実行します。

### カスタム署名

プロジェクトのルートに `local.properties` を作成します（`.gitignore` で既に無視されています）：

```properties
clipsync.storeFile=/absolute/path/your-release.jks
clipsync.storePassword=xxx
clipsync.keyAlias=clipsync
clipsync.keyPassword=xxx
clipsync.server=ws://192.168.1.1:8080
clipsync.token=
```

デフォルトのサーバーアドレス、デフォルトのユーザー名、バージョン番号などは、`app/build.gradle.kts` の `buildFeatures.buildConfig` で調整できます。

---

## 🔐 権限について

| 権限 | 用途 | 必須かどうか |
|------|------|----------|
| `INTERNET` / `ACCESS_NETWORK_STATE` | セルフホストの WebSocket サーバーへ接続 | ✅ 必須 |
| `RECEIVE_SMS` / `READ_SMS` | システム SMS ブロードキャストで認証コードを受信、直近 60 秒の SMS 原文を読み取り | 推奨 |
| `POST_NOTIFICATIONS`（Android 13+） | フォアグラウンド常駐通知や同期状態通知を表示 | ✅ 必須 |
| `READ_MEDIA_IMAGES`（Android 13+） / `READ_EXTERNAL_STORAGE` | 共有 / クリップボード内の画像を読み取り | 画像同期に必要 |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` | `dataSync` タイプでフォアグラウンド常駐サービスを開始 | ✅ 必須 |
| `RECEIVE_BOOT_COMPLETED` | システム起動イベントを検知（既定ではサービスを自動起動しません） | 任意 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | バックグラウンド停止を防ぐため、アプリをバッテリーホワイトリストへ追加するよう案内 | 推奨 |
| 通知アクセス（`NotificationListenerService`） | MIUI/HyperOS などが SMS ブロードキャストを遮断する場合、通知欄から SMS を読み取ってフォールバック | 国産 ROM で推奨 |
| ユーザー補助サービス（`AccessibilityService`） | バックグラウンドでクリップボードを読み書き（Android 10+ のバックグラウンドクリップボード制限を回避） | 双方向クリップボード同期に必要 |
| MIUI「通知型 SMS」「バックグラウンドポップアップ」 | Xiaomi / Redmi 機種で認証コードを受信し、バックグラウンド通知を表示するための専用権限 | MIUI で推奨 |

> すべての権限は「設定 → 権限」ページでリアルタイムに状態を確認でき、未許可の項目にはワンクリック遷移ボタンが表示されます。

---

## 🐛 トラブルシューティング

| 症状 | 確認事項 |
|------|------|
| サーバーに接続できない | アドレス / ポートや、スマホとサーバーが同じネットワークか確認し、`localhost` ではなく `ws://IP:ポート` の利用を推奨します。HTTPS リバースプロキシを使う場合は `wss://` を使用してください。 |
| 認証コードを受信できない | 1) SMS と通知権限を付与; 2) MIUI の場合、権限ページで「通知型 SMS」「バックグラウンドポップアップ」を有効化; 3) 「通知アクセス」スイッチが**接続済み**状態であることを確認（許可済みでは不十分な場合があります）。 |
| 通知アクセスは許可済みなのに反応しない | MIUI の既知の不具合です。権限ページの「通知リスナーを再バインド」をタップするか、スイッチをオフ/オンし直してください。ウォッチドッグが 10 分ごとに偽死活を検知して自動的に再バインドします。 |
| SMS が重複してプッシュされる | `SmsReceiver` と `NotificationSmsListener` の 2 チャネルで受信するため、送信者 + 5 分ウィンドウで重複除去しています。それでも重複する場合は、ログを添えて Issues へご報告ください。 |
| クリップボードがアップロードされない | MIUI などの ROM がバックグラウンドのクリップボードを制限しています。ユーザー補助サービスを有効にするか、システムの「共有 → ClipSync」から手動でプッシュしてください。 |
| サービスが頻繁にキルされる | 権限ページで「バッテリー無制限」を有効にしてください。MIUI の場合は追加でシステム設定でバックグラウンドロック、自動起動を許可し、「神隠しモード / バッテリーセーバー」を無効にしてください。 |
| メッセージは届くが復号に失敗する | 両端の「同期パスワード」が一致しないか、片方が暗号化を有効にしていません。設定ページの鍵フィンガープリントを照合してください。 |
| 画像の同期に失敗する | 画像 / ストレージ権限が付与されているか確認してください。大きすぎる画像は自動的に圧縮されますが、極端に大きい場合（5MB 超）はサーバー側で拒否される可能性があります。 |
| 起動後に自動で開始しない | 現在の設計では BootReceiver はユーザーの邪魔をしないよう、サービスを自動起動しません。メインページの「同期開始」をタップするか、「起動時に自動接続」を有効にしてください。 |

ログの確認：

```bash
# リアルタイムログ
adb logcat -s ClipSync SyncService WsClient SmsReceiver NotifSmsListener
```

---

## 🔐 プライバシーとセキュリティ

| 観点 | 設計 |
|------|------|
| 通信経路 | 自分自身のサーバーを経由し、サードパーティのプッシュ / 分析 / クラッシュレポートサービスは通りません。 |
| データ保存 | サーバー側には保存されません。Android 側のデータはアプリのプライベートディレクトリ（`/data/data/com.clipsync/`）にあります。 |
| アカウント情報 | ユーザー名、パスワード、Token は自動再接続のために `SharedPreferences`（`clipsync.xml`）に保存されます。 |
| 履歴 | SMS とクリップボードはそれぞれ直近 200 件を保存し（SharedPreferences + JSON）、アプリ内からワンクリックで消去できます。 |
| SMS 内容 | SMS は受信した瞬間にあなたの他のデバイスへ転送されるだけです。連絡先や SMS 履歴がアップロードされることはありません。 |
| エンドツーエンド暗号化 | AES-256-GCM。鍵は同期パスワードから PBKDF2-HMAC-SHA256（20 万回）で導出され、ソルトはクライアントに固定され、アップロードされません。 |
| 鍵キャッシュ | マルチスロット LRU キャッシュで最大 4 本の導出鍵を保持し、メッセージごとに 20 万回 PBKDF2 を実行しないようにしています。 |
| 最小権限 | 連絡先、位置情報、カメラ / マイクは読み取りません。必要なときにだけ SMS とクリップボードを読み取ります。 |
| 本番運用の推奨 | Nginx / Caddy をサーバーの前に置いて TLS を有効化し、クライアントでは `wss://` アドレスを利用してください。 |

---

## 🤝 関連プロジェクト

| プロジェクト | 技術スタック | リンク |
|------|--------|------|
| サーバー | Go + gorilla/websocket | https://github.com/JH-Clipsync/ClipSync-Server |
| Windows クライアント | .NET 8 + WPF | https://github.com/JH-Clipsync/ClipSync-Windows |
| macOS クライアント | Swift + SwiftUI | https://github.com/JH-Clipsync/ClipSync-Mac |
| 管理バックエンド | Go + Gin | https://github.com/JH-Clipsync/ClipSync-Admin |
| 管理フロントエンド | Vue 3 + Vite | https://github.com/JH-Clipsync/ClipSync-Admin-Web |

---

## 📄 License

個人利用のプロジェクトです。コードは自由に参照・改変いただけます。

---

**Made with ❤️ · 3 クライアントすべて自作 · プライバシーはあなたのもの**
