# 開発環境・セットアップ

## プロジェクト

| 項目 | 値 |
|---|---|
| 場所 | `C:\Android\DbxPhotoViewer` |
| 作り方 | Android Studio の New Project → Empty Views Activity で生成し、Claude が作ったコードで中身を置き換えた |
| アプリ名 / パッケージ | DBXフォトビューア / `jp.photoviewer.dbx` |
| 言語 | Java 11 |
| ビルド | Groovy DSL + バージョンカタログ(`gradle/libs.versions.toml`)、AGP 8.13.2、Gradle 8.13 |
| SDK | minSdk 26 / compileSdk 36 / targetSdk 36 |
| 主なライブラリ | Dropbox SDK 7.1.0(core / android)、Room 2.6.1、Material 1.14.0、RecyclerView、ViewPager2、SwipeRefreshLayout、Preference、Subsampling Scale Image View 3.10.0 |
| テンプレートの残骸 | `jp/photoviewer/dbx/MainActivity.java` は中身を空にしたもの(本体は `ui/MainActivity.java`)。削除して構わない |

Dropbox SDK を 8.x にしないのは、8.x が Java 21 を必要とするため。

## 実行環境

- 実機: Sony XQ-CQ44(Android 14 / API 34)
- エミュレータ: Pixel 3a API 35

## Dropbox アプリ登録(App Console)

| 項目 | 値 |
|---|---|
| アプリ名 | `PhotoFolderViewer-tsaito` |
| 種類 | スコープ付きアクセス / Dropbox へのフルアクセス |
| 権限 | `files.metadata.read`、`files.content.read`(読み取りのみ)。`account_info.read` は既定で付く |
| App key | `bfcws1euvef6b98`(公開されても問題ない識別子。App secret は使わない) |
| 状態 | 開発(自分のアカウントのみで使う分には問題なし) |

- App key は `local.properties` の `dropbox.appKey=` に書く。`local.properties` は Git 管理外
- 同じ App Console にある `ManaHinaViewer` は別のアプリ。触らない
- 権限を後から変えた場合は、アプリでログアウトして再接続する

## ビルド・実行の手順

1. (設定ファイルを変えたら)File → Sync Project with Gradle Files
2. Build → Rebuild Project
3. ▶ Run
4. 初回は「Dropbox に接続」→ ブラウザで許可 → アプリに戻る

## Claude と作業するときの注意

- Claude のクラウド作業環境ではコマンドを実行できない(ファイルの読み書きだけ)。この場合は**ビルドと実行は自分で行う**。Maven リポジトリにも接続できないため、コンパイルエラーは PC 側のビルドで見つかる
- **ローカルの Claude Code(PC 上で動くエージェント)はコマンドを実行できる。** `JAVA_HOME` を Android Studio 同梱の JBR(`C:\Program Files\Android\Android Studio\jbr`)に設定すれば、`./gradlew.bat :app:compileDebugJavaWithJavac` や `:app:assembleDebug` でコンパイル確認ができる(2026-11-04 のスライドショー追加作業で確認)。ただし実機での動作確認(Dropbox 同期・動画再生など)とインストールは引き続き自分で行う
- **Claude が書き換えるファイルは、Android Studio で開いたままにしない。** 開いていると、エディタの古い内容で上書きされることがある(debug.md D1)
- 不具合の調査には Logcat が役に立つ。タグ `ThumbnailApi` / `ThumbnailLoader` / `SyncManager` / `PreviewLoader` で絞り込める
