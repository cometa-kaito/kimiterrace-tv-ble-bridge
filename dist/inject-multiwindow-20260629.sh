#!/usr/bin/env bash
# 複数消灯時間帯 APK 現地注入ヘルパ（2026-06-29）
# 使い方（Git Bash）:  bash inject-multiwindow-20260629.sh <テザリングIP> <学年 1|2|3>
#   例:  bash inject-multiwindow-20260629.sh 10.248.149.28 2
# 身元(model+DeviceOwner)を確認し、一致した時だけ install -r する。取り違え防止。
# 端末は事前にテザリング(Pixelホットスポット)へ。gifu-edu 中は adb 不可。

ADB="/c/Users/20051/platform-tools/adb.exe"
APK="/c/Users/20051/Desktop/app/tv-ble-bridge/dist/v2-build/tv-ble-bridge-multi-offwindow-20260629.apk"
EXPECT_SHA="b474c74ed79d9ae45c123752780d7df9a26f1afc31fa52a8ea644e514e9c99d4"
PKG="com.kimiterrace.tvbridge"

IP="$1"; GRADE="$2"
if [ -z "$IP" ] || [ -z "$GRADE" ]; then
  echo "usage: bash inject-multiwindow-20260629.sh <テザリングIP> <学年 1|2|3>"; exit 2
fi

case "$GRADE" in
  1) EXPECT_MODEL="";      EXPECT_DO="no";  LABEL="1年 (AI PONT/非DO)";;
  2) EXPECT_MODEL="ASTEX"; EXPECT_DO="no";  LABEL="2年 (ASTEX/非DO)";;
  3) EXPECT_MODEL="4K SA"; EXPECT_DO="yes"; LABEL="3年 (HKC/DeviceOwner)";;
  *) echo "学年は 1|2|3"; exit 2;;
esac

# 0) APK 健全性
if [ ! -f "$APK" ]; then echo "❌ APK が無い: $APK"; exit 1; fi
GOT_SHA=$(sha256sum "$APK" | awk '{print $1}')
if [ "$GOT_SHA" != "$EXPECT_SHA" ]; then echo "❌ APK sha 不一致: $GOT_SHA"; exit 1; fi
echo "APK ok ($GOT_SHA)"

# 1) 接続
echo "=== [$LABEL] connect $IP:5555 ==="
"$ADB" connect "$IP:5555" >/dev/null 2>&1
if ! "$ADB" devices | grep -q "$IP:5555[[:space:]]*device"; then
  echo "❌ 未接続/未認可。テザリング接続とIPを確認して再実行。"; "$ADB" devices; exit 1
fi

# 2) 身元確認（取り違え防止）
MODEL=$(MSYS_NO_PATHCONV=1 "$ADB" -s "$IP:5555" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
OWNERS=$(MSYS_NO_PATHCONV=1 "$ADB" -s "$IP:5555" shell dpm list-owners 2>/dev/null | tr -d '\r')
IS_DO="no"; if echo "$OWNERS" | grep -qi "DeviceOwner"; then IS_DO="yes"; fi
echo "model = $MODEL"
echo "DeviceOwner = $IS_DO"

if [ -n "$EXPECT_MODEL" ]; then
  if ! echo "$MODEL" | grep -q "$EXPECT_MODEL"; then
    echo "❌ model 不一致: $LABEL は '*$EXPECT_MODEL*' のはず。中止。"; exit 3; fi
else
  # 1年: ASTEX/4K SA(=2年/3年) でないことを確認
  if echo "$MODEL" | grep -qE "ASTEX|4K SA"; then
    echo "❌ 1年のはずが 2年/3年 の model に見える ('$MODEL')。中止。"; exit 3; fi
fi
if [ "$IS_DO" != "$EXPECT_DO" ]; then
  echo "❌ DeviceOwner 不一致: 期待 $EXPECT_DO / 実際 $IS_DO。中止。"; exit 3; fi
echo "✅ 身元一致 → 注入する: $LABEL"

# 3) install -r（prefs/device_id 維持・署名0951一致）
echo "=== install -r ==="
MSYS_NO_PATHCONV=1 "$ADB" -s "$IP:5555" install -r "$APK"

# 4) 検証
echo "=== 検証 ==="
echo -n "versionName : "; MSYS_NO_PATHCONV=1 "$ADB" -s "$IP:5555" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | tr -d '\r '
echo -n "http_proxy  : "; MSYS_NO_PATHCONV=1 "$ADB" -s "$IP:5555" shell settings get global http_proxy | tr -d '\r'
echo -n "a11y        : "; A=$(MSYS_NO_PATHCONV=1 "$ADB" -s "$IP:5555" shell settings get secure accessibility_enabled | tr -d '\r'); echo "$A"
# 3年は install で a11y 復活しうる→0へ戻す
if [ "$GRADE" = "3" ] && [ "$A" != "0" ]; then
  echo "  (3年: a11y=$A → 0 に再投入)"
  MSYS_NO_PATHCONV=1 "$ADB" -s "$IP:5555" shell settings put secure accessibility_enabled 0
fi
echo "--- 起動ログ（Watchdog armed / 盤面ロードが出れば稼働）---"
MSYS_NO_PATHCONV=1 "$ADB" -s "$IP:5555" logcat -d -v time 2>/dev/null | grep -iE "Watchdog|ConfigPoller|SignageActivity|lock task" | tail -8

echo ""
echo "=== [$LABEL] 完了。端末を gifu-edu に戻してください（adb は切れます=正常）==="
echo "  複数消灯の窓は gifu-edu 復帰後の poll で v2 から受信されます。"
