# TV BLE Bridge / PoC センサパイプライン TODO

作成: 2026-05-30 13:00
コンテキスト切れ時の引継ぎ用。次セッションは **このファイル + project memory** から再開。

---

## 現状サマリ（2026-05-30 13:00）

### ✅ 稼働中（電子工学科 1年・自宅Wi-Fi）

- TV: ORION AI PONT (Android 11, IP 10.11.70.227)
- センサ: SwitchBot WoPresence (MAC `DC:A5:B3:C2:98:D7`)
- アプリ: `com.kimiterrace.tvbridge`（GitHub: cometa-kaito/kimiterrace-tv-ble-bridge）
- 動作: BleService 24h常駐、SignageActivity、スケジュール、ConfigPoller、autolaunch_signage
- データ: `/api/sensor-stats` で確認 → 20+ イベント記録済
- 教室URL: `https://app.school-signage.net/?school=5tdbeclk5fce&grade=dIvlTT7sN57UMmBN5X2x&department=fJJRKRdyLnNLattNzSsX&class=BnsfTb7hjAzA4XIYt73i&kiosk=1`
- スケジュール: 月-金 7:30-22:00（土日終日OFF＝現在Sat=黒画面 ✅）

### 🛒 これからセンサ追加（ユーザー買い物中）

- 電子工学科 2年（センサ + TV 追加）
- 電子工学科 3年（センサ + TV 追加）

---

## 🔥 最優先 TODO（買い物から帰る前に終わらせたい）

### TODO #1: `/api/tv/config` 実装

**ファイル**: `06_LP/edix-lp/app/api/tv/config/route.ts`（新規）

**TV側はすでに ConfigPoller で 60秒ごとにこのエンドポイントを叩いている**（実装済、TV にデプロイ済）。
サーバー側が無いので 404 fail → graceful skip 中。

**期待するレスポンス形式（GET）**:
```json
{
  "version": 1234567890,
  "config": {
    "target_mac": "DC:A5:B3:C2:98:D7",
    "webhook_url": "https://www.school-signage.net/api/switchbot-webhook?key=...",
    "signage_url": "https://app.school-signage.net/?school=...&class=...&kiosk=1",
    "schedule": {
      "enabled": true,
      "on_hour": 7, "on_minute": 30,
      "off_hour": 22, "off_minute": 0,
      "days_mask": 124
    }
  },
  "commands": {
    "signage_reload": false,
    "signage_open": false,
    "signage_exit": false
  }
}
```

**実装要件**:
- GET: 最新設定をデバイスIDで返す（key 認証）
- POST: 設定更新（key 認証、version 自動インクリメント）
- 認証: `SWITCHBOT_WEBHOOK_SECRET` 流用（`?key=...`）
- **マルチデバイス対応**: クエリ `?device_id=xxx` で個別設定を返す
- 単一デバイス互換: device_id 未指定なら最新の一行を返す（PoC 移行期）

### TODO #2: Turso マルチデバイス対応マイグレーション

**ファイル**: `06_LP/edix-lp/migrations/003_multi_device.sql`（新規）

```sql
-- 既存 tv_config テーブル削除（未使用、設計やり直し）
DROP TABLE IF EXISTS tv_config;

-- TV デバイス管理テーブル（教室ごとに 1 行）
CREATE TABLE IF NOT EXISTS tv_devices (
  device_id         TEXT PRIMARY KEY,           -- TV が初回起動時に生成する UUID
  label             TEXT NOT NULL,              -- "電子工学科 1年" 等の表示用
  school_id         TEXT,                       -- signage URL の school クエリ値
  grade_id          TEXT,                       -- signage URL の grade クエリ値
  department_id     TEXT,                       -- signage URL の department クエリ値
  class_id          TEXT,                       -- signage URL の class クエリ値
  target_mac        TEXT,                       -- BLEスキャン対象センサMAC
  signage_url       TEXT,                       -- フルURL
  webhook_url       TEXT,                       -- TV→Vercel POST 先
  schedule_json     TEXT,                       -- ScheduleConfig JSON
  commands_json     TEXT,                       -- 一過性コマンド
  version           INTEGER NOT NULL DEFAULT 0, -- monotonic 増分
  registered_at_ms  INTEGER NOT NULL DEFAULT (CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)),
  last_seen_ms      INTEGER,                    -- TV から最後にポーリングされた時刻
  notes             TEXT
);

CREATE INDEX IF NOT EXISTS idx_tv_devices_class ON tv_devices(school_id, grade_id, class_id);

-- motion_events に教室コンテキスト列を追加
ALTER TABLE motion_events ADD COLUMN device_id     TEXT;
ALTER TABLE motion_events ADD COLUMN school_id     TEXT;
ALTER TABLE motion_events ADD COLUMN grade_id      TEXT;
ALTER TABLE motion_events ADD COLUMN department_id TEXT;
ALTER TABLE motion_events ADD COLUMN class_id      TEXT;

CREATE INDEX IF NOT EXISTS idx_motion_events_class
  ON motion_events (school_id, grade_id, class_id, detected_at_ms);
```

適用方法:
```powershell
cd C:\Users\20051\Desktop\学校DX事業\06_LP\edix-lp
$env:TURSO_DATABASE_URL = "libsql://kimiterrace-sensor-cometa-kaito.aws-ap-northeast-1.turso.io"
$env:TURSO_AUTH_TOKEN = "(controlled tokenを取得)"
node scripts/run-migration.mjs
```

### TODO #3: TV アプリ Phase 4 改修（マルチデバイス対応）

**目的**: 新しい TV にこの APK を入れて signage URL だけ設定すれば、その URL から自動で教室を認識して動く。

**実装場所**: `03_PoC実施/実証実験/03_ハードウェア/tv-ble-bridge/`

#### 3-A. Config.kt 拡張
- `deviceId` getter（SharedPreferences、未設定なら UUID 生成して永続化）
- `schoolId`, `gradeId`, `departmentId`, `classId` getter/setter

#### 3-B. URL パーサ追加
新規ファイル `SignageUrlParser.kt`:
```kotlin
data class ClassroomContext(
    val schoolId: String?,
    val gradeId: String?,
    val departmentId: String?,
    val classId: String?,
)

fun parseSignageUrl(url: String): ClassroomContext { ... }
```

#### 3-C. MainActivity 改修
signage URL を保存した瞬間に `parseSignageUrl` でクエリ抽出 → Config に保存。

#### 3-D. Uploader.kt のペイロード拡張
```json
{
  "eventType": "changeReport",
  ...
  "context": {
    ...,
    "device_id": "<uuid>",
    "school_id": "5tdbeclk5fce",
    "grade_id": "...",
    "department_id": "...",
    "class_id": "..."
  }
}
```

#### 3-E. ConfigPoller の URL に device_id を載せる
```
GET /api/tv/config?device_id=<uuid>&key=...
```

### TODO #4: `/api/switchbot-webhook` 受信側を教室コンテキスト対応

**ファイル**: `06_LP/edix-lp/app/api/switchbot-webhook/route.ts`

`payload.context.device_id`, `school_id`, `grade_id`, `department_id`, `class_id` を取り出して `motion_events` に保存。
- 既存ペイロード（context 拡張なし）も後方互換（NULL で挿入）。

### TODO #5: `/sensors` ダッシュボード

**ファイル**: `06_LP/edix-lp/app/sensors/page.tsx`（新規）

**最小機能 (v1)**:
- 認証: `?key=...` で `SWITCHBOT_WEBHOOK_SECRET` チェック
- **教室別サマリーカード** ×3:
  - 教室名（label）
  - 直近 1h 検知数 / 24h 検知数
  - 最終検知時刻（からの経過時間 → 🟢/🟡/🔴 ステータス）
  - 最終ポーリング時刻（TV ヘルスチェック）
- **全教室の時系列折れ線**（24h、Recharts）
- **最新イベント表** （20件、教室ラベル付き）
- **30秒ごと自動リフレッシュ**

**v2 で追加（後回しOK）**:
- 時間帯別ヒートマップ
- 教室別フィルタ
- 期間指定（7d/30d/custom）
- CSV エクスポート

---

## 🎯 中優先 TODO（買い物から帰った後）

### TODO #6: 新 TV 2 台のセットアップ

**前提**: ユーザーが買って帰ってきた SwitchBot 人感センサ × 2 ＋（既存 or 新規）Google TV × 2

**手順（各 TV 共通）**:
1. SwitchBot アプリで人感センサをペアリング → MAC アドレス控える
2. TV の開発者モード ON、ネットワークデバッグ ON、IP 控える
3. PC で `adb connect <TV_IP>:5555` → USB許可ダイアログ「常に許可」
4. `adb install -r dist/tv-ble-bridge-debug.apk`
5. SharedPreferences に流し込む（教室別 URL のみ差し替え）:
   - 電子工学科 2年: signage_url を 2年クラスの URL に
   - 電子工学科 3年: signage_url を 3年クラスの URL に
   - target_mac を新センサ MAC に
   - webhook_url は共通
   - config_endpoint は共通
6. 権限付与（ACCESS_FINE_LOCATION ×2、COARSE、SYSTEM_ALERT_WINDOW）
7. バッテリ最適化除外
8. MainActivity 起動でサービス開始

**自動化スクリプト案**: `dist/setup-new-tv.bat` を作って 教室ラベル と URL と MAC を引数渡しで全自動実行できるようにする。

### TODO #7: 教室 URL の確認

ユーザーから教えてもらう必要があるもの（v1 管理画面から取得）:
- 電子工学科 **2年** クラスの signage URL（school/grade/department/class クエリ全部入り）
- 電子工学科 **3年** クラスの signage URL
- 新センサ × 2 の MAC（買って SwitchBot アプリでペアリング後に取得）

### TODO #8: v2 への F15 設計ドキュメント追加

**配置先**: `C:\Users\20051\Desktop\キミテラス-v2\docs\requirements\functional\F15-tv-device-management.md`

**書く内容**（既存 F13/F14 のスタイル準拠）:
- 状態: Draft（新規）
- 関連 ADR: ADR-020（SwitchBot Webhook）、新規 ADR-022（TV リモート設定基盤）の起票が必要
- 関連要件: F13（センサ webhook）、F08（ダッシュボード）

**概要**: PoC 期間中 LP リポジトリで実装した「TVリモート設定（/api/tv/config + TVアプリ ConfigPoller）」を v2 で正式実装する要件。

**ユーザーストーリー**:
- system_admin として、各 TV の signage URL を学校に行かずに変更したい
- system_admin として、スケジュール（曜日/時刻）を一括で変更したい
- system_admin として、特定 TV のサイネージを今すぐリロードしたい
- system_admin として、各 TV の最終ポーリング時刻を見て稼働ヘルスを確認したい
- school_admin として、自校の TV のスケジュールだけは変更したい（権限分離）

**受け入れ条件**:
- データモデル: `tv_devices` テーブル（school_id でRLS、auditColumns）、`tv_device_commands` テーブル（コマンドキュー）
- API:
  - `GET /api/tv/config?device_id=...` — TV がポーリング、最新設定を返す
  - `POST /admin/tv/devices/:id/config` — 管理画面から設定変更
  - `POST /admin/tv/devices/:id/command` — リロード等のコマンド送信
- UI（`/admin/tv-devices`）:
  - 一覧（school_admin は school_id スコープ）
  - 詳細・編集（signage URL、MAC、スケジュール）
  - コマンド送信（サイネージリロード、強制サイネージ起動、強制サイネージ終了）
  - 稼働ステータス（🟢/🟡/🔴 直近ポーリング時刻ベース）
- セキュリティ:
  - device_id は推測不能な UUIDv4
  - key 認証は `tv_device_tokens` 別テーブルで TV ごと発行
  - admin UI は audit_log 対象
- 関連: F13（センサ）、F08（ダッシュボード）

**v2 リポジトリ準拠の規律**:
- CLAUDE.md ルール 1-8 全準拠
- Drizzle スキーマ、RLS、監査カラム、PII マスキング
- 1 PR ≤500 行
- branch + PR フロー（main直push禁止）

**追加 ADR が必要なら**:
- ADR-022: TVリモート設定はポーリング方式（push非採用、NAT/ファイアウォール越え不要）

### TODO #9: `setup-new-tv.bat` の作成

`dist/` 配下に教室別セットアップを自動化するバッチを作る。

**引数**:
```
setup-new-tv.bat <TV_IP> "<教室ラベル>" "<signage_url>" "<sensor_mac>"
```

**処理**:
1. adb connect
2. uninstall + install
3. SharedPreferences 流し込み（XML テンプレートからプレースホルダ置換）
4. 権限付与
5. バッテリ最適化除外
6. アプリ起動

---

## 📋 参考情報（実装時に使う秘密情報・URL 等）

### Vercel 環境変数（設定済）
- `TURSO_DATABASE_URL` = `libsql://kimiterrace-sensor-cometa-kaito.aws-ap-northeast-1.turso.io`
- `TURSO_AUTH_TOKEN` = `eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9...VBeh...32_Bw`
- `SWITCHBOT_WEBHOOK_SECRET` = `0khL1mUcvIYYEowrO-Z1CuZQGIyMYHerkb30uFuByl0`

### 既知の TV/センサ（電子工学科 1年）
- TV IP: `10.11.70.227`（家のWi-Fi、Wi-Fi変更で変わる）
- センサ MAC: `DC:A5:B3:C2:98:D7`
- signage URL: 上記「現状サマリ」参照
- アプリパッケージ: `com.kimiterrace.tvbridge`

### キー URL
- LP本番: `https://www.school-signage.net/`
- センサ統計: `https://www.school-signage.net/api/sensor-stats?key=0khL1mUcvIYYEowrO-Z1CuZQGIyMYHerkb30uFuByl0&hours=24`
- Webhook受信: `https://www.school-signage.net/api/switchbot-webhook?key=0khL1mUcvIYYEowrO-Z1CuZQGIyMYHerkb30uFuByl0`
- TV設定（未実装）: `https://www.school-signage.net/api/tv/config?device_id=<uuid>&key=...`

### よく使うコマンド
```powershell
# adb 起動
$adb = "C:\Users\20051\platform-tools\adb.exe"

# Wi-Fi 経由接続（IP は機種ごと）
& $adb connect 10.11.70.227:5555
& $adb devices

# APK 入替（署名違いで uninstall 必要）
& $adb -s 10.11.70.227:5555 uninstall com.kimiterrace.tvbridge
& $adb -s 10.11.70.227:5555 install "C:\Users\20051\Desktop\学校DX事業\03_PoC実施\実証実験\03_ハードウェア\tv-ble-bridge\dist\tv-ble-bridge-debug.apk"

# SharedPreferences 流し込み（XML を /data/local/tmp に push してから run-as でコピー）
# 既存スクリプト群を参照、もしくは TODO #9 の setup-new-tv.bat を使う

# 権限一括付与
& $adb -s 10.11.70.227:5555 shell pm grant com.kimiterrace.tvbridge android.permission.ACCESS_FINE_LOCATION
& $adb -s 10.11.70.227:5555 shell pm grant com.kimiterrace.tvbridge android.permission.ACCESS_COARSE_LOCATION
& $adb -s 10.11.70.227:5555 shell pm grant com.kimiterrace.tvbridge android.permission.SYSTEM_ALERT_WINDOW

# バッテリ最適化除外
& $adb -s 10.11.70.227:5555 shell dumpsys deviceidle whitelist +com.kimiterrace.tvbridge

# CI 完了監視（GitHub）
cd "C:/Users/20051/Desktop/学校DX事業/03_PoC実施/実証実験/03_ハードウェア/tv-ble-bridge"
gh run list --limit 1
gh run download <run_id> --name tv-ble-bridge-debug-apk --dir /tmp/apk-download
```

### マイグレーション流し込み
```powershell
cd C:\Users\20051\Desktop\学校DX事業\06_LP\edix-lp
$env:TURSO_DATABASE_URL = "libsql://kimiterrace-sensor-cometa-kaito.aws-ap-northeast-1.turso.io"
$env:TURSO_AUTH_TOKEN = "<token>"
node scripts/run-migration.mjs
```

### v2 リポジトリ
- パス: `C:\Users\20051\Desktop\キミテラス-v2\`
- GitHub: `https://github.com/cometa-kaito/kimiterrace-v2`
- 規律: `CLAUDE.md` 必読、main直push禁止、branch+PR、CLAUDE.md ルール 1-8

---

## 🧩 完了済みの参考実装（読むべきファイル）

- `06_LP/edix-lp/app/api/switchbot-webhook/route.ts` — 既存 webhook 受信
- `06_LP/edix-lp/app/api/sensor-stats/route.ts` — 既存 GET 統計
- `06_LP/edix-lp/lib/sensor-db.ts` — Turso クライアント
- `06_LP/edix-lp/migrations/001_init.sql` — 既存スキーマ
- `06_LP/edix-lp/migrations/002_tv_config.sql` — 単一行版（TODO #2 で DROP 予定）
- `03_PoC実施/実証実験/03_ハードウェア/tv-ble-bridge/app/src/main/java/com/kimiterrace/tvbridge/*.kt` — TVアプリ全ソース
- `03_PoC実施/実証実験/03_ハードウェア/tv-ble-bridge/SIDELOAD_GUIDE.md` — TVへのインストール手順
- `06_LP/edix-lp/docs/POC_OPERATIONS.md` — PoC 運用ハンドブック

---

## 📅 想定スケジュール

| 日 | 想定 |
|---|---|
| 5/30 (今日) | 電子工学科 1年 TV セットアップ完了 ✅、買い物中（センサ×2）、Phase 3/4 実装着手 |
| 5/31 (明日 日) | TV 2年生・3年生分セットアップ、ダッシュボード完成、v2 F15 ドキュメント |
| 6/01 (月) | PoC 開始日。朝 7:30 に各 TV のサイネージが自動起動するかライブ確認 |

---

## 次セッション開始時のアクション

1. **このファイルを読む**
2. **project memory（`~/.claude/projects/.../memory/`）も読む** — 特に:
   - `project_kimiterrace.md` — 事業全体
   - `project_poc_sensor_pipeline.md` — センサパイプライン構成
3. **どこから再開するか判断**:
   - TODO #1〜#5 が未完なら、まずそこから（Vercel 側実装、マルチデバイス対応）
   - TODO #6 のセットアップは新センサ MAC とクラス URL の入手後
   - TODO #8（v2 F15）は他が落ち着いたら
