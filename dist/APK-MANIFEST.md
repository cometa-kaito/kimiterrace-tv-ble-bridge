# tv-ble-bridge 注入プログラム 集約台帳（APK インベントリ）

最終更新: 2026-06-17

モニタへ注入する Android アプリ `com.kimiterrace.tvbridge` の **ソースとビルド成果物の所在を一元管理**する台帳。
散乱した APK のどれが何のビルドかを sha256 で固定し、現場に入っているものを追跡する。

---

## 正本ソース（唯一・散乱なし）

- リポジトリ: `学校DX事業/03_PoC実施/実証実験/03_ハードウェア/tv-ble-bridge`（git 管理）
- HEAD: `8de64d6`（`git log` で確認）
- **ソースのコピーはこの1本のみ**（Desktop/Downloads/_archive/キミテラス-v2 を走査して確認済み 2026-06-17）。

> ⚠ 2026-06-17 時点で **未コミット・未ビルドの端末側レジリエンス全面強化**あり（「一過性の事象で端末を永久死させない」）。
> 過去の「NULL クラッシュ対策」は v2 **サーバ側**（lp-compat null→""、PR #855）のみで、**端末コードは未修正だった**。
> 以下が初の端末側対策。**次ビルドに必ず全部含めること**:
> 1. `BleService.startScan` / `Config.targetMac` — 無効 MAC（"NULL"/空/不正）で起動時クラッシュしない fail-safe。
> 2. `BleService.onCreate` — ConfigPoller を最優先起動＋全初期化を独立 try 化（周辺初期化の例外で生命線を巻き込まない）。
> 3. `Watchdog.kt`（新規）— AlarmManager 15分間隔の常駐ウォッチドッグ＋クラッシュ後の自動再起動アラーム。
> 4. `TvBridgeApp.kt`（新規・Application）— 全スレッド未捕捉例外フックで「落ちる直前に蘇生アラーム」＝あらゆるクラッシュを自動復帰に縮退。
> 5. `BootReceiver` — 起動処理を try 化＋ウォッチドッグ武装＋`MY_PACKAGE_REPLACED`（更新直後の再武装）。
>
> ビルド前提: 本 repo に gradle wrapper(jar/スクリプト)が無い。Android Studio でビルドするか wrapper を復元して `gradlew assembleDebug`。既定 `java` は 8 なので JDK 17/21 を使うこと。

---

## ビルド済み APK（distinct = 5 種）— 2026-06-17 集約済み

**正（versioned・`dist/v2-build/`）** — 運用で使うのはこれだけ:

| sha256(先頭16) | size(byte) | build日 | ファイル | 状態 |
|---|---|---|---|---|
| `ab4a01db5a37b2a2` | 7,304,030 | 2026-06-15 | `dist/v2-build/tv-ble-bridge-night-off-20260615.apk` | **最新・現場に入っている想定** |
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
