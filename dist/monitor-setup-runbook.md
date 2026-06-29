# サイネージモニタ 設置・確認・復旧 runbook（1枚／provision 済み機向け）

> **用途**: 「このモニタ（IP / クラス指定）をセットアップして」と頼まれたときに**これ1枚で完結**させる。
> provision 済み（device 登録・鍵注入済）の端末を **起動→表示→接続🟢確認** する手順と、
> 今日（2026-06-13）現地で踏んだ**地雷集**。
>
> - **factory reset からの新規構築**（Device Owner 化・権限付与・スケジュール）→ [provision-googletv.md](provision-googletv.md)
> - **v2 web のデプロイ**（lp-config 等のサーバ修正）→ `キミテラス-v2/docs/runbooks/web-deploy.md`
> - 端末アプリ = `com.kimiterrace.tvbridge`（Kotlin・本ツリー）。prefs 実体 =
>   `/data/data/com.kimiterrace.tvbridge/shared_prefs/tv_ble_bridge.xml`（debug 署名なので `run-as` で読み書き可）

---

## 0. 環境（Windows / このPC）

- adb 実体: `C:\Users\20051\AppData\Local\Android\Sdk\platform-tools\adb.exe`（git-bash の PATH には無い＝フルパス指定）
- **シェルは git-bash(Bash tool) が楽**。ただし⚠️:
  - **`/sdcard/...` など端末側の絶対パスは git-bash が Windows パスに変換して壊す** → adb 呼び出しに **`MSYS_NO_PATHCONV=1` を前置**（`adb connect`・`pull`・`screencap /sdcard/...` 等）。
  - **`MSYS_NO_PATHCONV` を global export しない**（gcloud が壊れて空を返す）。**adb 行にのみ**前置。
- 接続は `adb connect <IP>:5555`。`device` 表示になればOK（`unauthorized` ならTV側で許可ダイアログ承認）。

```bash
ADB="/c/Users/20051/AppData/Local/Android/Sdk/platform-tools/adb.exe"
DEV="192.168.11.12:5555"; PKG="com.kimiterrace.tvbridge"
MSYS_NO_PATHCONV=1 "$ADB" connect "$DEV"
"$ADB" devices -l
```

---

## 1. 端末レジストリ（device_id = lp-config のマッチキー）

| 教室 | device_id | 備考 |
|---|---|---|
| **岐阜工業高校 進路指導室前** | `ef315334-93ff-48d2-9825-a096d716d655` | **no-DO（Device Owner 無し・抜けられる）**。同じ HKC 機を factory reset → 192.168.11.13 / `lakeside`。APK=`tv-ble-bridge-nodo-kiosk-20260618.apk`（kiosk=false）。signage_url=`eU0mdHFP…`。**2026-06-18 注入・全項目検証済🟢**（[provision-nodo-runbook.md](provision-nodo-runbook.md)）|
| ~~1年1組~~ | `73f65bf0-feeb-4864-90a7-b030a9713d98` | （旧）192.168.11.12 / HKC 4K Google TV(`lakeside`)。**↑へ factory reset 済**。テスト校で別系統。signage_url=`qERLY4wH…?design=pattern2` |
| 岐南 電子工学科 1〜3年 | provision-googletv.md §4 の表 | 別 device_id（Device Owner 化済の実運用機） |

> device_id は**端末 prefs に焼く値**＝v2 のシード行と一致させる。違うと lp-config が空応答になり何も出ない。

---

## 2. フロー判定

```
端末は provision 済み？（アプリ導入済み＆device_id/config_endpoint が prefs にある）
├─ YES → §3（起動→表示→確認）。汚染やクラッシュがあれば §4。
└─ NO（factory/新品）→ provision-googletv.md（Device Owner 化・鍵注入から）
```

provision 済みか確認:

```bash
"$ADB" -s "$DEV" shell pm list packages | grep kimiterrace        # 入っているか
"$ADB" -s "$DEV" shell run-as $PKG cat shared_prefs/tv_ble_bridge.xml   # device_id / config_endpoint があるか
```

---

## 3. 設置（provision 済み機）— 今日効いた手順

```bash
# (1) アプリ起動（autolaunch_signage=true でも、未起動なら手動で1回起動する）
"$ADB" -s "$DEV" shell am start -n $PKG/.MainActivity

# (2) クラッシュせず常駐したか
"$ADB" -s "$DEV" shell ps -A | grep kimiterrace                  # プロセスが居る
"$ADB" -s "$DEV" shell dumpsys activity activities | grep mResumedActivity   # 前面が $PKG/.MainActivity

# (3) lp-config 疎通（200 かつ device_label が想定クラス）。鍵は prefs から取り出し、出力には出さない
XML=$("$ADB" -s "$DEV" shell run-as $PKG cat shared_prefs/tv_ble_bridge.xml)
EP=$(echo "$XML" | sed -n 's/.*name="config_endpoint">\([^<]*\)<.*/\1/p')
DID=$(echo "$XML" | sed -n 's/.*name="device_id">\([^<]*\)<.*/\1/p')
curl -s -w '\n[HTTP %{http_code}]\n' "${EP}&device_id=${DID}&fcmToken=" | sed -E 's/(key=)[A-Za-z0-9_-]+/\1<REDACTED>/g'
#   期待: HTTP 200・"device_label":"<クラス名>"・"target_mac":""（null ではない＝サーバ修正済）

# (4) 画面確認（スクショ）。⚠️ exec-out 直は HKC 機のベンダーログ "Init wrapper…" で PNG 破損する。
#     必ず「端末に保存→pull」する。device 側パスは MSYS_NO_PATHCONV=1。
MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell screencap -p /sdcard/sc.png
MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" pull /sdcard/sc.png "C:/Users/20051/Desktop/app/_sc.png"
MSYS_NO_PATHCONV=1 "$ADB" -s "$DEV" shell rm -f /sdcard/sc.png
#   → ヘッダーに 日付/時刻/クラス名/キミテラス by Rebounder が出ていれば表示OK
```

**接続🟢の判定**: lp-config が 200 を返し、ConfigPoller が 60 秒毎にポーリング → v2 が `last_seen` を更新。
(3) で 200 かつ `device_label` が一致していれば実質🟢。admin UI を開く必要はない。

---

## 4. 既知バグと復旧

### 4-1. 起動直後クラッシュ → 画面が出ない（2026-06-13 サーバ側修正済）

- **症状**: 起動するとすぐ落ちてランチャーに戻る。logcat に
  `IllegalArgumentException: invalid device address NULL` /
  `BleService.startScan` / `setDeviceAddress`。
- **原因**: lp-config が `target_mac:null` を返し、旧APKの `JSONObject.optString` が
  Android 仕様で **null を文字列 "null" に化かす** → prefs に `target_mac="NULL"` 保存 →
  `ScanFilter.setDeviceAddress("NULL")` が例外。**SwitchBot 非設置のサイネージ専用機で必発**。
- **恒久対処（済）**: v2 lp-compat で `null`→`""` に修正（PR #855 → prod web `dfc0c5a`）。
  以後サーバは `target_mac:""` を返し、端末の `isNotBlank()` ガードがスキップするので**再発しない**。
- ⚠️ **サーバ修正は既に汚れた prefs を自動回復しない**。`target_mac="NULL"` のまま残っている実機は
  **一度だけ手動クリーン**が要る（下記）。クリーン後はポーリングで再汚染されない。

```bash
# 既に target_mac="NULL"（または webhook_url="null"）になっている端末の一度きりの手当て
"$ADB" -s "$DEV" shell am force-stop $PKG
"$ADB" -s "$DEV" shell "run-as $PKG sed -i 's/>NULL</>DC:A5:B3:C2:98:D7</; s/\"webhook_url\">null</\"webhook_url\"></' shared_prefs/tv_ble_bridge.xml"
"$ADB" -s "$DEV" shell run-as $PKG cat shared_prefs/tv_ble_bridge.xml | grep -E "target_mac|webhook_url"   # 検証
"$ADB" -s "$DEV" shell am start -n $PKG/.MainActivity
#   DC:A5:B3:C2:98:D7 = BuildConfig.DEFAULT_TARGET_MAC（有効なMAC形式）。
#   ⚠️ 空文字 "" は setDeviceAddress が弾くので不可。必ず有効MAC形式にする。
```

### 4-2. v2 へ向け直すだけ（config_endpoint 差し替え）
provision-googletv.md §10（SAFE FALLBACK）参照。

---

## 5. 地雷集（毎回ここを見る）

- **git-bash の `/sdcard/...` パス変換** → adb 行に `MSYS_NO_PATHCONV=1` を前置。global export 禁止（gcloud が壊れる）。
- **screencap の stdout 破損**: HKC 機は `Init wrapper sys mutex…` 等をstdoutに混ぜる。`exec-out screencap -p > a.png` だと壊れる → **端末保存→pull**。
- **prefs はプロセスにキャッシュされ外部書込を再読込しない** → prefs を直したら **force-stop してから relaunch**（または reboot）。
- **lock-task(キオスク)中は停止不可**（force-stop/run-as kill 全拒否）→ 設定反映は **reboot 必須**（BootReceiver autostart で新 prefs 読込）。※今回の HKC 1年1組は Device Owner 未化＝lock-task NONE なので force-stop で足りた。
- **`persist.adb.tcp.port` 未設定の機は reboot で network-adb が戻らない**（再 listen しない）。reboot 検証はブラインドになる → 現地で電源断確認するか、本項目の poll 耐性確認で代替。
- **clean install 直後は必ず一度 MainActivity を起動**（未起動だと stopped-state で BOOT_COMPLETED が来ず BootReceiver が発火しない）。
- **秘密鍵（lp-config の key・FCM トークン）をコマンド/出力に出さない**。prefs から変数に取り、`sed 's/key=[..]/key=<REDACTED>/'` で伏字。

---

## 参照
- 新規構築: [provision-googletv.md](provision-googletv.md)
- v2 web デプロイ: `キミテラス-v2/docs/runbooks/web-deploy.md`
- magic link 再発行: prod Cloud Run Job `kimiterrace-seed-ginan-sig`
- memory: `reference_kimiteras_tv_connect_and_deploy.md` / `reference_kimiteras_tv_bridge_app.md`
