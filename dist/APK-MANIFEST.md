# tv-ble-bridge 注入プログラム 集約台帳（APK インベントリ）

最終更新: 2026-06-17

モニタへ注入する Android アプリ `com.kimiterrace.tvbridge` の **ソースとビルド成果物の所在を一元管理**する台帳。
散乱した APK のどれが何のビルドかを sha256 で固定し、現場に入っているものを追跡する。

---

## 正本ソース（唯一・散乱なし）

- リポジトリ: `学校DX事業/03_PoC実施/実証実験/03_ハードウェア/tv-ble-bridge`（git 管理）
- HEAD: `8de64d6`（`git log` で確認）
- **ソースのコピーはこの1本のみ**（Desktop/Downloads/_archive/キミテラス-v2 を走査して確認済み 2026-06-17）。

> ✅ 2026-06-17 **端末側レジリエンス全面強化を実装・コミット・ビルド済**（branch `feat/tv-resilience-hardening`）。
> 過去の「NULL クラッシュ対策」は v2 **サーバ側**（lp-compat null→""、PR #855）のみで、**端末コードは未修正だった**＝再起動で再発し得た。本変更が初の端末側対策。
> 「一過性の事象で端末を永久死させない」ための内容:
> 1. 無効 MAC fail-safe（`BleService.startScan` ガード＋`Config.targetMac` 正規化）。
> 2. `BleService.onCreate` を ConfigPoller 最優先＋全初期化 独立 try 化（周辺初期化の例外で生命線を巻き込まない）。
> 3. **poll liveness 自己診断**：成功時刻を記録し、Watchdog が「20分無成功＝poller 停止」を検知→サービス強制再生成（`Config`/`ConfigPoller`/`Watchdog`/`BleService.forceRestart`）。
> 4. `Watchdog.kt`（新規）AlarmManager 15分毎 + クラッシュ後 即時再起動アラーム。
> 5. `KeepAliveWorker.kt`（新規・WorkManager）AlarmManager と独立した第2経路。
> 6. `TvBridgeApp.kt`（新規 Application）全未捕捉例外→蘇生アラームで自動復帰に縮退。
> 7. ConfigPoller ループ堅牢化（例外で止めない/Cancellation 再送出）＋OkHttp `callTimeout`＋`days_mask=0` 誤config 弾き。
> 8. `BootReceiver`/Manifest 堅牢化＋`MY_PACKAGE_REPLACED` 再武装。`snapshotStatus`/WebView `onRenderProcessGone` のクラッシュ穴封鎖。
>
> **端末側で原理的に塞げない残件**＝stopped-state（force-stop/初回未起動）。OS が boot/alarm/FCM/Work を全ブロック→ Device Owner 化＋サーバ死活監視＋現地リブートで担保（運用側）。
>
> ビルド方法（実績）: 本 repo に gradle wrapper(jar/スクリプト)が無いので、キャッシュの gradle を直接利用:
> `JAVA_HOME=<JDK21> <…>/gradle-8.9/bin/gradle -p <repo> -Pandroid.overridePathCheck=true assembleDebug`
> （非ASCIIパス override は `gradle.properties` にも恒久追記済。既定 `java` は 8 なので JDK 17/21 必須。Android Studio でも可。）

---

## ビルド済み APK（distinct = 5 種）— 2026-06-17 集約済み

**正（versioned・`dist/v2-build/`）** — 運用で使うのはこれだけ:

| sha256(先頭16) | size(byte) | build日 | ファイル | 状態 |
|---|---|---|---|---|
| `48ac8ebf1b509b63` | 7,570,934 | 2026-06-17 | `dist/v2-build/tv-ble-bridge-resilience-20260617.apk` | **最新・全面堅牢化版（次に現地 flash 推奨）。未配布** |
| `ab4a01db5a37b2a2` | 7,304,030 | 2026-06-15 | `dist/v2-build/tv-ble-bridge-night-off-20260615.apk` | 前版（現場に入っている想定） |
| `64e431ccbda9fbe7` | 7,181,874 | 2026-06-11 | `dist/v2-build/tv-ble-bridge-fcm-20260611.apk` | 旧（FCM 遠隔起動 導入版） |
| `e50aba66eb0beed6` | 6,454,118 | 2026-06-08 | `dist/v2-build/tv-ble-bridge-v2migration-20260608.apk` | 旧（v2 移行 初版。旧名 `tv-ble-bridge-debug.apk` を版数名へ改名） |

**退避（`dist/_archive-prebuilt/`）** — 参照用・運用では使わない:

| sha256(先頭16) | size(byte) | build日 | ファイル | 由来 |
|---|---|---|---|---|
| `97f94b8e6b7f82d8` | 6,422,108 | 2026-05-30 | `_archive-prebuilt/tv-ble-bridge-prebuilt-20260530-a-97f94b8e.apk` | 旧 `Downloads/tv-ble-bridge-debug/`（v2 前・陳腐） |
| `f2121433ab3d719e` | 6,433,316 | 2026-05-30 | `_archive-prebuilt/tv-ble-bridge-prebuilt-20260530-b-f2121433.apk` | 旧 `Downloads/...-v2/`（v2 前・陳腐） |
| `7e31fb281d5586c5` | 6,433,356 | 2026-05-30 | `_archive-prebuilt/tv-ble-bridge-prebuilt-20260530-c-7e31fb28.apk` | 旧 `Downloads/...-v3/`（v2 前・陳腐） |
| `ab4a01db5a37b2a2` | 7,304,030 | 2026-06-15 | `_archive-prebuilt/DUPLICATE-of-night-off-20260615-ab4a01db.apk` | 旧 `dist/tv-ble-bridge-debug.apk`（最新の無版数重複） |

### 集約の記録（2026-06-17 実施）
- `Downloads/tv-ble-bridge-debug{,-v2,-v3}/` に散在していた v2 移行前 APK 3 つ → `dist/_archive-prebuilt/` へ**移動**（削除なし。元フォルダは空のまま残置）。
- 無版数 `dist/tv-ble-bridge-debug.apk`（最新と同一バイト）→ `_archive-prebuilt/` へ退避。
- `dist/v2-build/tv-ble-bridge-debug.apk`（06-08 の別ビルド）→ `tv-ble-bridge-v2migration-20260608.apk` に改名。
- `app/build/outputs/apk/debug/app-debug.apk` は Gradle 出力（ビルド毎に再生成）＝管理対象外。
- APK は `.gitignore` 対象（git 追跡外）。追跡されるのは本台帳のみ。

---

## 今後の運用ルール

1. リリース APK は `dist/v2-build/` に `tv-ble-bridge-<feature>-<YYYYMMDD>.apk` の名前で置く。
2. 無版数の `tv-ble-bridge-debug.apk` は作らない（どのビルドか追えなくなる）。
3. 端末へ流し込んだら、本台帳に「どの端末（device_id / 教室）に・いつ・どのハッシュを入れたか」を追記する。
4. ソースは上記 git 1 本のみ。別の場所にコピーを作らない。
