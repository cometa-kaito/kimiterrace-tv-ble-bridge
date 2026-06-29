# サイネージ no-DO プロビジョニング runbook（Device Owner 無し・抜けられる機向け）

> **用途**: factory reset 済み／新品の Google TV 機を **Device Owner 化せず**にサイネージ端末へ仕立てる。
> 「HOME で Google TV ランチャーに戻れる（抜けられる）」運用。**Device Owner 化できる機種が少ない**ため、
> これを no-DO 機の標準手順とする（背景: memory `project_kimiteras_signage_no_device_owner_direction`）。
>
> - **DO（キオスク・lock-task）版** → [provision-googletv.md](provision-googletv.md)
> - **provision 済み機の設置・確認・復旧** → [monitor-setup-runbook.md](monitor-setup-runbook.md)
> - 初回検証: 2026-06-18・岐阜工業高校 進路指導室前（HKC 4K GTV `lakeside`・192.168.11.13）。**全項目グリーン**。

---

## 0. 前提・環境

- adb: `C:\Users\20051\AppData\Local\Android\Sdk\platform-tools\adb.exe`（PATH 外＝フルパス）。
- APK: `dist/v2-build/tv-ble-bridge-nodo-kiosk-20260618.apk`（**no-DO フラグ `kiosk_enabled` 対応ビルド**。台帳 `dist/APK-MANIFEST.md`）。
- 端末側で **開発者オプション → ネットワークデバッグ ON**（factory reset 後は毎回 OFF に戻る）。
- `adb connect <IP>:5555` → 初回は TV 画面に RSA 許可ダイアログ → **許可**で `device` 表示。
- ⚠️ **git-bash の罠（毎回）**:
  - `/sdcard/...` 等 **端末側絶対パスは MSYS が壊す** → その adb 行だけ `MSYS_NO_PATHCONV=1` 前置（screencap/pull）。
  - **`MSYS_NO_PATHCONV` を export しない**（gcloud が空を返す）。
  - **スクショの pull はローカルが日本語パスだと git-bash で文字化け** → **PowerShell で pull する**のが確実（パスをネイティブ処理）。

```bash
ADB="/c/Users/20051/AppData/Local/Android/Sdk/platform-tools/adb.exe"
D="192.168.11.13:5555"; PKG="com.kimiterrace.tvbridge"
"$ADB" connect "$D"; "$ADB" devices -l        # device 表示を確認（unauthorized なら TV で許可）
```

## 1. UI 側（先に管理画面で済ませる）

1. v2 admin で学校・クラス（＝盤面ヘッダーの表示名。例「進路指導室前」）を作る。
2. tv-devices で**クラス選択フローから枠を作成** → magic-link が自動発行され、その **device_id** を控える。
3. device_id（例 `ef315334-93ff-48d2-9825-a096d716d655`）が §2 で端末 prefs に焼く値。

> 盤面ヘッダーに出るのは **クラス名**（学校名ではない）。「進路指導室前」と出したいならクラス名をそれにする。

## 2. APK 注入 + prefs 書き込み

```bash
APK="$(dirname "$ADB")/../../../../Desktop/app/tv-ble-bridge/dist/v2-build/tv-ble-bridge-nodo-kiosk-20260618.apk"
# ↑パスが面倒ならフルパス直書きで可
"$ADB" -s "$D" install "$APK"          # Success
```

**鍵（`prod-tv-poll-secret`）は本番シークレット**＝読み取りは**ユーザーの明示承認が要る**（auto-mode classifier が止める）。承認後、変数に取り込み**出力に一切出さない**。
prefs は **base64 経由で on-device decode** して書く（adb shell stdin の CRLF 事故回避）。

```bash
DEVID="ef315334-93ff-48d2-9825-a096d716d655"
SIGURL="https://app.school-signage.net/signage/<token>"     # 枠の signage_url

SECRET="$(gcloud secrets versions access latest --secret=prod-tv-poll-secret --project=signage-v2-prod)"
[ -z "$SECRET" ] && { echo "secret 取得失敗"; exit 1; }

XML="<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <string name=\"device_id\">${DEVID}</string>
    <string name=\"signage_url\">${SIGURL}</string>
    <string name=\"config_endpoint\">https://app.school-signage.net/api/tv/lp-config?key=${SECRET}</string>
    <boolean name=\"autolaunch_signage\" value=\"true\" />
    <boolean name=\"kiosk_enabled\" value=\"false\" />
</map>"
B64="$(printf '%s\n' "$XML" | base64 -w0)"
"$ADB" -s "$D" shell run-as "$PKG" mkdir -p shared_prefs
"$ADB" -s "$D" shell "echo $B64 | base64 -d | run-as $PKG sh -c 'cat > shared_prefs/tv_ble_bridge.xml'"
# 検証（key は伏字）
"$ADB" -s "$D" shell run-as "$PKG" cat shared_prefs/tv_ble_bridge.xml | sed -E 's/(key=)[^<"]+/\1[REDACTED]/'
```

**no-DO の肝 = `kiosk_enabled=false`**（既定は true ＝DO機向け）。これで:
- KeepAwakeManager の ON 期間 **前面強制復帰をしない**（HOME/Back で抜けられ引き戻されない）。
- lock-task ピンもしない。
- 起動時の自動表示（`autolaunch_signage=true`）は**独立**で残る → 抜けても **reboot で自動復帰**。
- `night_off_mode` は未設定＝既定 **overlay**（擬似黒・朝復帰最優先。最も安全。設定不要）。
- `target_mac` 未設定で可（SwitchBot 非設置のサイネージ専用機。`""`/既定MACにフォールバック）。
  - ⚠️ **`target_mac` を設定する＝SwitchBot 人感センサを使う機**は、§3 で**位置情報権限の付与が必須**（Android 11 は無いと BLE スキャンが無言で0件）。

## 3. 権限付与 + 起動

```bash
# --- BLE スキャン（SwitchBot 人感センサを使う機=target_mac 設定機のみ必須）---
# Android 11 以下は位置情報権限が無いと BLE スキャンが「結果0件・エラーも出さない」無言失敗になる。
"$ADB" -s "$D" shell pm grant "$PKG" android.permission.ACCESS_FINE_LOCATION    # BLE スキャンに必須（無いと無言で0件）
"$ADB" -s "$D" shell pm grant "$PKG" android.permission.ACCESS_COARSE_LOCATION  # 同上（FINE と併せて付与）
"$ADB" -s "$D" shell cmd location set-location-enabled true                     # 位置情報サービス本体を ON
"$ADB" -s "$D" shell settings put secure location_mode 3                        # 同上（vendor 差の保険）
"$ADB" -s "$D" shell settings get secure location_mode                          # 期待: 0 以外（0=OFF だと BLE 検知ゼロ）

# --- 画面制御 / 背面起動 ---
"$ADB" -s "$D" shell pm grant "$PKG" android.permission.WRITE_SECURE_SETTINGS  # 画面オフ/スクリーンセーバ無効化
"$ADB" -s "$D" shell appops set "$PKG" WRITE_SETTINGS allow                    # screen_off_timeout
"$ADB" -s "$D" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow               # 背面からの startActivity / 夜間 overlay

"$ADB" -s "$D" shell am start -n "$PKG/.MainActivity"                          # 初期化 + BleService 開始 + 即ポール（stopped-state 解除）
sleep 4
"$ADB" -s "$D" shell dumpsys activity services "$PKG" | grep "\.BleService"    # 常駐確認
"$ADB" -s "$D" shell am start -a com.kimiterrace.tvbridge.OPEN_SIGNAGE -n "$PKG/.SignageActivity"  # サイネージ前面化
```

> ⚠️ **BLE が無言で0件になる罠（Android 11 仕様・2026-06-20 岐阜工業 進路指導室前で実際に踏んだ）**:
> `ACCESS_FINE_LOCATION` 未付与だと、SwitchBot 人感センサの BLE スキャンが **結果0件・例外も logcat エラーも出さず**に
> 黙って失敗する（＝人感センサが「全く検知されない」が原因不明に見える）。**位置情報権限の付与＋位置情報サービス本体の
> ON（`settings get secure location_mode` が 0 でない）の両方**が要る。付与漏れ時の復旧は
> `pm grant … ACCESS_FINE_LOCATION`（+ COARSE）→ **アプリ再起動**（`am force-stop "$PKG"` → 再 `am start`）。
> サイネージ専用機（SwitchBot 非設置＝`target_mac` 未設定）はこの権限は不要。

> ⚠️ **既定ホームに設定しない**。MainActivity は manifest で `category.HOME` を持つ（DO ソフトキオスク用）が、
> **default home に据えなければ** HOME 押下は Google TV ランチャーへ行く＝抜けられる。`cmd package set-home-activity` は**打たない**。

## 4. 検証（全部グリーンで完了）

```bash
# (a) poll 成功＝鍵が効き server に check-in
"$ADB" -s "$D" shell run-as "$PKG" cat shared_prefs/tv_ble_bridge.xml | grep -E "last_poll_ok_ms|config_version"
#     last_poll_ok_ms が非0・config_version>=1 ＝🟢（admin を開かずとも check-in 済）

# (b) 表示（端末保存→pull は PowerShell が確実）
#   PowerShell:
#   & $adb -s $d shell screencap -p /sdcard/sc.png
#   & $adb -s $d pull /sdcard/sc.png "C:\Users\20051\sc.png"
#   → ヘッダーに 日付/時刻/<クラス名>/「キミテラス by Rebounder」、右に広告枠（未配信はウォーターマーク）

# (c) HOME で抜けられる（no-DO の本丸）
"$ADB" -s "$D" shell input keyevent KEYCODE_HOME
sleep 8
"$ADB" -s "$D" shell dumpsys activity activities | grep mResumedActivity
#     → launcherx/.VanillaModeHomeActivity のまま（8s 後も引き戻されない）＝OK

# (d) reboot 復帰（BootReceiver→autolaunch で自動表示）
"$ADB" -s "$D" reboot
#     ~20s で再接続（network-adb が persist する機）→ mResumedActivity=SignageActivity を確認
```

**判定**: (a) poll 200・(b) クラス名表示・(c) HOME 抜け＆引き戻し無し・(d) reboot 自動復帰 = 全部緑で完了。

## 5. 地雷集（no-DO 特有 + 共通）

- **BLE 人感センサが「全く検知されない」→ まず位置情報権限を疑う（Android 11）**。`ACCESS_FINE_LOCATION` 未付与だと BLE スキャンが**無言で0件**（エラーも出ない）。§3 の `pm grant … ACCESS_FINE_LOCATION`（+ COARSE）＋ 位置情報サービス本体 ON（`settings get secure location_mode` が 0 以外）＋ **アプリ再起動**で解消。2026-06-20 岐阜工業 進路指導室前で実踏。**no-DO 機は DO のような自動付与が無い＝SwitchBot を使う全 no-DO 機で手動 grant が要る**（進路指導室前=192.168.11.13 は対応済み。他の no-DO 機を新設する際も同じ grant を忘れない）。
- **本番シークレット読取は composite で classifier がブロック**（APK install と束ねると特に）。`gcloud secrets … prod-tv-poll-secret` 単体でも**ユーザー明示承認が要る**。承認語（例「鍵を読んでいい」）を貰ってから。
- **シェル変数は呼び出し間で揮発**（このツールの仕様）。**鍵取得→prefs 書込は同一 bash 呼び出し**で完結させる（`$SECRET` が次の呼び出しに残らない）。
- **prefs はアプリ起動前に run-as で先に書く**＝`device_id` をアプリの自動生成 UUID に取られない（`Config.deviceId` は未設定時にランダム発行）。
- **clean install 直後は MainActivity を一度起動**（未起動=stopped-state で BOOT_COMPLETED が来ず BootReceiver 不発）。
- **prefs はプロセスにキャッシュ**＝書き換えたら force-stop→relaunch か reboot。
- **スクショ stdout 破損**（HKC は `Init wrapper sys mutex…` を混入）→ 必ず端末保存→pull。**pull は PowerShell**（日本語パス文字化け回避）。
- **秘密鍵を出力に出さない**（`sed 's/key=…/key=[REDACTED]/'`）。

---

## 参照
- DO 版（キオスク）: [provision-googletv.md](provision-googletv.md)
- 設置・復旧: [monitor-setup-runbook.md](monitor-setup-runbook.md)
- APK 台帳: [APK-MANIFEST.md](APK-MANIFEST.md)
- memory: `project_kimiteras_signage_no_device_owner_direction` / `reference_kimiteras_tv_connect_and_deploy`
