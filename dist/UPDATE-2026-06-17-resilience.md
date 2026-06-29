# 現地アップデート手順：レジリエンス版APK 導入＋切断モニタの復旧（2026-06-17）

> 対象: 岐南工業 電子工学科サイネージ（`com.kimiterrace.tvbridge`）。
> 目的: 全面堅牢化版 **`tv-ble-bridge-resilience-20260617.apk`** を流し込み、
> (a) **切断中の 1年/3年モニタを復旧**し、(b) 全機を「一過性事象で永久死しない」状態に**免疫化**する。
> ⚠ 切断中の2台はポーリングしていない＝**遠隔配信は不可。校内LAN内のPCからの adb 作業が必須**。

---

## 0. これは何を直すか（背景）
- 2026-06-17 03:47 JST に電子工学科の2台（1年/3年）が同時に無音化。原因は「再起動時の起動経路クラッシュ」または「プロセス生存のままポーリング停止」で、**端末アプリが自己回復しきれていなかった**。
- 本APKで、起動経路の堅牢化／poll死活の自己診断→自動再生成／Watchdog(Alarm+WorkManager)／全例外の自動復帰 等を入れた。**今後この症状は出さない**設計。
- 端末側で唯一塞げないのは stopped-state（手動 force-stop / 初回未起動）。だから下記 §4 で**導入後に必ず1回 MainActivity を起動**すること。

---

## 1. 準備（PC = 校内LAN内 / Windows）
```bash
ADB="/c/Users/20051/AppData/Local/Android/Sdk/platform-tools/adb.exe"
APK="C:/Users/20051/Desktop/app/tv-ble-bridge/dist/v2-build/tv-ble-bridge-resilience-20260617.apk"
PKG="com.kimiterrace.tvbridge"
```
- 各モニタの **IP** を用意（TV設定→ネットワーク→接続中Wi-Fi、または校内ルータのDHCP一覧）。
- device_id ↔ 教室の対応（prod ログ実測 2026-06-17）:
  - `e5e0a86a-…` = 稼働中（おそらく **2年**）
  - `91bc4164-…` / `c6c8c16f-…` = **切断中（1年 / 3年）** ← 今回の主対象
  - 正確な学年対応は v2 管理画面 `/ops/tv-devices` で確認可。

---

## 2. 接続
```bash
MSYS_NO_PATHCONV=1 "$ADB" connect <TV_IP>:5555
"$ADB" devices -l           # `device` 表示ならOK（`unauthorized` ならTV画面で許可）
```
> ⚠ 端末側パス(`/sdcard/...`)を含む adb 行のみ `MSYS_NO_PATHCONV=1` を前置（global export 禁止=gcloud が壊れる）。

---

## 3. APK 導入（全機：稼働機も切断機も同じ手順）
```bash
"$ADB" -s <TV_IP>:5555 install -r "$APK"      # Success
```
> ⚠ `INSTALL_FAILED_UPDATE_INCOMPATIBLE`（署名鍵 churn）が出たら旧版を消してから入れる:
> ```bash
> "$ADB" -s <TV_IP>:5555 uninstall $PKG
> "$ADB" -s <TV_IP>:5555 install "$APK"
> ```
> uninstall すると prefs(device_id/config_endpoint 等)が消える。その場合は新規プロビジョニング扱い
> （`provision-googletv.md` §4 の device_id 再設定）が必要なので、**まず -r での上書きを試す**。

---

## 4. 起動（stopped-state 解除のため必須）
```bash
"$ADB" -s <TV_IP>:5555 shell am start -n $PKG/.MainActivity
"$ADB" -s <TV_IP>:5555 shell ps -A | grep kimiterrace          # プロセス常駐を確認
```
> ⚠ clean install 直後は**必ず一度 MainActivity を起動**（未起動だと BOOT_COMPLETED が来ず自動起動が発火しない）。
> ⚠ lock-task(キオスク)中で force-stop できない端末は **reboot** で新APKを反映:
> `"$ADB" -s <TV_IP>:5555 reboot`（`persist.adb.tcp.port` 未設定機は reboot 後に network-adb が戻らない＝検証はブラインド）。

---

## 5. 復旧確認（=接続🟢）
**端末側**: lp-config 疎通（鍵は出力に出さない）:
```bash
XML=$("$ADB" -s <TV_IP>:5555 shell run-as $PKG cat shared_prefs/tv_ble_bridge.xml)
EP=$(echo "$XML" | sed -n 's/.*name="config_endpoint">\([^<]*\)<.*/\1/p')
DID=$(echo "$XML" | sed -n 's/.*name="device_id">\([^<]*\)<.*/\1/p')
curl -s -w '\n[HTTP %{http_code}]\n' "${EP}&device_id=${DID}&fcmToken=" | sed -E 's/(key=)[A-Za-z0-9_-]+/\1<REDACTED>/g'
#   期待: HTTP 200・"device_label":"電子工学科 N年"・"target_mac":""
```
**サーバ側（PCどこからでも）**: 60秒待ってから last_seen が更新＝🟢:
```bash
gcloud logging read 'logName="projects/signage-v2-prod/logs/run.googleapis.com%2Frequests" AND httpRequest.requestUrl:"device_id=<その端末のdevice_id>"' \
  --project signage-v2-prod --freshness=5m --limit=3 --format='value(timestamp)'
#   直近の timestamp が出れば復活。管理画面 /ops/tv-devices でも 🟢 を確認できる。
```

---

## 6. 画面確認（任意・スクショ）
```bash
MSYS_NO_PATHCONV=1 "$ADB" -s <TV_IP>:5555 shell screencap -p /sdcard/sc.png
MSYS_NO_PATHCONV=1 "$ADB" -s <TV_IP>:5555 pull /sdcard/sc.png "C:/Users/20051/Desktop/app/_sc.png"
MSYS_NO_PATHCONV=1 "$ADB" -s <TV_IP>:5555 shell rm -f /sdcard/sc.png
```
> ⚠ HKC 機は `exec-out screencap -p > a.png` だとベンダーログ混入で PNG 破損。必ず端末保存→pull。

---

## 7. 後始末／台帳
- 導入したら [APK-MANIFEST.md](APK-MANIFEST.md) に「どの端末(device_id/教室)に・いつ・どのハッシュ(`48ac8ebf…`)を入れたか」を追記。
- 全機 install 後は、深夜の瞬停・OEM kill・想定外クラッシュからでも自動復帰するようになる（人手の再訪を減らす）。

参照: [monitor-setup-runbook.md](monitor-setup-runbook.md)（一般の設置・確認・復旧）/ [provision-googletv.md](provision-googletv.md)（新規 factory 機）。
