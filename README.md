# DBXフォトビューア

指定した Dropbox フォルダの写真・動画を、公式アプリの「写真」タブのように撮影日順のグリッドで閲覧する Android アプリ(閲覧専用)。仕様は [DESIGN.md](DESIGN.md) を参照。

## セットアップ

1. **Dropbox アプリを登録する**
   - https://www.dropbox.com/developers/apps →「Create app」
   - 「Scoped access」「Full Dropbox」を選び、任意の名前で作成
   - 「Permissions」タブで `files.metadata.read` と `files.content.read` にチェックして「Submit」
   - 「Settings」タブの **App key** を控える(App secret は不要)
2. **`local.properties` の `dropbox.appKey=` の後ろに App key を書く**
   ```
   dropbox.appKey=控えたApp key
   ```
3. Gradle を同期(Sync Now)して、実機かエミュレータで ▶ Run

初回起動で「Dropbox に接続」→ ブラウザで許可すると、ルートフォルダ以下の写真が表示されます。対象フォルダとサブフォルダ探索の有無は、メニュー →「設定」で変更できます。

## 補足

- 認証情報はアプリ専用領域の SharedPreferences に保存されます
