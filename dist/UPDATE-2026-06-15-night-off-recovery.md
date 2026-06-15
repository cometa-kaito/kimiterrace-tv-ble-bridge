# 現地アップデート手順 — 夜間OFF復帰対応（2026-06-15）

対象ビルド: branch `feat/night-off-recovery`（夜間OFF擬似黒モード + ネット復帰トリガ）
APK: `dist/tv-ble-bridge-debug.apk`（署名 `0951ef53…` / 7.3MB / 2026-06-15）
これは **既プロビジョニング済み端末の上書き更新**（reinstall）手順。新規設置は `provision-googletv.md`。

---

## 0. 安全則（必読・最重要）

- **絶対に `adb uninstall` しない。** uninstall すると **Device Owner が消え、factory reset 無しでは復旧不能**になる。
- 今回APKの署名鍵 `0951ef53…` は **現行本番（FCM版 2026-06-11）と同じ**。よって 6/11 を入れた端末は `install -r` で更新できる。
- `install -r` は**署名が違えば安全に失敗**して既存アプリはそのまま残る（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`）。
  失敗したら **その端末は触らず**、機種・IP を記録して撤退（古い鍵の端末＝別対応）。**失敗しても uninstall しない。**
- **まず 1〜2 台**で実施し「当日夜→翌朝に復帰」を確認してから残りへ。全台一斉にしない。

---

## 1. PC 準備（持ち出し前）

- 新APK: `dist/tv-ble-bridge-debug.apk`（= `install-to-googletv.bat` がインストールする版）
- ロールバック用: `dist/v2-build/tv-ble-bridge-fcm-20260611.apk`（現行本番・同じ署名 `0951…`）
- adb: `C:\Users\20051\platform-tools\adb.exe`
- 署名鍵バックアップ（紛失厳禁）: `..\_signing-backup\kimiteras-tvbridge-debug-0951ef53-20260615.keystore`

---

## 2. 各端末の更新（`install-to-googletv.bat` でも可）

```powershell
$ADB="C:\Users\20051\platform-tools\adb.exe"
$IP="<端末のIP>"          # 例 192.168.1.50
& $ADB connect "$IP:5555"

# (任意) 現状把握
& $ADB -s "$IP:5555" shell dumpsys package com.kimiterrace.tvbridge | findstr versionName

# 上書き更新（※失敗しても uninstall しない）
& $ADB -s "$IP:5555" install -r "<repo>\dist\tv-ble-bridge-debug.apk"
#   Success                              → 次へ
#   Failure: SIGNATURE / UPDATE_INCOMPATIBLE → 署名不一致。記録して撤退（uninstall 禁止）

# 再起動
& $ADB -s "$IP:5555" shell am start -n com.kimiterrace.tvbridge/.MainActivity
```

### 新ビルドが入った確認（重要）
MainActivity に **「夜間OFFを擬似黒にする（復帰優先・推奨）」チェックボックス**が表示されていれば新版。
既定 **ON（= overlay モード）**。これが見えなければ旧版のまま。

### 動作確認
```powershell
& $ADB -s "$IP:5555" shell am start -a com.kimiterrace.tvbridge.OPEN_SIGNAGE   # サイネージ前面
```
- v2 で **接続🟢** / 状態 `scanning` を確認。
- **その場で擬似黒テスト**: MainActivity の「テスト黒画面」ボタンで黒画面化 →
  ① 暗室で十分黒いか ② 画面が消灯しない（KEEP_SCREEN_ON が効く）か → ホーム/解除で**即サイネージ復帰**を確認。

---

## 3. no-sleep の効きを確認（メーカー差の切り分け）

```powershell
& $ADB -s "$IP:5555" shell settings get system screen_off_timeout   # 期待 2147483647
& $ADB -s "$IP:5555" shell settings get secure sleep_timeout         # 期待 -1
```
- 効いていなくても **overlay 既定なので朝復帰する想定**。真の消灯にしたい復帰確認済み機のみ、MainActivity のチェックを **OFF（= lock）** に。
- **TV 本体の firmware オフ/スリープタイマー**（例: ORION AI PONT 16:30）は `settings` で消えない → **TV 設定 UI で必ず無効化**。

---

## 4. 翌朝の検証（本不具合の確認）

- 各端末がサイネージ表示に**戻っているか**確認。
- 戻らない端末: 機種 / IP / 挙動を記録 → ① night_off_mode が overlay か ② no-sleep 読み戻し値 ③ firmware オフタイマー無効化漏れ を確認。

---

## 5. ロールバック（必要時）

```powershell
& $ADB -s "$IP:5555" install -r "<repo>\dist\v2-build\tv-ble-bridge-fcm-20260611.apk"
```

---

## 6. 持ち帰り後 — 署名鍵の保全

署名鍵 `~/.android/debug.keystore`（`0951ef53…`）は **いまや現場全台の署名 ID**。これが消えると今後一切 `install -r` で更新できなくなる
（過去2週間で鍵が3回変わっている: `90e1`→`e0307e80`→`0951`）。**安全な場所（リポジトリ外・できればオフマシン）へ必ずバックアップ**しておくこと。
今回 `..\_signing-backup\` に1部コピー済み。
