# DBXフォトビューア 設計書

Dropbox API を使った閲覧専用の Android 写真ビューア。画面は Dropbox 公式アプリの「写真」タブにほぼ準拠し、表示対象フォルダとサブフォルダ探索の有無を指定できる。

## 1. 目的とスコープ

| 項目 | 内容 |
|---|---|
| 目的 | 指定した Dropbox フォルダ内の写真・動画を、撮影日順のグリッドで閲覧する |
| 対象 | 自分用(個人の Dropbox アカウント)。Play ストア公開は想定しない |
| 閲覧専用 | アプリ権限を読み取りスコープのみに限定し、API レベルで書き込み・削除を不可能にする |
| 対象外(v1) | アップロード、削除、移動、共有リンク作成、スター付き絞り込み(API で扱えないため)、チームアカウント |

## 2. 動作環境・技術スタック

| 項目 | 内容 |
|---|---|
| 言語 | Java 11 |
| minSdk / targetSdk | 26 (Android 8.0) / 36 |
| ビルド | Android Gradle Plugin 8.13.2、Gradle 8.13、Groovy DSL + バージョンカタログ(libs.versions.toml) |
| Dropbox SDK | `com.dropbox.core:dropbox-core-sdk:7.1.0`、`com.dropbox.core:dropbox-android-sdk:7.1.0` |
| 主要ライブラリ | AndroidX(AppCompat, RecyclerView, ViewPager2, SwipeRefreshLayout, Preference, Lifecycle)、Room 2.6.1、Material Components、Subsampling Scale Image View(ズーム表示) |

SDK 8.x は Java 21 必須のため、Android との互換性を優先して 7.1.0(minSdk 26 対応)を採用する。targetSdk 36 では常に全画面(edge-to-edge)表示になるため、各画面で EdgeToEdge を有効にし、レイアウト側の fitsSystemWindows で余白を確保する。

## 3. Dropbox アプリ登録

1. https://www.dropbox.com/developers/apps で「Create app」
2. Scoped access / Full Dropbox を選択
3. Permissions タブで以下のみ有効化して Submit
   - `files.metadata.read`
   - `files.content.read`
4. App key を `local.properties` に `dropbox.appKey=xxxxxxxx` と記載(Git 管理外)
5. 認証は OAuth2 PKCE(App secret 不要)。リフレッシュトークンを保存し、アクセストークンは SDK が自動更新する

## 4. 画面仕様

### 4.1 画面一覧

| 画面 | クラス | 概要 |
|---|---|---|
| 写真グリッド(メイン) | `MainActivity` | 未ログイン時は接続ボタン、ログイン後は写真グリッド |
| ビューア | `ViewerActivity` | 1枚表示。左右スワイプで前後、ピンチズーム |
| 設定 | `SettingsActivity` | 対象フォルダ、サブフォルダ探索、除外フィルタ、キャッシュ削除、ログアウト |
| フォルダ選択 | `FolderPickerActivity` | Dropbox のフォルダ階層をたどって対象フォルダを選ぶ |

### 4.2 写真グリッド(公式「写真」タブ準拠)

- 撮影日時の新しい順に並べる。撮影日時が無いものは `client_modified`(ファイルの更新日時)で代替する
- 日付でグループ化し、グループごとに見出しと件数を出す(例:「2026年10月 11 件」)。単位は日・月・年の3段階で、ツールバーのメニューで切り替える(初期値は月)
- 1行に並べるサムネイル数(3〜8、初期値4)はピンチで変える。ピンチアウトで大きく、ピンチインで小さく。グループ化の単位は変わらない
- 動画はサムネイル右下に再生アイコンと再生時間を表示
- 表示種別フィルタ: すべて / 写真のみ / 動画のみ(メニュー)
- 下に引っぱって更新(SwipeRefreshLayout)。起動時にも差分同期する
- 高速スクロール用のスクロールつまみ(RecyclerView 標準の fastScroll)
- ツールバーのサブタイトルに対象フォルダと同期状況(「索引作成中 120/3400」など)を表示
- 写真タップでビューアを開く

### 4.3 ビューア

- ViewPager2 で、グリッドと同じ並び順のまま左右スワイプ
- 画像は Dropbox 側で 2048×1536 以内に縮小した JPEG を取得して表示(HEIC 等も JPEG で受け取れ、通信量も少ない)。縮小版が取れない形式は元ファイルをダウンロードして端末側でデコード
- ピンチズーム、ダブルタップズーム
- タップで情報バー(ファイル名、撮影日時、フォルダパス)の表示/非表示を切替
- 動画は再生ボタンを表示し、タップで一時リンク(4時間有効)を取得して端末の動画プレーヤーで再生

### 4.4 設定

| 項目 | 既定値 | 説明 |
|---|---|---|
| 対象フォルダ | `/`(ルート) | フォルダ選択画面で指定 |
| サブフォルダも探索 | ON | OFF なら指定フォルダの直下のみ |
| 小さい画像と PNG を除外 | ON | 公式と同じく 15KB 未満と .png を非表示。OFF で全部表示 |
| キャッシュを削除 | — | サムネイルとプレビューのキャッシュを消す |
| ログアウト | — | 保存した認証情報とローカルDBを消去 |

対象フォルダかサブフォルダ探索の設定を変えると、ローカルDBとカーソルを捨てて再同期する。

## 5. 対象ファイル

- 写真: jpg, jpeg, png, heic, heif, gif, webp, bmp, tif, tiff, dng
- 動画: mp4, mov, m4v, 3gp, mkv, webm, avi
- 「小さい画像と PNG を除外」が ON のとき、写真について 15KB 未満と png を除外する(DB には保持し、表示時のクエリで除外するので、切替に再同期は不要)

## 6. API の使い方

| 目的 | エンドポイント | 備考 |
|---|---|---|
| 一覧取得 | `files/list_folder` | `recursive` で探索有無を切替、`limit=2000` |
| 続き・差分取得 | `files/list_folder/continue` | 保存したカーソルで差分のみ取得。`reset` エラー時はフル再取得 |
| 撮影日時の取得(主) | `files/get_thumbnail_batch` | 25件/回。返却される `metadata.media_info.time_taken` を利用(32×32 の最小サムネで通信量を抑える) |
| 撮影日時の取得(従) | `files/get_metadata`(`include_media_info=true`) | バッチ非対応の形式(HEIC、動画など)や 20MB 超の画像用。1件/回 |
| グリッドのサムネイル | `files/get_thumbnail_batch` | 256×256、`fitone_bestfit`。表示中のセルを最大25件ずつまとめて取得 |
| ビューア表示用 | `files/get_thumbnail_v2` | 2048×1536、`bestfit`、JPEG |

※ `get_thumbnail_batch` と `get_thumbnail_v2` は SDK を使わず `ThumbnailApi`(HttpURLConnection)で直接呼ぶ。SDK 7.1.0 は API が受け付けない `quality` キーを送り、`unexpected key 'quality'` で失敗するため(debug.md 参照)。それ以外の API は SDK を使う。
| 元ファイル | `files/download` | 縮小版が取れない場合のみ |
| 動画再生 | `files/get_temporary_link` | 一時URLを外部プレーヤーへ渡す |
| フォルダ選択 | `files/list_folder`(非再帰) | フォルダのみ表示 |

### 6.1 撮影日時取得の方針

`list_folder` の `include_media_info` は 2019年12月に廃止され、一覧では撮影日時が取れない。単純に `get_metadata` を1件ずつ呼ぶと数千回のリクエストになるため、`get_thumbnail_batch` がメタデータ(media_info 含む)を同時に返す仕様を使い、25件ずつまとめて取得する。非対応ファイルだけ `get_metadata` で補う。

取得結果はローカルDBに保存し、同じファイル(id と rev が同じ)は再取得しない。`media_info` が `pending`(Dropbox 側で解析中)の場合は更新日時で仮置きし、次回同期で再取得する。

## 7. データ設計

### 7.1 ローカルDB(Room)

テーブル `photos`

| カラム | 型 | 説明 |
|---|---|---|
| id | TEXT PK | Dropbox のファイルID(`id:...`)。移動しても不変 |
| path_lower | TEXT (index) | 削除通知の照合用 |
| path_display | TEXT | 表示・API 呼び出し用 |
| name | TEXT | ファイル名 |
| ext | TEXT | 小文字の拡張子 |
| size | INTEGER | バイト数 |
| rev | TEXT | リビジョン。変化したら撮影日時を取り直す |
| client_modified | INTEGER | エポックミリ秒 |
| time_taken | INTEGER NULL | 撮影日時 |
| sort_time | INTEGER (index) | `time_taken` があればそれ、無ければ `client_modified` |
| is_video | INTEGER | 0/1 |
| duration_ms | INTEGER NULL | 動画の長さ |
| media_state | INTEGER | 0=未取得, 1=取得済, 2=pending, 3=情報なし |

### 7.2 SharedPreferences

| キー | 内容 |
|---|---|
| `credential` | `DbxCredential` の JSON |
| `folder_path` / `folder_display` | 対象フォルダ(ルートは空文字) |
| `recursive` | サブフォルダ探索 |
| `exclude_small` | 除外フィルタ |
| `group_mode` / `media_filter` | グリッドの表示状態(group_mode の初期値は月) |
| `columns` | 1行のサムネイル数(3〜8、初期値4。ピンチで変更) |
| `cursor` / `cursor_config` | 差分取得カーソルと、それを取得したときの「フォルダ+探索設定」 |

認証情報はアプリ専用領域に保存する(他アプリからは読めない)。端末を共有する場合は暗号化の追加を検討。

### 7.3 キャッシュ

- メモリ: LruCache(最大メモリの1/8)にグリッド用サムネイルの Bitmap
- ディスク: `cacheDir/thumb256/`(上限200MB)、`cacheDir/preview/`(上限100MB)に `sha1(キー)` 名で保存。起動時に上限を超えていたら古い順に上限の8割まで削除(OS による削除もあり得る)

## 8. 同期処理(SyncManager)

単一スレッドで直列実行し、状態を LiveData で通知する。

1. 現在の設定キー(`folder_path|recursive`)と `cursor_config` が違えば、DB とカーソルを破棄
2. カーソルがなければ全件取得(`list_folder`)、あれば差分取得(`list_folder/continue`)。`has_more` の間繰り返す
3. 反映のしかた
   - **全件取得**: Dropbox は日付と無関係な順(フォルダ順など)で返すため、全ページを集めてから1トランザクションで一度に反映する(途中経過を出すと古い写真から表示・取得されてしまう)
   - **差分取得**: ページごとに反映し、同時にカーソルを保存
   - `FileMetadata`(対象拡張子のみ)→ upsert。rev が変わっていれば `media_state=0` に戻す
   - `DeletedMetadata` → そのパス、またはその配下のパスの行を削除
   - `FolderMetadata` → 無視
4. `media_state` が 0 または 2 の行について、新しい順に撮影日時を取得(6.1 の方針)
   - 最新100件までは25件ずつ処理し、進捗バーを進める。100件そろった時点で「更新完了」としてくるくると進捗バーを止める
   - 残りは裏で100件ずつ続け、サブタイトルに「撮影日時を取得中… n/m」を表示。裏で取得中に新しい同期要求が来たら中断してそちらを優先
5. エラー時: レート制限(429)は指示された秒数待って再試行。認証エラーは再ログインを促す。通信エラーは状態に表示して終了

起動時(前回同期から1分以上経過)と、下に引っぱったときに実行する。

## 9. サムネイル読み込み(ThumbnailLoader)

- `load(ImageView, PhotoEntity)` でメモリ → ディスク → ネットワークの順に探す
- ネットワーク要求はキューに積み、ディスパッチャが最大 40ms 待って最大25件を1回の `get_thumbnail_batch` で取得
- 新しく要求されたものを優先(後入れ先出し)。高速スクロールで画面外に出たセルの要求は破棄
- 取得失敗(非対応形式など)は種別アイコンを表示し、そのセッション中は再要求しない
- 表示中セルの要求が無い間は、最新から100件を新しい順に先読みする(グリッド更新のたびに対象を置き換え)
- ディスパッチスレッドは例外で止まらないよう Throwable をすべて捕捉する

## 10. クラス構成

```
jp.photoviewer.dbx
├─ App                     Application。各シングルトンの初期化
├─ Prefs                   SharedPreferences ラッパ
├─ dropbox/
│  ├─ DropboxClientProvider  認証情報の保存/読込、DbxClientV2 生成
│  ├─ SyncManager            一覧・差分同期と撮影日時の取得
│  ├─ ThumbnailApi           サムネイル API を HTTP で直接呼ぶ(SDK の quality 問題の回避)
│  └─ MediaTypes             対象拡張子の判定
├─ data/
│  ├─ PhotoEntity / PhotoDao / AppDatabase   Room
├─ image/
│  ├─ ThumbnailLoader        グリッド用サムネイルのバッチ取得
│  ├─ PreviewLoader          ビューア用の大きな画像取得
│  └─ DiskCache              ディスクキャッシュ
└─ ui/
   ├─ MainActivity / GalleryViewModel / GalleryAdapter / GalleryItem / GroupMode
   ├─ ViewerActivity / ViewerAdapter
   ├─ SettingsActivity / SettingsFragment
   └─ FolderPickerActivity
```

## 11. 既知の制約・今後の拡張

- `get_thumbnail_batch` の公式対応形式は jpg, jpeg, png, tiff, tif, gif, webp, ppm, bmp。HEIC と動画のサムネイルが返るかは実機で確認が必要(返らなければ種別アイコン表示)
- スター付き絞り込みは API で扱えないため未対応
- 数万件規模の `recursive=true` は Dropbox 側で遅くなる場合がある(公式の注意書きあり)
- 拡張候補: 複数フォルダの指定、動画のアプリ内再生(Media3 ExoPlayer)、地図表示(media_info の位置情報)、認証情報の暗号化
