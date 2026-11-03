# 作業履歴

## 2026-11-03

1. Dropbox 公式モバイルアプリの「写真」タブの仕様を調査(表示対象・並び順・除外条件・グループ化)
2. Dropbox API で閲覧専用アプリが作れるか検討 → 可能。撮影日時の一括取得が課題と判明
3. 設計書(DESIGN.md)を作成し、Java で実装(当初はクラウド環境で作成し zip で受け渡し)
4. PC に `C:\Android\DbxPhotoViewer` を用意。Android Studio で空のプロジェクトを生成し、Claude がコードを配置して AGP 8.13.2 / SDK 36 に合わせた
5. Dropbox App Console でアプリ `PhotoFolderViewer-tsaito` を作成し、App key を取得(Claude が Chrome 操作で実施)
6. 実機(Sony XQ-CQ44)で動作確認しながら修正:
   - local.properties が Android Studio に上書きされる問題(D1、D2)
   - 見出しに件数を追加、最新優先の表示・サムネイル先読み(R9〜R11)
   - サムネイルが出ない: SDK の `quality` キー問題 → `ThumbnailApi` で回避(D3)
   - くるくるの終了条件の見直し、進捗バー追加(R12、R13、D4)
   - デフォルトの表示単位を「月」に(R14)
   - フォルダ切り替え時に古い写真から表示される問題(D7)
   - 年・月・日の表示ずれ(D5)と切り替えの遅さ(D6)
   - ピンチは列数変更だけに仕様変更(R15)
7. このドキュメント群を作成

### 次回の確認事項

- Rebuild 後に、D4〜D7 と R12、R13、R15、R16 の動作を実機で確認
- D9(9月の件数差)の調査
