# 引き継ぎ：岐南モニタ「夜間ダウン」真因＝プロキシ未対応（2026-06-25 夜）

⚠ これまでの `HANDOFF-2026-06-25.md`（Wi-Fiトグル a11y 復帰）は**誤診**。本書が最新。
関連メモリ: `project_ginan_ece_monitors_nightly_drop_3nen_offline`

## 0. TL;DR
- **真因（ほぼ確定）**: アプリ `ConfigPoller`(OkHttp) が proxy を使わず**直結**で `app.school-signage.net:443` に出ようとし、県 gifu-edu は**明示プロキシ必須**なので弾かれていた。`ConfigPoller.kt:65` の OkHttp に proxy 設定ゼロ。Android は **Wi-Fi 手動 proxy をアプリ独自 HTTP に自動適用しない**（WebView/ブラウザのみ自動→だからブラウザは元から通り、**アプリの心拍だけ**落ちていた）。
- **Wi-Fiトグル/a11y 復帰は誤診**（同 SSID 再接続では直らず、ログでも無線フルサイクルしたのに poll 戻らずを確認）。
- **修正**: 端末に `settings global http_proxy` を入れてアプリも proxy 経由にする。proxy 実体 IP=`192.168.103.102:8080`（全機共通・端末 ping で実測）。
- **進捗（6/25夜・全機完了）**: 1年=完了（gifu-edu で poll 成功実測）。2年=完了（proxy 経由 poll 成功を 19:49 に実証）。**3年=完了（HKC は running app が proxy を live 採用せず→`adb reboot` で採用確認。reboot 副作用で a11y 復活＋exclusion 消失→再投入で是正）**。**3台とも proxy 経由でアプリが v2 へ通うことを実証済**。**✅2026-06-29: gifu-edu で複数晩・1/2/3年とも安定動作をユーザー現認＝夜間ダウン解決**。
- ✅**確定済（2026-06-29）**: 「深夜の切断→再接続→外向き拒否（intermittent）」も proxy が越えた — gifu-edu で複数晩、1/2/3年とも安定動作をユーザー現認。**夜間ダウン問題は解決**。

## 1. 端末別 設定値（gifu-edu）
共通: pass `gifu-003` / GW `172.16.20.40` / prefix `24` / DNS `192.168.103.111,192.168.103.115` / bypass `*gifu-net.ed.jp,localhost` / proxy port `8080` / **proxy IP `192.168.103.102`**

| 機 | Wi-Fi手動proxy(欄) | 端末静的IP | MAC | 機種 | テザリングIP | device設定 |
|---|---|---|---|---|---|---|
| 1年 | proxygate2.gifu-net.ed.jp | 172.16.20.201 | 28:7e:80:13:e1:5e | AI PONT(非DO) | 10.248.149.70 | ✅ global proxy=192.168.103.102:8080 + a11y OFF |
| 2年 | 192.168.103.102 | 172.16.20.202 | 9c:95:61:73:e9:2e | ASTEX 4K(非DO) | 10.248.149.28 | ✅ global proxy=192.168.103.102:8080 + a11y OFF（6/25夜・.28・post-verify三値一致） |
| 3年 | 192.168.103.102 | 172.16.20.203 | 78:22:88:a9:24:a1 | HKC(DO/lock-task) | 10.248.149.59 | ✅ proxy + a11y OFF（6/25夜・HKCはreboot要→ConfigPollerがproxy宛で採用確認・exclusion再投入） |

## 2. 残り（3年）に入れるコマンド（テザリング接続中・adb到達時）
```bash
ADB=/c/Users/20051/platform-tools/adb.exe ; IP=10.248.149.59
"$ADB" connect $IP:5555
# 身元確認（HKC・DO機）
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell getprop ro.product.model    # 期待: HKC系
# 投入
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell settings put global http_proxy 192.168.103.102:8080
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell "settings put global global_http_proxy_exclusion_list '*.gifu-net.ed.jp,localhost'"
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell settings put secure accessibility_enabled 0   # DO機は元々寝落ち無し・揃えるなら
# 確認
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell settings get global http_proxy
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell settings get global global_http_proxy_exclusion_list
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell settings get secure accessibility_enabled
```
※ a11y OFF は実機状態変更なので**ユーザーの明示 OK を取ってから**（1年/2年は取得済）。3年は DO/ロックタスク機で元々寝落ち無し＝OFF は任意。
※ exclusion は device 側 sh の glob 回避のため**シングルクォートで包んで**渡す（`shell "... '*.gifu-net.ed.jp,localhost'"`）。

## 3. 検証
- 各機を gifu-edu に戻す → ダッシュボードで `last_seen` が毎分更新（**adb は gifu-edu 接続中は不可**＝テザリングに戻して logcat 確認 or dashboard）。
- proxy IP `192.168.103.102:8080` は **gifu-edu 内部アドレス**＝テザリング中は届かず poll は通らない（想定どおり）。検証は **gifu-edu 復帰後**に行う。
- **本番判定＝一晩**: 3台 gifu-edu で放置 → 翌朝「夜間の切断→再接続を越えて維持/自動復帰したか」。intermittent なので数晩 or 2/3年を proxy なしの A/B で「1年だけ落ちない夜が続く」を見れば確証。

## 4. 重要な区別 / 注意
- **Wi-Fi 設定欄の proxy**（ユーザーが入力＝ブラウザが使う）と **`settings global http_proxy`**（adb で投入＝アプリ ConfigPoller が使う）は**別物**。アプリの心拍を通すには**後者が必須**。
- **IP 直**(`192.168.103.102:8080`)採用＝DNS 非依存（夜間 DNS 劣化への保険）。`proxygate2` はホスト名で gifu-edu 内部 DNS でのみ解決（PC/外部 DNS では引けない）。
- **ダウン防止は無傷**: a11y OFF は「Wi-Fiトグル復帰（寝落ち原因・今回無効）」だけ停止。Watchdog(15分自己回復)/常駐 BleService/WifiLock/網回復→即ポーリング/no-sleep は稼働（1年/2年で実測）。「網回復→即 poll」は夜間再接続に直接効く。
- adb: `C:\Users\20051\platform-tools\adb.exe`、`MSYS_NO_PATHCONV=1` 必須・gifu-edu 接続中は adb 不可（テザリングに戻して操作）。
