# 実装メモ(判断とその理由)

コードを変更する前に知っておくべきこと。詳しい仕様は `../DESIGN.md`。

## Dropbox API

- **サムネイル系 API は SDK を使わない。** `get_thumbnail_batch` と `get_thumbnail_v2` は `dropbox/ThumbnailApi.java` で HTTP を直接送る。SDK 7.1.0 が送る `quality` キーを API が拒否するため(debug.md D3)。他の API(`list_folder`、`get_metadata`、`download`、`get_temporary_link`)は SDK を使う
- **撮影日時の取得は `get_thumbnail_batch` を流用している。** `list_folder` の `include_media_info` は 2019年に廃止され、一覧では撮影日時が取れない。`get_thumbnail_batch` は各ファイルのメタデータ(media_info)も返すので、32×32 のサムネイルを要求して撮影日時だけ使う(25件/回)。バッチ非対応の拡張子(HEIC・動画など)や 20MB 超は `get_metadata(include_media_info=true)` で1件ずつ(4並列)
- `media_info` が `pending` のものは更新日時で仮置きし、次回同期で取り直す
- ファイルは Dropbox のファイルID(`id:...`)で扱う。移動してもIDは変わらない。API のパス引数にもIDを渡している
- 認証は OAuth2 PKCE(App secret 不要)。リフレッシュトークンを SharedPreferences に保存し、`DbxCredential` を SDK とサムネイル API で共有している

## 同期(SyncManager)

- 専用スレッドで直列実行。実行中に要求が来たら、終わった後にもう一度実行する(`rerun`)
- 対象フォルダかサブフォルダ設定が変わったら、ローカルDBとカーソルを捨てて全件取得
- **全件取得は全ページそろってから一度に反映**(debug.md D7)。差分取得はページごと
- 撮影日時は新しい順に取得。最新100件まではくるくる+進捗バーを出し、そこで「完了」扱い。残りは裏で続ける(新しい同期要求が来たら中断)
- 状態は `SyncManager.State`(running=くるくる表示中、background=裏で取得中、progress/max=進捗バー)

## サムネイル(ThumbnailLoader)

- メモリ(LruCache)→ ディスク → ネットワークの順。キャッシュのキーは `id@rev` なので、ファイルが更新されると自動で取り直す
- 表示中セルの要求を最優先(後入れ先出し、画面外に出たら破棄)。その合間に最新100件を先読み
- ネットワーク取得は2スレッド。最大40ms待って最大25件をまとめて1回で取る
- ディスパッチスレッドは `Throwable` を捕捉する(止まると以後サムネイルが一切取れなくなるため)

## グリッド(MainActivity / GalleryViewModel)

- DB の変化(同期中に頻繁に起きる)は 300ms まとめてからリストを作り直す
- 年・月・日の切り替え時だけ DiffUtil を使わずリストを入れ替える(debug.md D6)
- 列数(ピンチ)と年・月・日(メニュー)は独立。列数は `Prefs.columns`(3〜8、初期4)、年・月・日は `Prefs.group_mode`(初期値は月)
- 先頭を見ている間は、同期で並びが変わっても先頭に留まる

## 画面全般

- targetSdk 36 では常に全画面(edge-to-edge)表示になるため、各 Activity で `EdgeToEdge.enable` を呼び、レイアウトのルートに `android:fitsSystemWindows="true"` を付けて余白を確保している
