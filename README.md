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
