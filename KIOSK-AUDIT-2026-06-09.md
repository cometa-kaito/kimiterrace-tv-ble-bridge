# Google TV キオスク監査 — tv-ble-bridge（com.kimiterrace.tvbridge）

- 監査日: 2026-06-09（READ-ONLY、ソース未変更）
- 対象: `C:\Users\20051\Desktop\学校DX事業\03_PoC実施\実証実験\03_ハードウェア\tv-ble-bridge\`
- 目的: 先行監査（`キミテラス-v2/docs/monitor-onboarding-and-kiosk-audit-2026-06-09.md`）が「アプリソースが見つからず UNVERIFIABLE」とした 4 要件を、実ソースで PASS/PARTIAL/FAIL 判定し直す。
- ビルド成果物:
  - `dist\tv-ble-bridge-debug.apk` — 6.15 MB / 2026-05-30 15:40（Phase 2/3/4 世代）
  - `dist\v2-build\tv-ble-bridge-debug.apk` — 6.16 MB / 2026-06-08 23:22（**現行ソース＝Device Owner / lockNow / lp-config 対応の最新ビルド**）
  - debug 署名（`app/build.gradle.kts:33` で release も debug 署名）。versionName `0.1.0` / versionCode `1`（`app/build.gradle.kts:14-15`）。OTA 更新の仕組みは無く、更新は adb `install -r` 手動（`SIDELOAD_GUIDE.md:166-174`）。

---

## エグゼクティブサマリ（4 要件の判定）

| # | 要件 | 判定 | 一行根拠 |
|---|---|---|---|
| 1 | スケジュール ON/OFF（曜日＋時刻、MUST） | **PASS（実装）／PARTIAL（真の電源 OFF 不可）** | AlarmManager で曜日マスク＋時刻の正確アラーム予約＋発火時に実行（`ScheduleManager.kt:26-48`, `ScheduleAlarmReceiver.kt:22-25`）。"OFF" は真の電源断ではなくバックライト OFF（Device Owner の `lockNow()`）＋黒画面フォールバック（`PowerController.kt:57-76`）。CEC は実質 no-op（`CecHelper.kt:23-37`）。 |
| 2 | 改ざん耐性（キオスク） | **PARTIAL** | Device Owner 時のみ lock task でホーム/戻りを抑止（`SignageActivity.kt:235-253`）。ただし **HOME カテゴリ未宣言＝ランチャー置換ではない**（`AndroidManifest.xml:97-101` は LAUNCHER/LEANBACK_LAUNCHER のみ）。BootReceiver で自動再起動あり（`BootReceiver.kt:31-47`）。非 Device Owner（SAFE FALLBACK 運用）では lock task が no-op で容易に離脱可能。 |
| 3 | 夜間バックライト/輝度 | **PARTIAL** | 本命は `lockNow()` でバックライト OFF（`PowerController.kt:57-67`）。黒画面 Activity は `screenBrightness=0.0f` かつ `FLAG_KEEP_SCREEN_ON` を**付けない**（`BlackScreenActivity.kt:63-71`）。ただし真の消灯は Device Owner 昇格と機種依存（`provision-googletv.md:228-229`）。非対応機では「明るさ 0 の黒画面（バックライト点灯のグロー残り得る）」のみ。 |
| 4 | 初回設置後のリモート変更 | **PASS（PARTIAL）** | ConfigPoller が 60 秒ごとに lp-config を GET し、target_mac/webhook_url/signage_url/schedule/コマンドを反映（`ConfigPoller.kt:75-199`）。**config_endpoint・key・device_id・APK バージョンは設置時に焼き込み＝リモート変更不可**（`Config.kt:97-100`, `provision-googletv.md:104-117`）。 |

**既定 config エンドポイント**: `https://app.school-signage.net/api/tv/lp-config`（`app/build.gradle.kts:23`）。秘密鍵はソースに焼かず、プロビジョニング時に prefs の `config_endpoint` に `?key=<V2_TV_POLL_SECRET>` を付与（`Config.kt:90-100`, `provision-googletv.md:107,115-116`）。ConfigPoller は `device_id` のみ自動付与し key は付けない（`ConfigPoller.kt:81-89`）。

**APK パス**: `dist\tv-ble-bridge-debug.apk`（5/30 世代）、`dist\v2-build\tv-ble-bridge-debug.apk`（6/08 現行）。

### Top 3 ギャップ
1. **真の電源 OFF はできない（設計上の意図）**: 端末を深いスリープに落とすと復帰不能になるため、夜間は「バックライト OFF（lockNow）＋黒画面」で端末本体は 24h 起こしたまま（`PowerController.kt:13-21`, `provision-googletv.md:8-12`）。HDMI-CEC standby は privileged 権限が必要でほぼ no-op（`CecHelper.kt:9-18,23-37`）。消費電力の完全削減や HDMI-CEC 連動 TV 電源断は不可。
2. **キオスク強度が Device Owner 昇格に全面依存し、機種依存・SAFE FALLBACK で穴**: HOME カテゴリ未宣言でランチャー置換ではない（`AndroidManifest.xml:97-101`）。Device Owner でない運用（`provision-googletv.md:239-275` の SAFE FALLBACK）では lock task が no-op になり、黒オーバーレイのみ。`onKeyDown` は HOME 以外を WebView に流すだけでリモコンキー遮断は限定的（`SignageActivity.kt:304-307`）。緊急解除も adb 前提（`provision-googletv.md:281-283`）。
3. **設置時ハードコード項目はリモート変更不可**: config_endpoint URL・poll key・device_id・APK バージョン（OTA なし）。device_id は UUIDv4 を初回生成し prefs に保存するが、クリーン再インストールで新規発行され lp-config のシード行とマッチしなくなる（`Config.kt:121-133`, `provision-googletv.md:117-119`）。鍵や URL を変えるには再度 on-site / adb が必要。

---

## 要件 1 — スケジュール ON/OFF（曜日＋時刻）

**判定: PASS（スケジュール機構として）／真の電源 OFF は PARTIAL**

### 曜日＋時刻の設定モデル
- `ScheduleConfig`（`Schedule.kt:17-93`）: `enabled / onHour / onMinute / offHour / offMinute / daysMask`。`daysMask` は `Calendar.SUNDAY..SATURDAY`(1..7) のビットマスク（`Schedule.kt:13,25`）。既定は平日のみ・7:30 ON・22:00 OFF・enabled=false（`Schedule.kt:55-68`）。
- OFF 期間判定 `isCurrentlyInOffPeriod`（`Schedule.kt:27-44`）: 対象外曜日は**終日 OFF**（休日も消灯）。ON<OFF（日跨ぎなし）前提で実装。
- UI でも編集可: `MainActivity.kt:98-127,175-204` に曜日チェックボックス×7・ON/OFF TimePicker・有効スイッチがあり、保存後に `ScheduleManager.rescheduleAll` を呼ぶ。

### アラーム駆動（intent だけでなく実行まで）
- `ScheduleManager.rescheduleAll`（`ScheduleManager.kt:26-48`）が ON/OFF それぞれの「次回発火時刻」を曜日マスクを尊重して算出（`nextOccurrence`、`ScheduleManager.kt:92-112`）し、`setExactAndAllowWhileIdle(RTC_WAKEUP)` で予約（`ScheduleManager.kt:114-137`）。正確アラーム権限が無ければ inexact にフォールバック（`:126-128,133-135`）。Manifest に `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`（`AndroidManifest.xml:57-63`）。
- 発火受信 `ScheduleAlarmReceiver.onReceive`（`ScheduleAlarmReceiver.kt:18-31`）: `ACTION_ALARM_OFF→PowerController.screenOff`、`ACTION_ALARM_ON→PowerController.screenOn` を**実際に実行**し、次回を再予約（AlarmManager は 1 回限りのため）＋`applyCurrentState` で画面状態を反映。
- 起動時・ブート時にも再予約: `BootReceiver.kt:18-22,46`、`MainActivity.kt:171`。

### サーバ config 由来（ConfigPoller → schedule）
- `ConfigPoller.applyConfigFields`（`ConfigPoller.kt:153-170`）が lp-config の `config.schedule.{enabled,on_hour,on_minute,off_hour,off_minute,days_mask}` を読み、差分があれば `ScheduleConfig.save`＋`rescheduleAll`＋`applyCurrentState`。`provision-googletv.md:210-218` に「v2 lp-config が schedule を返し 60 秒間隔で自動適用」「days_mask: 平日=124 / 毎日=254 / 月〜土=252」と明記。

### "OFF" の実装と限界（重要）
- 第一手段: Device Owner / アクティブ管理者なら `DevicePolicyManager.lockNow()`（`PowerController.screenOff`、`PowerController.kt:57-67`）。これで画面ロック＝バックライト OFF だが CPU/サービス/ポーリングは生存（`PowerController.kt:13-19`, `provision-googletv.md:9`）。
- 第二（補助）: HDMI-CEC standby を試すが、`CecHelper.tryStandby` は `hdmi_control` サービスの存在をログするだけで制御コマンドは発行しない＝**実質 no-op**（`CecHelper.kt:23-37`、コメント `:9-18` で「ほとんど SecurityException で落ちる」）。
- 第三（見た目フォールバック）: `BlackScreenActivity`（明るさ 0 の全画面黒、`BlackScreenActivity.kt:42-71`）。
- ON 側: `PowerController.screenOn`（`PowerController.kt:86-117`）が `SCREEN_BRIGHT_WAKE_LOCK | ACQUIRE_CAUSES_WAKEUP | ON_AFTER_RELEASE` を 3 秒取得→`SignageActivity` を `turnScreenOn` 付きで前面化→CEC wake 補助。SignageActivity 側も `FLAG_TURN_SCREEN_ON`/`setTurnScreenOn(true)`（`SignageActivity.kt:83-96`）。

**結論**: 「曜日＋時刻で ON/OFF」「アラーム駆動」「サーバ config 駆動」「intent でなく実行」はすべて成立 → 機構は PASS。ただし "OFF" は**真の電源断ではなくバックライト OFF（lockNow）＋黒画面**で、真の消灯成立は Device Owner 昇格と機種依存（`provision-googletv.md:227-229` で「ORION AI PONT で lockNow が消灯になるかは要実測」）→ この点 PARTIAL。

---

## 要件 2 — 改ざん耐性（キオスク性）

**判定: PARTIAL**

### あるもの
- **lock task（画面ピン留め）**: Device Owner のとき `SignageActivity.maybeStartLockTask`（`SignageActivity.kt:235-253`）が `onResume` ごとに `startLockTask()`。許可リスト登録は `PowerController.allowLockTaskSelf`（`PowerController.kt:123-134`、Device Owner 限定、BleService 起動時にも実行 `BleService.kt:97`）。非 Device Owner では明示的に no-op（開発機をブリックさせない設計、`SignageActivity.kt:237`）。
- **戻るキー無効化**: `SignageActivity.onBackPressed`（`SignageActivity.kt:296-302`）は WebView 内ナビのみで super 不呼出。`BlackScreenActivity.onBackPressed` も無効化（`BlackScreenActivity.kt:82-85`）。
- **Immersive（システムバー非表示）**: `SignageActivity.enterImmersiveMode`（`SignageActivity.kt:202-225`）。
- **自動起動 / 自己復活**: `RECEIVE_BOOT_COMPLETED`＋`BootReceiver`（`AndroidManifest.xml:33-34,145-155`, `BootReceiver.kt`）。BLE サービスは `START_STICKY`（`BleService.kt:118-120`）でフォアグラウンドサービス（`BleService.kt:75`, `AndroidManifest.xml:104-109`）。WebView ウォッチドッグで 1 時間ごと自動リロード（`SignageActivity.kt:46-53,311`）。
- **Device Admin / Device Owner**: `TvDeviceAdminReceiver`（`AndroidManifest.xml:162-172`, `TvDeviceAdminReceiver.kt`）＋ `device_admin.xml`（force-lock / watch-login、`res/xml/device_admin.xml`）。昇格は `dpm set-device-owner`（`provision-googletv.md:72-73`）。

### 弱いもの／欠落
- **ランチャー置換ではない**: `AndroidManifest.xml:97-101` の MainActivity intent-filter は `LAUNCHER`＋`LEANBACK_LAUNCHER` のみで **`android.intent.category.HOME` 未宣言**（リポジトリ全体で HOME カテゴリの宣言なし＝grep 0 件）。よってホームアプリ（ランチャー）として OS のホーム動作を奪うことはしない。キオスク固定は lock task のみに依存。
- **Device Owner 必須＝機種依存・運用依存**: アカウント未追加の factory reset 直後でしか昇格できず（`provision-googletv.md:32-50,84-88`）、`user setup is already complete` 等で昇格不可な機種では SAFE FALLBACK 運用（`provision-googletv.md:239-275`）。その場合 **lock task は no-op、夜間も黒オーバーレイのみ、リモコンで容易に離脱可能**。
- **リモコンキー遮断は限定的**: `onKeyDown`（`SignageActivity.kt:304-307`）は HOME 以外を WebView に流すだけ。HOME ボタンは OS 制約上アプリ側で完全遮断不可（コメント `SignageActivity.kt:32`）。lock task 非適用なら HOME/アプリスイッチャーで離脱できる。
- **設定アプリ無効化・ランチャー無効化は未実装**（コード・ドキュメントに該当処理なし）。
- **緊急解除が adb 前提**: `am force-stop` / `dpm remove-active-admin`（`provision-googletv.md:281-283`）。Device Owner は factory reset でしか外れない機種もあり、と注記。

**結論**: Device Owner で固めた本番構成なら lock task＋自動再起動で実用的なキオスクになるが、(a) ランチャー置換ではない、(b) Device Owner 昇格が機種依存で SAFE FALLBACK では穴だらけ、(c) リモコン全キー遮断はしていない → PARTIAL。

---

## 要件 3 — 夜間バックライト/輝度（実際に暗いか）

**判定: PARTIAL**

- **本命（真の消灯狙い）**: `PowerController.screenOff` の `lockNow()`（`PowerController.kt:57-67`）＝画面ロックでバックライト OFF。これが効けばパネルは実際に暗くなる。前提は Device Owner / アクティブ管理者（`PowerController.canLock`、`PowerController.kt:46-50`）。
- **黒画面 Activity はグロー対策済**: `BlackScreenActivity` は `lp.screenBrightness = 0.0f`（`BlackScreenActivity.kt:63-65`）で輝度 0、かつ **`FLAG_KEEP_SCREEN_ON` を意図的に付けない**（`BlackScreenActivity.kt:67-71`、付けると lockNow と綱引きしてバックライトが消えない、とのコメント `:17-26`）。
- **KeepAwakeManager の夜間挙動**: OFF 期間中はサイネージを再起動しない（夜間に点け直さない）、もしサイネージが前面に居たら `screenOff` を再発行（`KeepAwakeManager.kt:98-113`）。日中の no-sleep 設定（screen_off_timeout=MAX, sleep_timeout=-1, screensaver 無効）は別管理（`KeepAwakeManager.kt:51-76`）。
- **限界（グローリスク）**: lockNow が「画面ロック＝バックライト OFF」になるかは機種依存（`provision-googletv.md:227-229`、「消えない機種なら黒オーバーレイ（明るさ 0）だけになる。その場合の見え方も確認」）。非対応機・非 Device Owner では `screenBrightness=0` の黒画面のみとなり、パネルのバックライトが残ると黒の発光（グロー）が残り得る。CEC standby は no-op（`CecHelper.kt:23-37`）。TV 本体のオフタイマーは別途 UI で無効化が必要（`provision-googletv.md:189-191`）。

**結論**: 設計はグローを抑える方向（輝度 0＋KEEP_SCREEN_ON なし＋lockNow）で正しいが、真に暗くなるかは Device Owner 昇格＋機種の lockNow 挙動に依存し、フォールバック時はグロー残存の可能性 → PARTIAL。

---

## 要件 4 — 初回設置後のリモート変更

**判定: PASS（ただし焼き込み項目があり PARTIAL）**

### リモートで変更できるもの（ConfigPoller 経由、60 秒ポーリング）
- ポーリング: `ConfigPoller`（`BleService.kt:108-111` で常駐起動、`DEFAULT_POLL_INTERVAL_MS=60_000` `ConfigPoller.kt:203`）。`pollOnce` が `config_endpoint` に GET し、`device_id` をクエリに自動付与（`ConfigPoller.kt:75-105`）。
- 反映フィールド（差分時のみ）: `target_mac` / `webhook_url` / `signage_url`（実行中 SignageActivity へ URL 差し替えブロードキャスト）/ `schedule.*`（`ConfigPoller.kt:128-171`）。
- 一過性コマンド（version が新しい時のみ 1 回実行）: `signage_reload` / `signage_open` / `signage_exit` / `wake`（`ConfigPoller.kt:173-199`）。`wake` は `KeepAwakeManager.forceWake`（no-sleep 再適用＋サイネージ前面化、`KeepAwakeManager.kt:115-119`）。`service_restart` は未実装（`ConfigPoller.kt:198`）。
- → signage URL・スケジュール（曜日/時刻）・対象センサ MAC・webhook 先・リロード/再表示/復帰は**設置後も学校に行かずサーバから変更可能**。`provision-googletv.md:6-9,210-218` の運用前提と一致。

### 焼き込み（設置時固定、リモート変更不可）
- **config_endpoint URL**: 既定 `BuildConfig.DEFAULT_CONFIG_ENDPOINT = https://app.school-signage.net/api/tv/lp-config`（`app/build.gradle.kts:23`）。prefs `config_endpoint` で上書きするが、その上書き自体は adb / 初期プロビジョニングでのみ（`Config.kt:97-106`, `provision-googletv.md:104-117,239-275`）。lp-config レスポンスにエンドポイント自身を変える項目は無い（`ConfigPoller.kt:128-171`）。
- **poll key（`?key=<V2_TV_POLL_SECRET>`）**: ソースに焼かず prefs の config_endpoint URL に含めて設置時に投入（`Config.kt:90-96`, `provision-googletv.md:13-17,107,115-116`）。リモートでは変更不可。
- **device_id**: 初回読み出し時に UUIDv4 を生成し prefs に永続化（`Config.kt:121-133`）。APK 再インストールでは維持されるが、**アンインストール→クリーン再インストールで新規発行**され、v2 のシード行とマッチせず lp-config が空応答になる（`Config.kt:124-126`, `provision-googletv.md:117-119`）。本番では「v2 でシード済みの教室 device_id」を設置時に prefs へ直書きする（`provision-googletv.md:108-117,122-128`）。
- **APK バージョン / OTA**: versionCode=1 / versionName=0.1.0（`app/build.gradle.kts:14-15`）。アプリ内 OTA / 自動更新は無く、更新は adb `install -r`（`SIDELOAD_GUIDE.md:166-174`, `dist/README.md:35-39`）。リモートでアプリ本体は更新不可。
- **DEFAULT_TARGET_MAC / DEFAULT_WEBHOOK_URL** も BuildConfig 既定（`app/build.gradle.kts:19-20`）だが、これらは lp-config で上書き可能（焼き込みは「初期値」のみ）。

**結論**: 運用パラメータ（URL/スケジュール/MAC/コマンド）はリモート変更可能で要件を満たすが、config_endpoint・key・device_id・APK は設置時に焼き込み／on-site でしか変えられない → PASS（PARTIAL）。

---

## 付随確認事項

- **HDMI-CEC 能力**: `CecHelper`（`CecHelper.kt`）は `getSystemService("hdmi_control")` の有無をログするのみで、standby/wake の制御コマンドは発行しない＝実質 no-op。制御 API は signature|privileged 権限が必要で sideload では拒否される旨をコメントで明言（`CecHelper.kt:9-18`）。**CEC による TV 本体の電源 ON/OFF は事実上できない。**
- **自動再起動 / ウォッチドッグ / クラッシュ復旧**:
  - BootReceiver で端末再起動後に自動再開（スケジュール再予約＋no-sleep 再適用＋BleService 起動、`BootReceiver.kt:14-47`）。ただし clean install 後は一度 MainActivity を起動しないと `BOOT_COMPLETED` が配信されない Android 仕様の注意あり（`provision-googletv.md:202-204`, `SIDELOAD_GUIDE.md:159-163`）。
  - フォアグラウンドサービス＋`START_STICKY`＋`PARTIAL_WAKE_LOCK` でプロセス維持（`BleService.kt:75,88-91,118-120`）。Doze 除外は adb で whitelist（`provision-googletv.md:166-167`）。
  - keep-awake ループが 60 秒ごとに前面チェック、約 15 分ごとに no-sleep 設定再適用（`BleService.kt:127-142,280-282`）。
  - SignageActivity ウォッチドッグが 1 時間ごとに WebView 自動リロード（`SignageActivity.kt:46-53,311`）。
  - **真のクラッシュ自動再起動（プロセスが死んだ後にアプリ全体を再起動する Device Owner の自動再起動ポリシー等）は未実装**。サービスは START_STICKY で OS が再生成する範囲に依存。
- **既定 webhook**: `https://www.school-signage.net/api/switchbot-webhook`（`app/build.gradle.kts:20`、key は prefs 投入）。
- **セキュリティ補足（ソース外の運用ドキュメント）**: `TODO.md:302-315` および `dist/README.md:29-31` に Turso トークン・`SWITCHBOT_WEBHOOK_SECRET`・実 webhook URL が平文で記載されている（アプリの判定外だが運用上の漏洩リスクとして付記）。

---

## 先行監査（UNVERIFIABLE）の解消

先行監査（`キミテラス-v2/docs/monitor-onboarding-and-kiosk-audit-2026-06-09.md`）は「アプリソースが見つからない」として要件 1〜4 を UNVERIFIABLE としていたが、本監査でソース（`app/src/main/java/com/kimiterrace/tvbridge/*.kt`）と現行 APK（`dist/v2-build/tv-ble-bridge-debug.apk`、2026-06-08）を確認し、上記のとおり証跡付きで判定した（要件1: PASS/PARTIAL、要件2: PARTIAL、要件3: PARTIAL、要件4: PASS/PARTIAL）。
