# キミテラス TV BLE ブリッジ

Google TV 上で動作する Android アプリ。SwitchBot 人感センサの BLE Advertisement を
受信し、状態遷移時に Vercel `/api/switchbot-webhook` へ POST して Turso に蓄積する。

**Hub 2 不要、SwitchBot クラウド不要、追加機材ゼロ** で人感センサ → Turso パイプラインが
完結する PoC 用構成。

```
人感センサ
    │ BLE Advertisement（10m以内、Wi-Fi 不要）
    ↓
Google TV（既に学校 Wi-Fi 接続済、24時間稼働中、BLE 内蔵）
    │ このアプリ (TV BLE Bridge) が常駐
    ├→ ローカル SQLite に蓄積（オフライン保証）
    └→ Wi-Fi 経由で POST
         ↓
Vercel /api/switchbot-webhook
    ↓
Turso DB
```

## 動作要件

- Android 8.0 (API 26) 以上
- Bluetooth LE
- Wi-Fi 接続
- フォアグラウンドサービス権限（自動付与）
- BLE スキャン権限（初回起動時にユーザー許可）

## ビルド方法

### A) GitHub Actions で自動ビルド（推奨）

このリポジトリにコードを push すると、`.github/workflows/build.yml` が自動で APK を
ビルドし、Actions の Artifacts に置く。Actions タブから `tv-ble-bridge-debug-apk` を
ダウンロード → ZIP 解凍 → `tv-ble-bridge-debug.apk` を Google TV へ sideload。

#### Firebase（FCM 遠隔起動）の設定 — 本番ビルド時に必須

`google-services` プラグインはビルド時に `app/google-services.json` を必須とするが、
同ファイルは実 Firebase 鍵を含むため **`.gitignore` 済み**（リポジトリに焼かない）。
CI は次のように動く:

- **GitHub Secret `GOOGLE_SERVICES_JSON_BASE64` が設定されていれば** → それを復号して
  `app/google-services.json` を生成 → **FCM 遠隔起動が機能する本番 APK** をビルド。
- **未設定なら** → 同梱のダミー（`app/google-services.json.ci-placeholder`）を使って
  **ビルドだけ通す**（この APK では FCM 受信は機能しない＝センサ/スケジュール/設定sync は動く）。

本番 FCM を有効にする手順:

1. Firebase コンソールで Android アプリ（package `com.kimiterrace.tvbridge`）を登録し、
   `google-services.json` をダウンロード。
2. base64 化:
   ```bash
   base64 -w0 google-services.json   # macOS は `base64 -i google-services.json`
   ```
3. GitHub → リポジトリ → Settings → Secrets and variables → Actions →
   **`GOOGLE_SERVICES_JSON_BASE64`** という名前で上記出力を貼り付けて保存。
4. 以降の CI ビルドは実 Firebase 設定で APK を生成する。

### B) ローカル Android Studio でビルド

Android Studio で本フォルダを開く → Build → Build APK(s)。
出力: `app/build/outputs/apk/debug/app-debug.apk`

### C) コマンドラインビルド

JDK 17 と Android SDK が入っている前提：

```bash
gradle wrapper --gradle-version 8.7
./gradlew assembleDebug
```

## インストール（sideload）

Google TV を **開発者モード** にして adb 接続：

1. Google TV：設定 → システム → デバイス情報 → 「Android TV OS ビルド」を **7回タップ**
2. Google TV：設定 → システム → 開発者向けオプション → **USBデバッグ** ON、**ネットワークデバッグ** ON
3. Google TV のIPアドレスを控える（設定 → ネットワーク → 接続中の Wi-Fi）
4. PC（同じネットワーク）で：
   ```powershell
   adb connect <Google_TV_IP>:5555
   adb install tv-ble-bridge-debug.apk
   ```
5. Google TV 側で **接続を許可** ダイアログが出るので **常に許可** にチェック → OK

## 初回セットアップ

1. Google TV のアプリ一覧から **「キミテラス TV ブリッジ」** を起動
2. **センサーMAC**: `DC:A5:B3:C2:98:D7`（自動入力済、変更可）
3. **Webhook URL**:
   ```
   https://www.school-signage.net/api/switchbot-webhook?key=<SECRET>
   ```
4. **▶ 開始 / 再起動** を押す → 権限ダイアログが出るので **全て許可**
5. **ステータス: scanning** になれば受信開始
6. 人感センサの前で手を振ると **最終検知** が更新される
7. これ以降アプリを閉じても、再起動しても、サービスは常駐継続

## アーキテクチャ

| ファイル | 役割 |
|---|---|
| `SwitchBotParser.kt` | BLE Advertisement の Service Data / Manufacturer Data を解析（pyswitchbot 互換） |
| `BleService.kt` | 常駐 Foreground Service。BluetoothLeScanner で対象 MAC をフィルタスキャン |
| `Uploader.kt` | SQLite 永続化 + バックグラウンドで OkHttp POST + 失敗時自動リトライ |
| `Config.kt` | SharedPreferences で MAC / WebhookURL / 最終既知状態を保持 |
| `MainActivity.kt` | TV 用ステータス UI（設定、開始/停止、累計件数表示） |
| `BootReceiver.kt` | 端末起動時に BleService を自動起動 |
| `BleServiceHandle.kt` | UI ↔ Service 間の状態共有（PoC 用の簡易方式） |

## トラブルシュート

### スキャンが始まらない

- 設定 → アプリ → キミテラス TV ブリッジ → 権限 で Bluetooth が許可されているか確認
- Bluetooth 自体がオンになっているか確認

### 検知はされるが Vercel に届かない

- `ステータス` → 同期失敗→蓄積中 の表示なら Wi-Fi or URL の問題
- Webhook URL の `?key=` 値が正しいか確認
- ローカル SQLite には残っているので、復旧後に自動再送

### 端末再起動後に動かない

- 設定 → アプリ → キミテラス TV ブリッジ → 自動起動を許可（端末により名称異なる）
- アプリを一度開いて **▶ 開始** ボタンを押すと再開

## 関連プロジェクト

- LP / Webhook 受信側: `06_LP/edix-lp/`
- v2 アプリ移行: `キミテラス-v2/` （F13 / ADR-020）
- PoC 運用ハンドブック: `06_LP/edix-lp/docs/POC_OPERATIONS.md`
