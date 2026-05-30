# Google TV への sideload 手順

このドキュメントは TV BLE Bridge を Google TV にインストールするための手順書。
adb 経験ありを前提に書かれているが、各ステップで具体的なコマンドを示す。

## 0. 必要なもの

- **PC**（Windows 10/11、Mac、Linux いずれも可）
- **adb**（Android SDK Platform Tools。`scoop install adb` or [公式DL](https://developer.android.com/tools/releases/platform-tools)）
- **APK ファイル**（GitHub Actions Artifacts から DL 済）
- **Google TV** 本体とリモコン
- **PC と Google TV が同じ Wi-Fi に繋がっていること**（学校でセットアップする場合は同じ学校ネットワーク）

## 1. APK の入手

GitHub Actions の成果物から DL：

1. ブラウザで https://github.com/cometa-kaito/kimiterrace-tv-ble-bridge/actions を開く
2. 最新の成功 run（緑チェック）をクリック
3. ページ下部 **Artifacts** から **`tv-ble-bridge-debug-apk`** をクリック → ZIP DL
4. ZIP を展開すると `tv-ble-bridge-debug.apk`（〜5MB）

> ⚠️ release ビルドは debug 署名で署名しているため、通常運用には debug 版で十分。

## 2. Google TV の開発者モード有効化

1. 設定（歯車アイコン）
2. **システム** → **デバイス情報**
3. **Android TV OS ビルド**（or「ビルド番号」）を **7回連続タップ**
4. 「あなたは開発者になりました」と表示

## 3. ネットワークデバッグ有効化

1. 設定 → **システム** → **開発者向けオプション**（新たに出現）
2. **USB デバッグ** → ON
3. **ネットワークデバッグ** → ON
4. 画面に **IP アドレス：ポート**（例 `192.168.1.50:5555`）が表示されるのでメモ
   - 表示されない場合：設定 → **ネットワークとインターネット** → 接続中のネットワーク → IP アドレス確認、ポートは `5555` 固定

## 4. PC から adb 接続

PC のターミナル（PowerShell, cmd, Terminal）で：

```bash
adb connect 192.168.1.50:5555
```

→ Google TV の画面に **「USB デバッグを許可しますか？」** ダイアログ
→ **「常に許可」にチェック** → **OK**

```bash
adb devices
```

→ `192.168.1.50:5555    device` と表示されれば接続成功。

> ⚠️ `unauthorized` のままなら Google TV 側で許可ダイアログを見落としている。
> Google TV のリモコンで OK を押すまで先へ進めない。

## 5. APK インストール

```bash
adb install tv-ble-bridge-debug.apk
```

→ `Success` が出ればインストール完了。

> ⚠️ `INSTALL_FAILED_USER_RESTRICTED` が出る場合は、Google TV 設定 →
> **セキュリティとプライバシー** → **不明アプリ** で adb 経由のインストールを許可する。

## 6. アプリ起動と初期設定

1. Google TV のホーム → **アプリ一覧**（下のドロワーや「アプリ」アイコンから）
2. **「キミテラス TV ブリッジ」** を探す
   - 見つからない場合：ホーム画面下部の「すべてのアプリを見る」「ライブラリ」など
3. 起動 → 設定画面が出る

### 設定する2項目

| 項目 | 値 |
|---|---|
| センサーMAC | `DC:A5:B3:C2:98:D7`（既定値、自動入力済） |
| Webhook URL | `https://www.school-signage.net/api/switchbot-webhook?key=<SECRET>` |

> ⚠️ Webhook URL は **Vercel に登録した SWITCHBOT_WEBHOOK_SECRET を `key=` に貼る**。
> 不明なら `06_LP/edix-lp/docs/POC_OPERATIONS.md` を参照。
> リモコンでの URL 入力は手間なので、PC から adb 経由で SharedPreferences に直接書き込むことも可（下記「6.5」参照）。

### 6.5（オプション）adb で Webhook URL を流し込む

リモコン入力が面倒な場合、PC から：

```powershell
adb shell "am start -n com.kimiterrace.tvbridge/.MainActivity"
# キーボード入力で URL 直入力
adb shell input text "https://www.school-signage.net/api/switchbot-webhook?key=YOUR_SECRET"
```

> 注：`?` や `&` はエスケープが必要なので、最終的にはリモコン編集が一番堅い。

## 7. 権限付与とサービス開始

1. **▶ 開始 / 再起動** ボタンを **D-Pad で選択して OK**
2. 権限ダイアログが連続で出る：
   - **Bluetooth を使用** → 許可
   - **付近のデバイスを検索** → 許可
   - **通知を表示** → 許可
3. ステータス欄が **「状態: scanning」** になれば受信開始

## 8. 動作確認

センサの前で **手を振る**：

- **最終検知** 欄が更新される（数秒以内）
- **累計** カウンタがインクリメント
- **最終同期: HH:MM:SS (OK)** と表示される（同期成功）

PC のブラウザで：
```
https://www.school-signage.net/api/sensor-stats?key=YOUR_SECRET&hours=1
```
→ `totalEvents` が増えていれば end-to-end 成功。

## 9. 24時間運用化

### TVBro と共存

このアプリはバックグラウンドで Foreground Service として動作するため、
**TVBro が前面で動いていてもセンサ受信は継続** する。

ホームに戻って **TVBro を起動** → 通常通りサイネージを表示し続ける。

### 端末再起動時の自動再開

`BootReceiver` で自動再開する設計。ただし Google TV の機種・OEM により
バックグラウンドアプリの起動が制限される場合がある：

- 設定 → アプリ → キミテラス TV ブリッジ → **電池の最適化を無効化**（ある場合）
- 設定 → アプリ → キミテラス TV ブリッジ → **自動起動を許可**

### スリープ防止

サイネージ運用なら TV 側で **画面オフを無効化** しているはずだが、念のため：
- 設定 → 端末設定 → ディスプレイ → スリープ → **「使用しない」or「決して」**

## 10. トラブルシュート

### アプリが落ちる

`adb logcat | grep TVBleBridge` でログ確認。よくあるパターン：
- 権限未付与 → 設定 → アプリ → 権限を全部 ON
- BLE オフ → 設定 → リモコンとアクセサリ → Bluetooth → ON

### 検知はされるが Vercel に届かない

- ステータスが **「最終同期: 失敗→蓄積中」** ならネットワーク or URL 問題
- ローカル DB には残っているので、復旧後に自動再送される
- `adb shell` で `/data/data/com.kimiterrace.tvbridge/databases/sensor.db` を pull して中身確認可

### 端末再起動後にサービスが起動しない

- アプリを **一度開いて ▶ 開始ボタンを押す** と復活
- 学校設置前に「TV を電源 OFF → ON → 確認」を最低 1 回テストする

## 11. アップデート手順

新しい APK ができたら：

```bash
adb connect 192.168.1.50:5555
adb install -r tv-ble-bridge-debug.apk
```

`-r` で既存アプリを置き換え、設定値は保持される。

## 12. アンインストール

```bash
adb uninstall com.kimiterrace.tvbridge
```

または Google TV 設定 → アプリ → キミテラス TV ブリッジ → アンインストール。
