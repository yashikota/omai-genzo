# おまいGENZO!

写真選別＆LibRaw現像エンジン搭載Androidアプリケーション

## 性能ログ

アプリ起動中は全性能イベントをJSONLとLogcatへ記録します。端末内の保存先は開始画面にも表示されます。

```text
/sdcard/Android/data/com.yashikota.omaigenzo/files/perf/omai-perf.jsonl
/sdcard/Android/data/com.yashikota.omaigenzo/files/perf/omai-perf.1.jsonl
```

```shell
adb logcat -s OmaiPerf:D
adb pull /sdcard/Android/data/com.yashikota.omaigenzo/files/perf/omai-perf.jsonl
```

ログにはファイル名、content URI、操作、端末情報、デコード方式・時間、画像サイズ、キャッシュ、メモリ、PSS、CPU時間、温度状態、バッテリー残量・電流値、例外スタックが含まれます。64 MiBでローテーションし、現行と直前の最大128 MiBを保持します。外部サーバーへの送信は行いません。

## ベンチマーク

速度が最優先なので、変更の前後は実機で数値を取って比べます。JVM テストは「ホットパスのアルゴリズムが遅くなっていないか」を守るだけで、端末上の体感速度は保証しません。

| 何を見るか | 手段 |
|---|---|
| アルゴリズムの退行（キャッシュ、先読み計画、選択保存） | `task test`（`HotPathBenchmarkTest` など） |
| 純 C++ 部分の正しさ（JPEG 寸法検出、fd マップ） | `task test:native`（ASan/UBSan 付き） |
| 実機のデコード・スワイプ遅延 | `task bench`（`PreviewDecodeBenchmark`） |
| 実利用ログの集計・前後比較 | `task bench:report -- before.jsonl after.jsonl` |

### 実機ベンチ

`benchmark` ビルドは release と同じ R8・最適化済みネイティブを debug 署名で作ります（profileable）。debug ビルドは Compose が遅いので計測に使わないでください。

```shell
task build:benchmark
adb install -r app/build/outputs/apk/benchmark/app-benchmark.apk
adb install -r app/build/outputs/apk/androidTest/benchmark/app-benchmark-androidTest.apk
adb push ./sample-photos/. /sdcard/Android/data/com.yashikota.omaigenzo/files/bench/   # RAW / RAW+JPEG / JPEG を4組以上
adb shell am instrument -w -e class com.yashikota.omaigenzo.PreviewDecodeBenchmark \
    -e thinkMs 300 -e rounds 3 \
    com.yashikota.omaigenzo.test/androidx.test.runner.AndroidJUnitRunner
```

- `cold_preview_2048` / `cold_thumbnail_512`: キャッシュ無しのデコード時間（p50 / p95 / max）
- `cache_hit_peek`: キャッシュヒットの取得時間
- `swipe_next_with_prefetch` / `swipe_next_without_prefetch`: スワイプしてから次の写真が使えるまで。`thinkMs` は 1 枚を見ている時間で、`0` にすると最速で連打した最悪ケースになる。`prefetch_hit_ratio` は次の写真が先読み済みだった割合

結果は `files/perf/bench-results.jsonl` と OmaiPerf ログ（`bench_result`）にも入ります。端末が熱を持つと数値が変わるため、`thermal` イベントと一緒に比べてください。

### 変更前後の比較

```shell
adb pull /sdcard/Android/data/com.yashikota.omaigenzo/files/perf/omai-perf.jsonl after.jsonl
python3 tools/perf_report.py before.jsonl after.jsonl
```

デコード時間、表示までの時間、キャッシュヒット率、デコード経路の内訳、PSS・ネイティブヒープ、熱状態をまとめて出します。
