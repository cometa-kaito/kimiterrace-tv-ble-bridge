# 現地注入手順 2026-06-29 — 複数消灯時間帯（multi-offwindow）APK

岐南 電子工学科 **1/2/3年（3台）** へ、複数 ON/OFF 窓対応の新 APK を `adb install -r` で注入する現地手順。
コピペで完結する。困ったら §6 トラブルシュート。

## 0. 注入物
- **APK**: `dist/v2-build/tv-ble-bridge-multi-offwindow-20260629.apk`
- **sha256**: `b474c74ed79d9ae45c123752780d7df9a26f1afc31fa52a8ea644e514e9c99d4`（7,596,202 byte）
- **署名**: `0951…`（既存3台と一致）→ **アンインストール不要・`install -r` で上書き・prefs 全維持**
- **versionCode=1 / 0.1.0**（据置 ＝ install -r で再インストール）
- **機能**: `ScheduleConfig.windows`＝複数 ON/OFF 窓（分単位）。OFF＝全 ON 窓の外（昼休み消灯等）。ConfigPoller が v2 の `schedule_windows` を解析→適用。中身は branch `feat/tv-resilience-hardening` の全 working tree（resilience/Watchdog/a11y/NetworkCycle 同梱）。

## 1. 事前確認（出発前・PC で）
```bash
ADB=/c/Users/20051/platform-tools/adb.exe
APK="/c/Users/20051/Desktop/app/tv-ble-bridge/dist/v2-build/tv-ble-bridge-multi-offwindow-20260629.apk"
ls -la "$APK"
sha256sum "$APK"   # → b474c74ed79d9ae4… を確認
ls -la "$ADB"
```
- ⚠ **v2 側の事前条件**: 各機の `schedule_windows`（複数消灯の窓）が v2 ops で設定済みであること。**未設定なら端末は単一窓にフォールバック＝複数消灯は効かない**（APK は正常・データ待ち）。注入前に v2 側の窓設定を済ませておく。

## 2. 各機 共通の注入手順（テザリング経由）
gifu-edu 接続中は adb 不可。**端末をスマホのテザリング（Pixel ホットスポット）に乗せてから**：

```bash
ADB=/c/Users/20051/platform-tools/adb.exe
APK="/c/Users/20051/Desktop/app/tv-ble-bridge/dist/v2-build/tv-ble-bridge-multi-offwindow-20260629.apk"
IP=<テザリングIP>        # 下表参照（DHCP なので変わりうる）

# (a) 接続
"$ADB" connect $IP:5555
"$ADB" devices

# (b) 身元確認（取り違え防止 — §3 の期待値と一致するか）
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell getprop ro.product.model
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell dpm list-owners     # 3年のみ DeviceOwner

# (c) 注入（-r = prefs/device_id 維持）
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 install -r "$APK"         # → Success

# (d) 検証
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell dumpsys package com.kimiterrace.tvbridge | grep -E "versionName|lastUpdateTime"
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell settings get global http_proxy            # → 192.168.103.102:8080 維持
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell settings get secure accessibility_enabled # → 0 維持
# 起動確認（Watchdog armed / 盤面ロードのログ）
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 logcat -d -v time | grep -iE "Watchdog|ConfigPoller|SignageActivity|lock task" | tail -10

# (e) gifu-edu に戻す（端末側の Wi-Fi を gifu-edu へ）。adb は切れる＝正常。
```

## 3. 各機の身元（取り違え防止の要）
| 機 | `ro.product.model`（期待） | DeviceOwner | 前回テザリングIP | 静的IP(gifu-edu) | wlan MAC |
|---|---|---|---|---|---|
| 1年 | AI PONT 系（非ASTEX/非HKC） | ✗ 非DO | `10.248.149.70` | 172.16.20.201 | 28:7e:80:13:e1:5e |
| 2年 | `ASTEX 4K Android` | ✗ 非DO | `10.248.149.28` | 172.16.20.202 | 9c:95:61:73:e9:2e |
| 3年 | `4K SA Google TV`（HKC） | ✅ **DO** | `10.248.149.59` | 172.16.20.203 | 78:22:88:a9:24:a1 |
> **model で 3 機種は一意に割れる**（ASTEX=2年・HKC/4K SA=3年・残り=1年）。`dpm list-owners` が出る＝3年。IP が違っても model で確認すれば誤爆しない。

## 4. install -r が「維持するもの / 入れ替えるもの」
- **維持**: SharedPreferences 全部（`device_id`・`config_endpoint`・`signage_url`・`webhook_url`）、`settings global http_proxy`（=192.168.103.102:8080）、`accessibility_enabled=0`。→ **proxy も a11y OFF もそのまま**。
- **入れ替え**: アプリコードのみ（複数窓対応の新版へ）。再起動で新 OkHttpClient が proxy を再採用（3年も含め確実に）。
- ⚠ **生 prefs を adb で直接編集しない**（device_id 破損＝別端末化リスク）。`install -r` は安全。

## 5. 複数消灯（schedule_windows）が効いたかの確認
端末は **gifu-edu に戻って poll してから** v2 の `schedule_windows` を受信する（テザリング中は proxy 不達で受信しない＝正常）。確認手段:
- **方法A（推奨・非接触）**: v2 ops ダッシュボード／盤面で、設定した窓どおりに点灯/消灯するか。
- **方法B（adb）**: gifu-edu で数分 poll させた後、テザリングに戻して prefs を読む:
  ```bash
  MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell run-as com.kimiterrace.tvbridge cat shared_prefs/tv_schedule.xml | grep -i windows
  ```
  `windows` キーに窓の JSON 配列が入っていれば受信・適用済み。空なら v2 側の窓設定が未投入（§1 の事前条件）。

## 6. トラブルシュート
- **`adb connect` 不可**: 端末がテザリングに乗っているか / Pixel ホットスポットのクライアント一覧で IP 確認 / 数秒待って再 `connect`。reboot 後は net-adb が落ちることがある→再 `connect` で復活（3年で実績）。
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`（署名不一致）**: その端末が 0951 署名でない＝想定外。その場で `adb uninstall` すると **prefs/device_id を失う**ので、無理せず持ち帰り判断。
- **`Success` だが盤面が出ない**: `logcat` で例外確認。最悪 §7 ロールバック。
- **3年（DO/ロックタスク）**: `install -r` は DO でも通る。注入後 a11y が `enabled_accessibility_services` 経由で復活しうる→ `settings put secure accessibility_enabled 0` を再投入（[[project_ginan_ece_monitors_nightly_drop_3nen_offline]] の HKC reboot 副作用と同様）。

## 7. ロールバック（前版へ戻す）
```bash
PREV="/c/Users/20051/Desktop/app/tv-ble-bridge/dist/v2-build/tv-ble-bridge-l3recovery-plug-20260622.apk"  # sha e119b5df…・同0951署名
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 install -r "$PREV"
```

## 8. 注入後にやること
- APK-MANIFEST.md の「現地導入記録」へ追記（どの教室に・いつ・sha `b474c74e…` を入れたか）。
- 3台 gifu-edu 復帰 → 翌日、複数消灯が窓どおり効いているか最終確認。
