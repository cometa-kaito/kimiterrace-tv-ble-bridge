# TV設定 ADB ランブック（クラス配備の再現手順）

PC から ADB で Google TV にキミテラスTVブリッジを設定する**再現手順**。
SIDELOAD_GUIDE.md（リモコン手動方式）の代替で、URL入力の破綻を避けられる**確実な方式**。
2026-05-30 に電子工学科3年機（device_id `8d30fcc4-…`）でこの手順を実行し、E2E検証まで完了済み。

> 🔑 シークレット（`SWITCHBOT_WEBHOOK_SECRET`）はこの文書では `<SECRET>` と表記。
> 実値は `06_LP/edix-lp/docs/POC_OPERATIONS.md` / Vercel環境変数を参照。コミットに実値を残さない。

---

## 0. 前提

- PC に adb（`C:\Users\<user>\AppData\Local\Android\Sdk\platform-tools\adb.exe` など）
- TV 側で**開発者モード→ネットワークデバッグON**（手順は SIDELOAD_GUIDE.md §2-3）
- PC と TV が**同一ネットワーク**（学校なら校内LAN）
- APK: `dist/tv-ble-bridge-debug.apk`（debug署名＝`run-as` 可）

## 1. このクラス用のパラメータを埋める

| 変数 | 例（電子工学科3年） | 取得元 |
|---|---|---|
| `TV_IP` | `10.11.70.155` | TVの設定→ネットワーク（ポートは5555固定） |
| `SENSOR_MAC` | `E2:E2:E8:85:3A:32` | センサ個体ごと。`ble_observe.py` でも確認可 |
| `SIGNAGE_URL` | `https://app.school-signage.net/?school=…&grade=…&department=…&class=…&kiosk=1` | サイネージ管理画面（Firestore） |
| `SCHOOL_ID/GRADE_ID/DEPARTMENT_ID/CLASS_ID` | URLのクエリと一致 | 同上 |
| `LABEL_LOCAL` | `電子工学科 3年` | 端末ローカル用（payloadタグ。日本語OK） |
| `LABEL_DASH` | `Denshi 3nen (ECE Y3)` | ダッシュボード用（**ASCII固定**, 下記§注意4） |

## 2. 接続・APK導入

```powershell
$adb = "C:\Users\<user>\AppData\Local\Android\Sdk\platform-tools\adb.exe"
& $adb connect <TV_IP>:5555
& $adb devices -l                      # `device` 表示を確認（unauthorizedならTV側で許可）
& $adb shell getprop ro.build.version.release   # Android版（11ならAPI30）
& $adb install -r "<repo>\...\dist\tv-ble-bridge-debug.apk"   # Success
```

> ⚠️ `INSTALL_FAILED_UPDATE_INCOMPATIBLE: signatures do not match`：既存が別の debug 鍵で署名（旧ローカルビルド等）。`-r` 不可。**`adb uninstall com.kimiterrace.tvbridge` → クリーン install** で対応（prefs・権限・appops は消えるので §3〜§4 を再実行）。以降は全機CIビルドAPKで統一すれば再発しない。

## 3. 設定（SharedPreferences）を直書き

URLに `?` `&` を含むため `input text` は使わない。XMLを作って `run-as` で書き込む。

**(a) ローカルにXML作成**（`&`→`&amp;`、日本語ラベルはそのまま。保存はUTF-8。シークレット入りなのでTemp等の非追跡領域へ）

```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name="target_mac"><SENSOR_MAC></string>
    <string name="webhook_url">https://www.school-signage.net/api/switchbot-webhook?key=<SECRET></string>
    <string name="signage_url"><SIGNAGE_URL の & を &amp; にエスケープ></string>
    <string name="config_endpoint">https://www.school-signage.net/api/tv/config?key=<SECRET></string>
    <string name="school_id"><SCHOOL_ID></string>
    <string name="grade_id"><GRADE_ID></string>
    <string name="department_id"><DEPARTMENT_ID></string>
    <string name="class_id"><CLASS_ID></string>
    <string name="device_label"><LABEL_LOCAL></string>
    <boolean name="autolaunch_signage" value="true" />
</map>
```

**(b) base64経由で書き込み**（日本語/特殊文字の経路事故を防ぐ）

```powershell
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\...\Temp\prefs.xml"))
$inner = "mkdir -p shared_prefs; echo $b64 | base64 -d > shared_prefs/tv_ble_bridge.xml; echo WROTE_OK"
& $adb shell "run-as com.kimiterrace.tvbridge sh -c '$inner'"
& $adb shell "run-as com.kimiterrace.tvbridge cat shared_prefs/tv_ble_bridge.xml"   # 読み戻して検証
```

- prefs実体: `/data/data/com.kimiterrace.tvbridge/shared_prefs/tv_ble_bridge.xml`
- 既存機を再設定する場合は **先に `am force-stop`**（稼働中はメモリ上のprefsがファイルを上書きするため）

## 4. 権限付与＋⚠️位置情報ON（最重要）

```powershell
& $adb shell pm grant com.kimiterrace.tvbridge android.permission.ACCESS_FINE_LOCATION
& $adb shell pm grant com.kimiterrace.tvbridge android.permission.ACCESS_COARSE_LOCATION
# ★Android 11はアプリ権限だけではBLEスキャン結果が返らない。端末の位置情報トグルON必須
& $adb shell cmd location set-location-enabled true
& $adb shell settings put secure location_mode 3
# ★★画面OFF（黒画面）に必須：SYSTEM_ALERT_WINDOW（他アプリの上に表示）。
#   黒画面はBootReceiver/アラームからバックグラウンド起動するため、未許可だとAndroid 11のBAL制限で起動できず、スケジュール消灯が一切動かない
& $adb shell appops set com.kimiterrace.tvbridge SYSTEM_ALERT_WINDOW allow
& $adb shell "appops get com.kimiterrace.tvbridge SYSTEM_ALERT_WINDOW"   # → allow
```

> ⚠️ **位置情報**: OFFだと logcat に `Permission denial: Location is off`、スキャン登録のみ・**検知ゼロ**。**TV再起動でOFFに戻ると検知が無言で止まる**ので再起動後は必ず再確認。
>
> ⚠️ **SYSTEM_ALERT_WINDOW**: 未許可だと黒画面が出ず、`appops get` に `rejectTime` が記録される（BootReceiver/OFFアラームのstartActivityが弾かれた痕跡）。許可後は logcat に `Background activity start … allowed because SYSTEM_ALERT_WINDOW permission is granted` が出る。**これが無いと「スケジュールは適用済なのに黒画面が出ない」状態になる**（2026-05-30に3年機で実際に発生→許可で解決）。

## 4.5 電源が勝手に切れる対策（PoC致命傷：消灯中は検知ゼロ）

「箱は常時ON・画面だけ黒」を維持する。adbで効く対策：

```powershell
& $adb shell settings put global hdmi_control_auto_device_off_enabled 0   # CEC連動オフ無効
& $adb shell settings put global no_signal_auto_power_off 0                # 無信号オフ無効
& $adb shell settings put system screen_off_timeout 2147483647            # 画面オフ実質無効
& $adb shell settings put secure sleep_timeout -1                          # スリープ無効
# ★スクリーンセーバ(Daydream)無効化 — 「画面だけ黒くなる」の最有力主因。FLAG_KEEP_SCREEN_ONが効かない瞬間に発動する
& $adb shell settings put secure screensaver_enabled 0
& $adb shell settings put secure screensaver_activate_on_sleep 0
& $adb shell settings put secure screensaver_activate_on_dock 0
& $adb shell settings put global stay_on_while_plugged_in 7                # 給電中は起きたまま（USB給電箱対策）
```

> ⚠️ **TV本体の電源オフ/スリープタイマー**：機種により `settings list global` に `tv_timer_power_off_timer_values=HH:MM` 等を持つ（ORION AI PONT で 16:30 が設定され、勝手に電源OFFしていた）。`tv_timer_*` を adb で書いても**firmware が自前設定を見ていて効かない場合がある**ので、**TVの設定UI（タイマー/電源/省エネ）でオフタイマー・スリープタイマーを必ず無効化**すること。
> 新APKは ON 時間帯=サイネージ / OFF 時間帯=黒画面 を常に前面化（FLAG_KEEP_SCREEN_ON）するので、無操作スリープも抑止される。
>
> ⚠️ **これらの `settings put` は再起動で元に戻る**（位置情報トグルと同型）。上の手動コマンドは「今すぐ効く即時対策」だが恒久ではない。**恒久化は §4.6 の権限付与＋新APK**（`KeepAwakeManager` が起動時・常駐中に自分で再適用する）で行う。

## 4.6 スリープ恒久対策の権限付与（新APK＝KeepAwakeManager 用・一度きり）

§4.5 の `settings put` を**アプリ自身が**起動時／常駐中／リモート `wake` 受信時に再適用するための権限。
一度付与すれば再起動 revert を解消でき、以後は学校に PC 不要で自走する。

```powershell
# Settings.Secure / Settings.Global（sleep_timeout / screensaver / stay_on 等）の書込権限
& $adb shell pm grant com.kimiterrace.tvbridge android.permission.WRITE_SECURE_SETTINGS
# Settings.System（screen_off_timeout）の書込権限（appops 経由）
& $adb shell appops set com.kimiterrace.tvbridge WRITE_SETTINGS allow
```

> 付与確認は logcat：MainActivity/サービス起動後に `KeepAwake: secure sleep_timeout=-1` / `screen_off_timeout=MAX` 等が出れば書込成功。`... skip: ...`（SecurityException）が出る場合は権限未付与のまま no-op。
> `WRITE_SECURE_SETTINGS` は `signature|privileged|development` 権限で、`pm grant` で一般アプリにも付与可。未付与でも前面再アサート（ON時間帯にサイネージを前面へ戻す）は動くが、`settings` のOS無効化は効かない。

## 4.7 遠隔から「起こす」（commands.wake）

落ちた／黒くなった TV を管理側から復帰させる信号。次のポーリング（最大60秒）で no-sleep 設定再適用＋サイネージ前面化が走る。

```powershell
# 一覧表示（device_id 確認）
$env:SWITCHBOT_WEBHOOK_SECRET="<SECRET>"; node 06_LP/edix-lp/scripts/wake-tv.mjs
# 対象を起こす
$env:SWITCHBOT_WEBHOOK_SECRET="<SECRET>"; node 06_LP/edix-lp/scripts/wake-tv.mjs <DEVICE_ID>
```

```bash
# curl 直叩きでも可（version がインクリメントされ1回だけ発火）
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"commands":{"wake":true}}' \
  "https://www.school-signage.net/api/tv/config?device_id=<DEVICE_ID>&key=<SECRET>"
```

## 5. 起動

```powershell
& $adb shell am start -n com.kimiterrace.tvbridge/.MainActivity   # webhook設定済＋権限ありでBleService自動開始
& $adb shell am start -a com.kimiterrace.tvbridge.OPEN_SIGNAGE    # サイネージ（キオスク）前面表示
```

> ⚠️ **clean install 直後は必ず一度 MainActivity を起動すること**。未起動のまま再起動すると、アプリが「stopped-state」のままで `BOOT_COMPLETED` が配信されず（Android仕様：未起動アプリは暗黙ブロードキャスト対象外）、`BootReceiver` が発火せず自動起動・黒画面復帰しない。一度起動すれば以降の再起動はBootReceiverが正常動作する。
>
> ⚠️ **clean install で device_id を引き継ぎたい場合**（既存リモート登録を維持・孤児レコード回避）：§3 の prefs XML に旧 `<string name="device_id">…</string>` を含めて書き込む。アプリは既存値があればそれを使う。

## 6. リモート設定に登録（以後PCから遠隔管理）

GETポーリングだけでは登録されない（行を作らない）。**device_id 指定でPOST upsert**が必要。

```powershell
# device_id は端末が生成済。prefsから取得：
& $adb shell "run-as com.kimiterrace.tvbridge cat shared_prefs/tv_ble_bridge.xml" | Select-String device_id
```

```bash
# 登録（ASCIIラベルはインラインJSONで可。日本語を送るなら --data-binary @ファイル でUTF-8固定）
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"label":"<LABEL_DASH>","school_id":"<SCHOOL_ID>","grade_id":"<GRADE_ID>","department_id":"<DEPARTMENT_ID>","class_id":"<CLASS_ID>","target_mac":"<SENSOR_MAC>","signage_url":"<SIGNAGE_URL>","webhook_url":"https://www.school-signage.net/api/switchbot-webhook?key=<SECRET>"}' \
  "https://www.school-signage.net/api/tv/config?device_id=<DEVICE_ID>&key=<SECRET>"
```

- `config_endpoint` には **key を含める**（ConfigPollerは device_id だけ付与しkeyは付けない）
- upsertは**部分更新**（未指定フィールドは既存値保持）。スケジュールだけ後追いPOSTしてもMAC/URL等は消えない

## 7. 画面ON/OFFスケジュール（リモート設定経由）

```bash
curl -s -X POST -H "Content-Type: application/json" \
  -d '{"schedule":{"enabled":true,"on_hour":7,"on_minute":30,"off_hour":19,"off_minute":0,"days_mask":124}}' \
  "https://www.school-signage.net/api/tv/config?device_id=<DEVICE_ID>&key=<SECRET>"
```

- `days_mask` はビット曜日（bit1=日,2=月,3=火,…,7=土）。**平日(月〜金)=124** / 毎日=254 / 月〜土=252
- OFF時刻=黒画面(BlackScreenActivity)＋HDMI-CEC standby試行、ON時刻=黒画面解除＋CEC wake
- 非稼働曜日はアラームを張らない＝直前のOFF状態が継続（例：金19:00 OFF→週末ずっと黒→月7:30 ON）
- TVは60秒間隔でポーリングし自動適用。`ConfigPoller: schedule updated` → `ScheduleManager: next ON/OFF` がログに出る

## 8. 検証（E2E）

```powershell
# 端末側：検知と送信
& $adb logcat -d -v brief | Select-String "EVENT DETECTED|Uploader|synced|Location is off|next ON|schedule updated"
# 期待: `EVENT DETECTED battery=…`, `synced N/N events`, `next ON = …`
```
```bash
# クラウド側：到達確認
curl -s "https://www.school-signage.net/api/sensor-stats?key=<SECRET>&hours=1"   # totalEvents 増加
curl -s "https://www.school-signage.net/api/tv/config?key=<SECRET>"              # devices[] に当該機
```

---

## ハマりどころまとめ

1. **位置情報トグル**（§4）— Android 11の最頻ハマり。OFFだと無言で検知ゼロ。
2. **prefs直書き**（§3）— URLのクォート事故回避。debug署名なので `run-as` 可。再設定は先に force-stop。
3. **登録はPOST**（§6）— GETポーリングは行を作らない。config_endpointにkey必須。
4. **非ASCIIラベルはリモート設定で文字化け** — libsql往復でUPDATEのたび既存値が再エンコードされ崩れる。ダッシュボードラベルはASCII固定。**機能タグは端末ローカルの日本語ラベルを使うので影響なし**。
5. **signage_urlを遠隔変更しても教室ID(school/grade/department/class)は再抽出されない** — クラス切替時は端末prefsの *_id も直接更新するか、upsertで明示送信。
6. **黒画面にSYSTEM_ALERT_WINDOW必須**（§4）— 未許可だと「スケジュールは適用済なのに消灯しない」。BootReceiver/OFFアラームのバックグラウンドActivity起動がBAL制限で弾かれるため。許可確認は `appops get … SYSTEM_ALERT_WINDOW`＝`allow`。
7. **スケジュール適用時は現在状態を強制しない**（既知の軽微な穴）— `isCurrentlyInOffPeriod` の判定は `BootReceiver`（起動時）と各アラーム発火時のみ。リモート設定で**OFF時間帯のさなかにスケジュールを変更/有効化しても、その瞬間には黒画面化されない**（次のアラーム発火 or 再起動まで持ち越し）。確実に即反映したいときは設定後に `adb reboot`。恒久対策はConfigPoller側で適用後に現在状態を反映するコード追加（要APK再ビルド）。
   - なお**ON復帰（朝7:30）は黒画面をdismissするがSignageActivityを再起動しない**。OFF中に再起動が挟まると朝の復帰時にサイネージではなくランチャーが出る可能性。PoC初日朝に要目視確認。

関連: [SIDELOAD_GUIDE.md](SIDELOAD_GUIDE.md) / [POC_OPERATIONS.md](../../../../06_LP/edix-lp/docs/POC_OPERATIONS.md) / メモリ `project_poc_sensor_pipeline.md`
