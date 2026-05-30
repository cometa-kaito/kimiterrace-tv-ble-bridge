@echo off
REM ============================================================
REM Google TV に tv-ble-bridge-debug.apk を 1 コマンドでインストール
REM ============================================================
REM
REM 使い方:
REM   1. このファイルをダブルクリック、または
REM      cmd で .\install-to-googletv.bat を実行
REM   2. Google TV の IP アドレスを入力（例: 192.168.1.50）
REM   3. Google TV 画面で「USB デバッグを許可」ダイアログが出たら OK
REM   4. 自動でインストールまで進む
REM
REM 事前準備:
REM   - Google TV 設定 → システム → デバイス情報
REM     → 「Android TV OS ビルド」を 7 回タップ → 開発者モード ON
REM   - 設定 → 開発者向けオプション
REM     → USB デバッグ ON、ネットワークデバッグ ON
REM   - Google TV を学校 Wi-Fi に接続済
REM   - この PC も同じ Wi-Fi に接続
REM ============================================================

setlocal EnableDelayedExpansion

set ADB="C:\Users\20051\platform-tools\adb.exe"
set APK="%~dp0tv-ble-bridge-debug.apk"

if not exist %APK% (
    echo ERROR: APK ファイルが見つかりません: %APK%
    pause
    exit /b 1
)

echo ============================================================
echo  キミテラス TV ブリッジ Google TV インストーラー
echo ============================================================
echo.

set /p TV_IP="Google TV の IP アドレスを入力: "

if "%TV_IP%"=="" (
    echo ERROR: IP アドレスが入力されませんでした
    pause
    exit /b 1
)

echo.
echo [1/4] 既存接続をクリーンアップ...
%ADB% disconnect >nul 2>&1

echo [2/4] Google TV に接続: %TV_IP%:5555 ...
%ADB% connect %TV_IP%:5555
if errorlevel 1 (
    echo ERROR: 接続失敗。IP アドレスと開発者モードを確認してください
    pause
    exit /b 1
)

echo.
echo  → Google TV の画面で「USB デバッグを許可」ダイアログが
echo    出ている場合は、リモコンで「常に許可」にチェック → OK を押してください
echo.
pause

echo [3/4] 接続確認 ...
%ADB% devices

echo.
echo [4/4] APK インストール（既存があれば置き換え） ...
%ADB% -s %TV_IP%:5555 install -r %APK%

if errorlevel 0 (
    echo.
    echo ============================================================
    echo  インストール成功！
    echo ============================================================
    echo.
    echo  次のステップ:
    echo  1. Google TV のアプリ一覧から「キミテラス TV ブリッジ」を起動
    echo  2. Webhook URL を入力
    echo  3. 「▶ 開始 / 再起動」を押して権限を全て許可
    echo  4. 状態が "scanning" になれば受信開始
    echo.
)

echo.
echo 切断 ...
%ADB% disconnect %TV_IP%:5555

echo.
pause
