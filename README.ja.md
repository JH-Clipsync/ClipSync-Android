<p align="center">
  <a href="README.md">中文</a> · <a href="README.en.md">English</a> · <a href="README.ja.md">日本語</a>
</p>

<p align="center">
  <img src="icon.png" width="120" alt="ClipSync"/>
</p>

# ClipSync-Android

<p align="center">
  <b>ClipSync 3端末同期システムの Android クライアント</b><br/>
  SMS 認証コードを自動でリッスンし、クリップボードを監視し、共有メニューからテキスト/画像をプッシュして、あなたの PC へリアルタイム同期。<br/>
  Kotlin + フォアグラウンドサービス + ユーザー補助サービス。中国産 ROM のバックグラウンド維持にも深く対応。
</p>

<p align="center">
  <a href="https://github.com/JH-Clipsync/ClipSync-Android/releases">⬇️ APK をダウンロード</a> ·
  <a href="https://github.com/orgs/JH-Clipsync/packages">📦 Packages (ghcr.io)</a> ·
  <a href="https://github.com/JH-Clipsync/ClipSync-Server">🖧 サーバー</a> ·
  <a href="https://github.com/JH-Clipsync/ClipSync-Mac">🖥️ Mac クライアント</a>
</p>

---

## 1. できること

| 機能 | 説明 | 実装方式 |
|---|---|---|
| **SMS 認証コード同期** | 認証コード SMS を受信すると 4〜8 桁の数字を自動認識し、PC にプッシュ | `SmsReceiver` ブロードキャスト + `NotificationSmsListener` 通知リスナー（MIUI の傍受を回避） |
| **クリップボード自動同期** | スマホでテキスト/画像をコピー → PC が受信して自動でクリップボードに書き込み可能 | ユーザー補助サービスがバックグラウンドでクリップボードを読み取り（Android 10+ の制限を回避） |
| **共有メニューからのプッシュ** | 任意のアプリで文字/画像を長押し選択 → 共有 → ClipSync を選択 | `ShareActivity`（独立タスク。プッシュ後に元のアプリへ復帰） |
| **履歴** | プッシュした内容をローカルに保存し、あとから閲覧可能 | `HistoryActivity` + ローカルストレージ |
| **起動時自動開始** | 再起動/アップグレード後に同期サービスを自動復帰 | `BootReceiver` |
| **バックグラウンド維持** | 常駐フォアグラウンド通知 + 電池最適化の無視を要求 | `SyncService`（foregroundServiceType=dataSync） |

## 2. ダウンロードとインストール

[Releases](https://github.com/JH-Clipsync/ClipSync-Android/releases) から `ClipSync-vX.Y.Z-release.apk` をダウンロードしてください（v2 署名方式で署名済み）。

1. スマホに転送して開き、インストールします（初回は「提供元不明のアプリのインストールを許可」が必要です）
2. アプリを開く → 設定で **サーバーアドレス**（例: `ws://192.168.1.100:8080`）と **Token** を入力（PC 版と同じトークンで自動ペアリング）
3. 案内に従って権限を付与（次項参照）→「同期サービスを開始」をタップ → 通知欄に常駐通知が表示されれば成功

### 権限一覧と用途

| 権限 | 用途 |
|---|---|
| SMS（RECEIVE/READ_SMS） | 認証コードのリッスン |
| 通知アクセス | MIUI などが SMS ブロードキャストを傍受する場合のフォールバック読み取りチャンネル |
| ユーザー補助サービス | バックグラウンドでのクリップボード読み書き（Android 10+ 制限下での適合的な回避策） |
| フォアグラウンドサービス/起動ロック | バックグラウンド維持 |
| 電池最適化の無視 | 中国産 ROM によるバックグラウンド強制終了を防止 |
| メディアライブラリの読み取り | スクリーンショット/画像クリップボードの同期 |

## 3. メッセージプロトコル（サーバーとの取り決め）

```json
{ "type": "notify_pc", "kind": "sms_code", "text": "【某銀行】認証コード 314159" }
```

- `notify_pc`: PC クライアントにのみ送信（認証コード用途）
- `clipboard`: 全端末にブロードキャスト。受信側は各自のスイッチ設定に従ってクリップボードへ書き込むか決定
- 同じトークンを持つ端末同士が自動的にグループ化。サーバーはデータを保存しません

## 4. 開発環境のセットアップ

### 1. Android Studio のインストール

https://developer.android.com/studio から入手し、初回起動時の案内に従って SDK（API 34）をインストールします。

### 2. コマンドライン環境変数

```bash
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator
source ~/.zshrc
```

### 3. 実機デバッグ

設定 → 端末情報 → ビルド番号を 7 回連続タップ → 開発者向けオプション → USB デバッグ → データケーブルで PC に接続し「ファイル転送」を選択。

### 4. 実行

Android Studio でこのディレクトリを Open → Gradle Sync → デバイスを選択 → ▶️（Shift+F10）。
コマンドラインの場合: `./gradlew installDebug`。

## 5. ビルド

### Debug ビルド（自用）

```bash
./gradlew assembleDebug
# 成果物: app/build/outputs/apk/debug/app-debug.apk
```

### Release 署名ビルド（配布用）

署名設定は `local.properties` から読み込まれます（このファイルは gitignore 済み。**鍵はリポジトリにコミットされません**）:

```properties
clipsync.storeFile=../keystore/release.jks
clipsync.storePassword=***
clipsync.keyAlias=clipsync
clipsync.keyPassword=***
```

```bash
./gradlew assembleRelease
# 成果物: app/build/outputs/apk/release/app-release.apk（自動署名）
```

keystore がない場合、`signingConfigs` は作成されず、未署名 APK が生成されます（インストール不可。CI のフォールバック用のみ）。

### CI 自動ビルド（推奨）

タグをプッシュすると、署名ビルドの構築、Release の発行、コンテナイメージの push が自動で実行されます:

```bash
git tag v1.2.0 && git push origin v1.2.0
```

署名鍵は base64 でリポジトリの Secrets（`ANDROID_KEYSTORE_B64` など計4つ）に保存され、workflow が自動注入します。ローカルでの設定は不要です。

## 6. プロジェクト構成

```
app/src/main/java/...
├── MainActivity.kt              # メイン画面: 接続状態、サービス開始/停止
├── clip/ShareActivity.kt        # 共有メニューのエントリー
├── clipboard/ClipReaderActivity.kt  # MIUI クリップボード読み取りフォールバック（透過1フレーム）
├── sms/SmsReceiver.kt           # SMS ブロードキャスト受信
├── sms/NotificationSmsListener.kt   # 通知リスナーによるフォールバック
├── accessibility/ClipSyncAccessibilityService.kt  # バックグラウンドクリップボード
├── service/SyncService.kt       # 常駐フォアグラウンド同期サービス（WS クライアントホスト）
├── service/BootReceiver.kt      # 起動時自動開始
└── ui/                          # 設定/機能設定/権限設定/履歴
```

## 7. よくある質問

| 問題 | 解決方法 |
|---|---|
| 認証コードを受信できない | SMS 権限 + 通知アクセスを付与。電池最適化をオフに |
| クリップボードが同期しない | ユーザー補助サービス「ClipSync クリップボード同期」を有効化 |
| バックグラウンドで落とされる | アプリをロック/ホワイトリスト追加/自動起動を許可 |
| サーバーに接続できない | 同じ WiFi に接続。ファイアウォールで 8080 を許可。アドレスは `ws://IP:8080` |
| 共有メニューに ClipSync が出ない | システムを再起動するか再インストール |
