# Google TV を「Device Owner キオスク」としてプロビジョニングする手順（v2 バックエンド）

工場出荷状態（factory reset 済）の Google TV を、キミテラス TV ブリッジの
**端末所有者（Device Owner）キオスク**として一気通貫で構築する手順。

このアプリは v2（GCP）バックエンドにポーリングし、以下を「PC 不要で自走」する:

- **昼間**: サイネージ（WebView キオスク）を前面に固定（lock task）＋端末を 24h 起こしたまま
- **夜間**: `DevicePolicyManager.lockNow()` で **画面（バックライト）だけ OFF**。端末本体は起きたままなので
  ポーリングは止まらず、リモート `wake` も届く（＝**復帰不能にならない**設計）
- **朝**: ウェイクロック＋`turnScreenOn` でバックライトを点け、サイネージを再前面化

> 🔑 **秘密鍵をこの文書・コミットに実値で書かない。** プレースホルダ表記:
> - `<V2_TV_POLL_SECRET>` … v2 TV ポーリング鍵（lp-config の `?key=`）。値は GCP Secret Manager
>   `prod-tv-poll-secret` / staging 相当、または `docs/STATUS.md` / 運用ドキュメントを参照。
> - `<SWITCHBOT_WEBHOOK_SECRET>` … センサ webhook 鍵（従来どおり www 側。当面 v1 のまま）。
> - `<TV_IP>` … TV の LAN IP。`<SIGNAGE_URL>` … その教室のサイネージ URL。

---

## 0. 前提

- PC に adb（例 `C:\Users\<user>\platform-tools\adb.exe`）
- PC と TV が **同一ネットワーク**（校内 LAN）
- APK: `dist/tv-ble-bridge-debug.apk`（**debug 署名＝`run-as` が使える**。prefs 直書きに必須）
  - CI（GitHub Actions）の `tv-ble-bridge-debug-apk` artifact をこのパスに展開しておく
- 機種メモ: 実証は **ORION AI PONT / Android 11（API 30）**。Device Owner / lockNow / lock task の
  実挙動は機種依存。本番投入前に §9 の **on-device 検証チェックリスト**を必ず実施すること。

---

## 1. Factory reset とセットアップ（アカウントを追加しないことが Device Owner の必須条件）

`dpm set-device-owner` は **端末にアカウントが 1 つも無い**ときしか成功しない（`already has accounts` で失敗する）。

1. 設定 → システム → リセット（または初回セットアップ）で **factory reset**。
2. 初期セットアップウィザードで:
   - Wi-Fi には接続する（ADB と URL ポーリングに必要）
   - **Google アカウントの追加を SKIP する**（「後で」「スキップ」を選ぶ）。
     - Google TV はアカウント追加をスキップしづらい UI のことがある。既知の回避策:
       - セットアップ中に **ネットワークを一旦切る**とアカウント手順を飛ばせる機種がある（その後再接続）。
       - それでも残る場合は、セットアップ完了後に **設定 → アカウントとログイン から全アカウントを削除**してから §4 を実行する。
   - 「Google TV」ではなく「ベーシック TV / Apps only」モードを選べる機種ではそれを選ぶとアカウント無しにしやすい。
3. **開発者オプションを有効化**: 設定 → システム → デバイス情報 → 「Android TV OS ビルド」を **7 回タップ**。
4. 開発者向けオプション → **USB デバッグ ON** ＋ **ネットワークデバッグ（ADB over network / port 5555）ON**。
5. TV の IP を控える（設定 → ネットワーク → 接続中の Wi-Fi）。

> ⚠️ アカウントを 1 つでも追加してしまったら、`set-device-owner` は通らない。削除しても
> 「かつてアカウントがあった」状態が残ってブロックされる機種があるため、**確実なのは再 factory reset**。

---

## 2. 接続・APK 導入

```powershell
$adb = "C:\Users\<user>\platform-tools\adb.exe"
& $adb connect <TV_IP>:5555
& $adb devices -l                                   # `device` 表示を確認（unauthorized ならTV側で許可）
& $adb shell getprop ro.build.version.release        # Android 版（11 なら API30）
& $adb install -r "<repo>\dist\tv-ble-bridge-debug.apk"   # Success
```

> ⚠️ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（署名不一致）: 旧ローカルビルドが残っている。
> `adb uninstall com.kimiterrace.tvbridge` → クリーン install。以降は **全機 CI ビルド APK で統一**。

---

## 3. Device Owner に昇格（このプロビジョニング方式の中核）

アカウント無し・APK 導入済の状態で 1 回だけ:

```powershell
& $adb shell dpm set-device-owner com.kimiterrace.tvbridge/.TvDeviceAdminReceiver
# 期待: "Success: Device owner set to package com.kimiterrace.tvbridge ..."
```

確認:

```powershell
& $adb shell dpm list-owners
# 期待: device owner に com.kimiterrace.tvbridge が出る
```

> ⚠️ 失敗パターン:
> - `Not allowed to set the device owner because there are already some accounts` → §1 に戻りアカウント削除 or 再 factory reset。
> - `Not allowed ... user setup is already complete` → 機種により setup 完了後は不可。factory reset 後、
>   setup 完了前（または provisioned フラグを落とした状態）で実行する必要がある。回避が難しい場合は
>   §10 の「Device Owner 無しフォールバック」で運用する（夜間消灯は黒オーバーレイのみになる）。

Device Owner になると、アプリは `lockNow()`（夜間消灯）と `setLockTaskPackages`/`startLockTask`（キオスク）を
**追加権限なし・PC 不要**で行使できる。

---

## 4. 設定（SharedPreferences）を直書き

URL に `?` `&` を含むため `input text` は使わない。XML を作って `run-as` で書き込む
（ADB_SETUP_RUNBOOK.md §3 と同方式）。

**(a) ローカルに XML 作成**（`&`→`&amp;`、日本語ラベルはそのまま、UTF-8 保存。シークレット入りなので Temp 等の非追跡領域へ）

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="target_mac"><SENSOR_MAC></string>
    <string name="webhook_url">https://www.school-signage.net/api/switchbot-webhook?key=<SWITCHBOT_WEBHOOK_SECRET></string>
    <string name="config_endpoint">https://app.school-signage.net/api/tv/lp-config?key=<V2_TV_POLL_SECRET></string>
    <string name="signage_url"><SIGNAGE_URL の & を &amp; にエスケープ></string>
    <string name="device_id"><この教室の v2 シード済 device_id></string>
    <string name="device_label"><LABEL_LOCAL（日本語OK）></string>
    <boolean name="autolaunch_signage" value="true" />
</map>
```

- `config_endpoint` は **v2 の lp-config**（`app.school-signage.net`）。`?key=<V2_TV_POLL_SECRET>` を含める
  （ConfigPoller は `device_id` だけ自動付与し、key は付けない）。
- `device_id` は **v2 でシード済の教室 device_id** を書く（下表）。これが lp-config のマッチキー。
  - 書かなければアプリが UUID を新規発行してしまい、v2 のシード行とマッチせず lp-config が空応答になる。
- `signage_url` は **起動直後のフォールバック表示**。device_id がマッチすれば lp-config が返す
  `config.signage_url` で上書きされる。

**シード済 device_id（岐南工業・電子工学科。例／プレースホルダとして）:**

| 教室 | device_id |
|---|---|
| 電子工学科 1年 | `1bf201a2-bd76-4ed9-a900-3b989d49a871` |
| 電子工学科 2年 | `6c14d31f-b6fb-4dba-ac35-937c043bc9f4` |
| 電子工学科 3年 | `8d30fcc4-8f0e-4da0-9a3a-98a241d5363d` |

各 device_id は v2 側でそれぞれの `signage_url` に紐付いており、TV は lp-config をポーリングして
その URL を取得する（prefs の signage_url はあくまでブートフォールバック）。

**(b) base64 経由で書き込み**（日本語/特殊文字の経路事故を防ぐ）

```powershell
# 既存機を再設定する場合は先に force-stop（稼働中はメモリ上 prefs がファイルを上書きするため）
& $adb shell am force-stop com.kimiterrace.tvbridge

$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\...\Temp\prefs.xml"))
$inner = "mkdir -p shared_prefs; echo $b64 | base64 -d > shared_prefs/tv_ble_bridge.xml; echo WROTE_OK"
& $adb shell "run-as com.kimiterrace.tvbridge sh -c '$inner'"
& $adb shell "run-as com.kimiterrace.tvbridge cat shared_prefs/tv_ble_bridge.xml"   # 読み戻して検証
```

- prefs 実体: `/data/data/com.kimiterrace.tvbridge/shared_prefs/tv_ble_bridge.xml`

---

## 5. 権限付与（BLE / 位置情報 / 画面制御 / バックグラウンド起動 / 電池最適化除外）

```powershell
# BLE スキャン（Android 11 は位置情報権限＋端末トグル ON が必須）
& $adb shell pm grant com.kimiterrace.tvbridge android.permission.ACCESS_FINE_LOCATION
& $adb shell pm grant com.kimiterrace.tvbridge android.permission.ACCESS_COARSE_LOCATION
& $adb shell cmd location set-location-enabled true
& $adb shell settings put secure location_mode 3

# no-sleep 設定をアプリ自身が再適用するための権限（KeepAwakeManager）
& $adb shell pm grant com.kimiterrace.tvbridge android.permission.WRITE_SECURE_SETTINGS
& $adb shell appops set com.kimiterrace.tvbridge WRITE_SETTINGS allow

# バックグラウンドからの画面起動（黒画面/サイネージの startActivity）に必須
& $adb shell appops set com.kimiterrace.tvbridge SYSTEM_ALERT_WINDOW allow
& $adb shell "appops get com.kimiterrace.tvbridge SYSTEM_ALERT_WINDOW"   # → allow

# 電池最適化からの除外（常駐サービスが Doze で殺されにくくする）
& $adb shell dumpsys deviceidle whitelist +com.kimiterrace.tvbridge
```

> ⚠️ **位置情報権限/トグルは BLE スキャンの前提（Android 11）**。Device Owner でも **runtime 権限は自動付与されない**ので
> 上の `pm grant … ACCESS_FINE_LOCATION` は必須。**権限未付与だと BLE スキャンが結果0件・例外も出さず無言で失敗**する
> （人感センサが「全く検知されない」原因不明の症状になる。2026-06-20 岐阜工業の no-DO 機で実踏）。
> トグル OFF 側は logcat に `Permission denial: Location is off`、検知ゼロ。**TV 再起動で OFF に戻る**ことがあるので再起動後に再確認。
> ⚠️ **SYSTEM_ALERT_WINDOW**: 未許可だと黒画面/サイネージのバックグラウンド起動が BAL 制限で弾かれる。

---

## 6. no-sleep 即時適用（再起動で戻る分はアプリが再適用するが、初回は手動でも入れておく）

```powershell
& $adb shell settings put global hdmi_control_auto_device_off_enabled 0
& $adb shell settings put global no_signal_auto_power_off 0
& $adb shell settings put system screen_off_timeout 2147483647
& $adb shell settings put secure sleep_timeout -1
& $adb shell settings put secure screensaver_enabled 0
& $adb shell settings put secure screensaver_activate_on_sleep 0
& $adb shell settings put secure screensaver_activate_on_dock 0
& $adb shell settings put global stay_on_while_plugged_in 7
```

> ⚠️ **TV 本体のオフタイマー/スリープタイマー**は firmware 側で持つことがあり（ORION AI PONT で 16:30 の
> 電源 OFF タイマーが効いていた）、`settings put` では消えない。**TV 設定 UI（タイマー/電源/省エネ）で
> オフタイマー・スリープタイマーを必ず無効化**すること。これを忘れると夜間に箱ごと落ちて検知が止まる。

### 6.1 no-sleep が実際に効いたか読み戻して確認（メーカー差の切り分け）

`settings put` は機種によって**黙って無視される**（vendor がキーを持たない／権限が効かない）。put した値を読み戻し、
効いていない機は夜間 OFF を擬似黒モード（`night_off_mode=overlay`、既定）で運用する。

```powershell
& $adb shell settings get system screen_off_timeout   # 期待: 2147483647
& $adb shell settings get secure sleep_timeout         # 期待: -1
& $adb shell settings get secure screensaver_enabled   # 期待: 0
```

- **期待値どおり** → `night_off_mode=lock`（真の消灯）でも復帰し得る。実機で「夜間→朝」の復帰を一度検証してから lock に。
- **値が違う／反映されない** → そのメーカーは深いスリープに落ち得る。**`night_off_mode=overlay`（既定のまま）**で運用する。

### 6.2 夜間 OFF の方式（`night_off_mode`）

- 既定 **`overlay`**（擬似黒・復帰優先）。黒オーバーレイ＋`FLAG_KEEP_SCREEN_ON` でパネルを起こしたまま擬似黒にし、
  朝 ON は「オーバーレイ解除」だけで**必ず復帰**する。不明メーカーでも朝戻る。代償はパネル常時点灯の微小電力と弱い発光のみ。
- 真の消灯（lockNow でバックライト OFF）にしたい & 復帰確認済みの機だけ **`lock`** にする。切替は次のいずれか:
  - MainActivity の「夜間OFFを擬似黒にする」チェックボックス（ON=overlay / OFF=lock）
  - lp-config レスポンスの `config.night_off_mode`（`"overlay"` / `"lock"`、per-device で配信）

---

## 7. 起動

```powershell
& $adb shell am start -n com.kimiterrace.tvbridge/.MainActivity   # 権限ありで BleService 自動開始
& $adb shell am start -a com.kimiterrace.tvbridge.OPEN_SIGNAGE    # サイネージ（キオスク）前面表示
```

> ⚠️ **clean install 直後は必ず一度 MainActivity を起動**すること。未起動のまま再起動すると
> stopped-state のまま `BOOT_COMPLETED` が配信されず、BootReceiver が発火しない（Android 仕様）。

Device Owner なら SignageActivity の `onResume` で `startLockTask()` が走り、ホーム/戻るで抜けられない
キオスクになる（開発機＝非 Device Owner では lock task は no-op なのでブリックしない）。

---

## 8. スケジュール（画面 ON/OFF）

スケジュールは v2 の lp-config が返す `config.schedule`（enabled/on_hour/on_minute/off_hour/off_minute/days_mask）で
自動適用される。TV は 60 秒間隔でポーリングし反映する。

- OFF 時刻 → `PowerController.screenOff()`（**Device Owner の lockNow でバックライト OFF**）＋黒オーバーレイ（フォールバック）
- ON 時刻 → `PowerController.screenOn()`（ウェイクロック＋turnScreenOn でバックライト点灯）＋サイネージ再前面化
- `days_mask`: bit1=日, 2=月, …, 7=土。平日(月〜金)=124 / 毎日=254 / 月〜土=252
- 非稼働曜日はアラームを張らない＝直前の OFF が継続（金 OFF → 週末ずっと消灯 → 月 ON）

---

## 9. on-device 検証チェックリスト（実機テスト担当者向け・本番投入前に必須）

機種依存が大きい項目を実機で確認する:

1. **Device Owner 昇格**: §3 が `Success`。`dpm list-owners` に出る。
2. **夜間消灯（lockNow）**: スケジュール OFF 時刻に **バックライトが実際に消える**か。
   - ORION AI PONT で lockNow が「画面ロック＝バックライト OFF」になるかは要実測。
   - 消えない機種なら黒オーバーレイ（明るさ 0）だけになる。その場合の見え方も確認。
3. **消灯中もポーリング継続**: OFF 中に `curl …/api/sensor-stats` でセンサ到達、`wake` で復帰するか。
4. **朝の点灯（screenOn）**: ON 時刻に **バックライトが点き**、サイネージが前面に出るか
   （ランチャーが出ないか）。
5. **キオスク（lock task）**: ホーム/戻るで抜けられないか。**緊急解除手段**（adb から
   `am force-stop` → `dpm remove-active-admin` / 再 factory reset）を運用側が把握しているか。
6. **再起動耐性**: `adb reboot` 後、自動でサービス再開・スケジュール反映・キオスク復帰するか。

---

## 10. SAFE FALLBACK（リビルド不要・既設置 TV の v2 移行）

**既にプロビジョニング済みの TV** を v2 に向けるだけなら、**再インストール・factory reset 不要**。
`config_endpoint` の prefs を v2 lp-config に上書きするだけでよい（device_id は既存値を維持）。

```powershell
$adb = "C:\Users\<user>\platform-tools\adb.exe"
& $adb connect <TV_IP>:5555

# 1) 稼働中プロセスを止める（メモリ上 prefs がファイルを上書きするのを防ぐ）
& $adb shell am force-stop com.kimiterrace.tvbridge

# 2) config_endpoint だけ v2 に書き換える（XML 全体を作り直すより、現行 prefs を読み出して
#    config_endpoint 行だけ差し替える方が安全。下は sed で endpoint 行を置換する例）
$pull = & $adb shell "run-as com.kimiterrace.tvbridge cat shared_prefs/tv_ble_bridge.xml"
#   → 取得した XML の <string name="config_endpoint">…</string> を
#     https://app.school-signage.net/api/tv/lp-config?key=<V2_TV_POLL_SECRET> に書き換えて prefs.xml として保存

# 3) 書き戻し（§4(b) と同じ base64 経路）
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\...\Temp\prefs.xml"))
$inner = "echo $b64 | base64 -d > shared_prefs/tv_ble_bridge.xml; echo WROTE_OK"
& $adb shell "run-as com.kimiterrace.tvbridge sh -c '$inner'"
& $adb shell "run-as com.kimiterrace.tvbridge cat shared_prefs/tv_ble_bridge.xml" | Select-String config_endpoint

# 4) 再起動
& $adb shell am start -n com.kimiterrace.tvbridge/.MainActivity
```

ワンライナーで `sed` 置換したい場合（XML 内の endpoint 行を直接書き換え。`&` を含まない URL なので比較的安全）:

```powershell
& $adb shell "run-as com.kimiterrace.tvbridge sh -c 'sed -i \"s#<string name=\\\"config_endpoint\\\">[^<]*</string>#<string name=\\\"config_endpoint\\\">https://app.school-signage.net/api/tv/lp-config?key=<V2_TV_POLL_SECRET></string>#\" shared_prefs/tv_ble_bridge.xml'"
```

> ⚠️ `sed` ワンライナーはクォート地獄になりやすい。**確実なのは §4(b) の XML 全文書き戻し**。
> ⚠️ この SAFE FALLBACK では Device Owner にはならない（夜間消灯は黒オーバーレイのみ、lockNow なし）。
> lockNow による真の消灯・キオスクが必要なら §1〜§3 のクリーンプロビジョニングが必要。

---

## 緊急解除 / トラブルシュート

- **キオスクから抜けたい**: `adb shell am force-stop com.kimiterrace.tvbridge`
- **Device Owner を外したい**: `adb shell dpm remove-active-admin com.kimiterrace.tvbridge/.TvDeviceAdminReceiver`
  （Device Owner は通常 factory reset でしか完全には外れない機種もある）
- **夜間に画面が消えない**: §9-2 を確認。lockNow 非対応機なら黒オーバーレイ運用＋TV 本体タイマー無効化(§6)で代替。
- **朝にランチャーが出る**: SignageActivity 再前面化が走っているか logcat（`SignageActivity` / `PowerController`）で確認。

関連: [ADB_SETUP_RUNBOOK.md](../ADB_SETUP_RUNBOOK.md)（v1 手順・prefs 直書きの原典） / [dist/README.md](README.md)
