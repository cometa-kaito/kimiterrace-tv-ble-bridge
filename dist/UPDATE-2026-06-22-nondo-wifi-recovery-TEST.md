# 現地テスト手順：非DO Wi-Fi 復旧（rung1 再検証 / rung2 機内モード）— 2026-06-22

> 目的: HKC 4K Google TV（MediaTek/CVTE・Android14）で、**非Device-Owner でも使える Wi-Fi 張り直し**が
> 実機で効くかを確定する。rung1（`reportNetworkConnectivity`・特権不要）と rung2（機内モードトグル・
> `WRITE_SECURE_SETTINGS`）を adb トリガで個別検証する。
>
> 背景: 夜間に「県Wi-Fi接続済なのに v2 不達（L3セッション死）」→ reboot/別ネットで回復、を自動化する手。
> DO機は `DevicePolicyManager.reboot()` で解決済。本テストは**非DO機の代替**の成否確認。

- 対象 APK: `dist/v2-build/tv-ble-bridge-l3recovery-plug-20260622.apk`（sha256 先頭 `CB01D964…`・署名 0951・size 7,650,281）
- **自動復帰は毎朝 05:00**（表示スケジュールと独立・休日も）に発火: DO機+opt-in=graceful reboot ／ それ以外=rung1+Wi-Fiトグル。
- 検証する手は4つ: **rung1**=`reportNetworkConnectivity`(再検証・Wi-Fi切れない) / **案A**=機内モードON/OFF(全ラジオ) / **案B**=`wifi_on`トグル(Wi-Fiのみ) / **DO reboot**=`DevicePolicyManager.reboot()`(DO機・電源切らずOS再起動)。案A/案Bは~8秒Wi-Fi断、DO rebootは端末再起動。
- ⚠ **1年/2年/3年とも今夕この新ビルドを入れる**（旧resilience版は自動reboot無し＝1年が今朝落ちた一因）。各機で §1 の install＋`pm grant`＋DO確認を行う。
- adb: `C:\Users\20051\platform-tools\adb.exe`
- ⚠ テスト中 **Wi-Fi が約8秒切れる**（rung2）。**夜間OFF時間帯（黒画面）に実施**すれば画面影響なし。
- ⚠ 安全網あり: 機内モードは in-app タイマで8秒後OFF＋Watchdog が15分以内に固着強制OFF。最悪リモコン/再起動で復帰。

---

## 0. 接続準備（現地）
1. 対象モニタ と この PC を**同じテザリング**に繋ぐ。
2. モニタの IP を確認（TV設定→ネットワーク or ルータDHCP）。以下 `IP=` に入れる。
```bash
ADB=/c/Users/20051/platform-tools/adb.exe
IP=<モニタのIP>          # 例 10.248.149.59
APK="C:/Users/20051/Desktop/app/tv-ble-bridge/dist/v2-build/tv-ble-bridge-l3recovery-plug-20260622.apk"
"$ADB" connect $IP:5555   # 「タイムアウト」なら数回試す/再起動直後はnet-adbが落ちている事あり
"$ADB" devices -l         # `device` 表示でOK（HKC_4K_GTV）
```

## 1. 注入 ＋ 権限付与（一度きり）
```bash
"$ADB" -s $IP:5555 install -r "$APK"                                   # Success（署名一致でprefs維持）
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell am start -n com.kimiterrace.tvbridge/.MainActivity
# 権限（未付与なら）。rung2(機内モード)に WRITE_SECURE_SETTINGS が必須。
"$ADB" -s $IP:5555 shell pm grant com.kimiterrace.tvbridge android.permission.WRITE_SECURE_SETTINGS
"$ADB" -s $IP:5555 shell pm grant com.kimiterrace.tvbridge android.permission.BLUETOOTH_CONNECT
"$ADB" -s $IP:5555 shell pm grant com.kimiterrace.tvbridge android.permission.BLUETOOTH_SCAN
# 新ビルド稼働確認
"$ADB" -s $IP:5555 logcat -d -t 400 | grep -iE "wifi lock acquired|watchdog armed|BleService onCreate done"
```

## 2. ベースライン
```bash
"$ADB" -s $IP:5555 shell settings get global airplane_mode_on   # 0 のはず
"$ADB" -s $IP:5555 shell ip -f inet addr show wlan0 | grep inet # 現IP（rung2後に張り直るか比較用）
```

## 3. rung1 テスト（軽い・特権不要・Wi-Fiは切れない）
```bash
"$ADB" -s $IP:5555 logcat -c
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell am broadcast -n com.kimiterrace.tvbridge/.PlugCommandReceiver -a com.kimiterrace.tvbridge.NET_REVALIDATE
"$ADB" -s $IP:5555 logcat -d | grep -iE "NetworkCycle|PlugCmd"
#   期待: "revalidate: reportNetworkConnectivity(false) -> ..." が出る（OSが再検証を再実行）
#   ※rung1は検証の再実行のみ。L3が完全死だと戻らない事もある（その時 rung2 へ）。
```

## 4. rung2 テスト（機内モード＝本命。Wi-Fiが~8秒切れる）
```bash
"$ADB" -s $IP:5555 logcat -c
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell am broadcast -n com.kimiterrace.tvbridge/.PlugCommandReceiver -a com.kimiterrace.tvbridge.NET_CYCLE
#   この直後 adb が一瞬切れる想定（=ラジオが実際に落ちた＝機構が効いた証拠）。10〜15秒待つ。
"$ADB" connect $IP:5555
"$ADB" -s $IP:5555 logcat -d | grep -iE "NetworkCycle"
#   期待: "airplane ON (radios cycling) -> OFF in 8000ms" → "airplane OFF (radios re-associating)"
"$ADB" -s $IP:5555 shell settings get global airplane_mode_on   # 0 に戻っているはず
```

## 4.5 案B テスト（wifi_on トグル＝Wi-Fi だけ・~8秒切れる）
```bash
"$ADB" -s $IP:5555 logcat -c
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell am broadcast -n com.kimiterrace.tvbridge/.PlugCommandReceiver -a com.kimiterrace.tvbridge.NET_WIFI_TOGGLE
#   直後 adb が一瞬切れて戻れば = wifi_on 書込みでWi-Fiが落ちた（=このOEMはwifi_onを尊重）。10〜15秒待つ。
"$ADB" connect $IP:5555
"$ADB" -s $IP:5555 logcat -d | grep -iE "NetworkCycle"
#   期待: "wifi_on=0 ... -> on in 8000ms" → "wifi_on=1 (Wi-Fi re-enabling)"
"$ADB" -s $IP:5555 shell settings get global wifi_on   # 1 に戻っているはず
```
> 案A(機内モード)と案B(wifi_on)は**どちらがこのHKC箱で実際にWi-Fiを落とすか**を比較する。BTを巻き込まない
> 案Bが効くならそちらが上品。両方効かなければ案D(IR)。

## 4.8 プログラム再起動（DO機）テスト ＋ 朝の予防reboot有効化
> Device Owner 機なら**プログラムから graceful reboot 可能**（電源を切らないので standby問題なし＝
> プラグ通電復帰と違い必ず表示に戻る）。「reboot で夜間のL3セッション死をまっさらに取り直す」最も確実な手。
```bash
# 0) この端末が Device Owner か（"owner: ... DeviceOwner" と出ればOK。3年は確認済、1年/2年は要確認）
"$ADB" -s $IP:5555 shell dpm list-owners
# 1) その場で再起動テスト（DO機のみ実行。~1〜2分で起動し SignageActivity 表示＆v2🟢 に戻るか確認）
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell am broadcast -n com.kimiterrace.tvbridge/.PlugCommandReceiver -a com.kimiterrace.tvbridge.REBOOT_NOW
#   → 再起動後 net-adb が落ちてたら再 `adb connect`。戻れば「graceful reboot は standby に落ちず復帰」が実証。
# 2) 朝の予防reboot を有効化（DO機・毎朝 05:00 に自動 graceful reboot。既定OFF=opt-in）
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell am broadcast -n com.kimiterrace.tvbridge/.PlugCommandReceiver -a com.kimiterrace.tvbridge.SET_MORNING_REBOOT --es on 1
#   無効化は --es on 0。ON の DO機は朝 Wi-Fi トグルでなく reboot でL3を取り直す（最も確実）。
```
> ⚠ REBOOT_NOW は端末を再起動するので**夜間OFF（黒画面）時に**。非DO機では no-op（ログのみ）。
> ※ 反応的にも Watchdog が「poll不達30分→DO機 reboot / 非DO機 airplane」を自動実行する（今朝の1年型の途絶を新ビルドが自動復旧）。

## 5. 判定ツリー（これで結論）
| 観測 | 結論 | 次 |
|---|---|---|
| **案A(NET_CYCLE)でadbが一瞬切れて戻る** | 機内モードでラジオ張り直し可（全ラジオ・非DO成立） | WARMUP自動化済。横展開へ |
| **案B(NET_WIFI_TOGGLE)でadbが一瞬切れて戻る** | wifi_onでWi-Fiだけ張り直し可（BT巻き込まず＝上品・推奨） | WARMUP/Watchdogを案Bに切替えて横展開 |
| 案A/案B とも adb 切れない（ログは出るが無反応） | このOEMは設定書込みでラジオ非反応 | 案D IR(SwitchBot Hub Mini) |
| rung1(NET_REVALIDATE)だけで last_seen 復活 | 検証固着型だった | rung1で十分（軽い） |
| airplane=1 / wifi_on=0 が戻らない | in-app復帰失敗 | 15分でWatchdog強制復帰／即: `settings put global airplane_mode_on 0`・`settings put global wifi_on 1` |

## 6. 自動化（毎朝 05:00 の定時復帰）＋ 効いた手の採用
- **毎朝 05:00**（表示スケジュールと独立・休日も・boot跨ぎで再武装）に自動ネット復帰:
  - DO機 + `morning_reboot` ON → **graceful reboot**（最も確実）
  - それ以外 → **rung1 再検証 ＋ Wi-Fi トグル**（既定=案A機内モード。`recovery_use_wifi` ON で案B `wifi_on`）
- 今夕のテストで**効いた手を各機に設定**:
```bash
# DO機: 5:00 に reboot させる（最も確実）
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell am broadcast -n com.kimiterrace.tvbridge/.PlugCommandReceiver -a com.kimiterrace.tvbridge.SET_MORNING_REBOOT --es on 1
# 非DO機で案B(wifi_on)が効いたなら 5:00復帰を案Bへ切替（既定は案A機内モード）
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell am broadcast -n com.kimiterrace.tvbridge/.PlugCommandReceiver -a com.kimiterrace.tvbridge.SET_RECOVERY_WIFI --es on 1
# 5:00復帰そのものを止める時
MSYS_NO_PATHCONV=1 "$ADB" -s $IP:5555 shell am broadcast -n com.kimiterrace.tvbridge/.PlugCommandReceiver -a com.kimiterrace.tvbridge.SET_DAILY_RECOVERY --es on 0
```
- 確認: 翌朝 `logcat | grep -iE "RECOVERY|NetworkCycle"` に "5am recovery ..." の発火。
- 日中の poll 不達も Watchdog が rung1(10分)→サービス再起動(20分)→30分(DO reboot/非DO airplane)で自動回復。

## 7. 後始末／横展開
- 効いたら他機にも `install -r` ＋ 各機で `pm grant WRITE_SECURE_SETTINGS`（rung2に必須）。
- ソースは未コミット（branch `feat/tv-resilience-hardening`）。確定後にコミット。
- 参照: [APK-MANIFEST.md](APK-MANIFEST.md)（台帳に本ビルド追記すること）。
