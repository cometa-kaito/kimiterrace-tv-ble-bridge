# 配布物（dist/）

GitHub Actions でビルド済の APK と、Google TV へのインストール用バッチを置く場所。

## ファイル

| ファイル | 役割 |
|---|---|
| `tv-ble-bridge-debug.apk` | デバッグ署名済 APK（6.1MB）。Google TV にこれを入れる |
| `install-to-googletv.bat` | adb 経由でインストールする1コマンドスクリプト |

## ローカル環境（既にセットアップ済）

- **adb**: `C:\Users\20051\platform-tools\adb.exe`
- **APK**: `dist\tv-ble-bridge-debug.apk`
- **インストーラー**: `dist\install-to-googletv.bat`

## 学校での使い方（最短手順）

1. Google TV を **開発者モード**に：
   - 設定 → システム → デバイス情報 → 「Android TV OS ビルド」**7 回タップ**
   - 戻って 開発者向けオプション → **USB デバッグ ON**、**ネットワークデバッグ ON**
2. Google TV の **IP アドレスをメモ**（設定 → ネットワーク → 接続中の Wi-Fi）
3. **PC をその学校 Wi-Fi に同様に接続**
4. PC で **`install-to-googletv.bat` をダブルクリック**
5. IP を入力 → Google TV 画面で「USB デバッグを許可」OK → 自動インストール
6. Google TV のアプリ一覧から **「キミテラス TV ブリッジ」** を起動
7. **Webhook URL** を入力：
   ```
   https://www.school-signage.net/api/switchbot-webhook?key=<SWITCHBOT_WEBHOOK_SECRET>
   ```
8. **▶ 開始 / 再起動** → 権限を全て許可
9. 状態が **scanning** になれば完了。サイネージ（TVBro）に戻して常駐運用

## APK 更新時

新しい APK ができたら：
1. GitHub Actions Artifacts から DL → ZIP 展開 → このフォルダの `tv-ble-bridge-debug.apk` を上書き
2. `install-to-googletv.bat` を再実行（既存アプリを置き換え、設定は保持）

## 詳細手順

[SIDELOAD_GUIDE.md](../SIDELOAD_GUIDE.md) を参照。
