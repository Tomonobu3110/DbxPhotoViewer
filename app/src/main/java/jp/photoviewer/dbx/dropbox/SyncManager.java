package jp.photoviewer.dbx.dropbox;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.dropbox.core.DbxException;
import com.dropbox.core.InvalidAccessTokenException;
import com.dropbox.core.NetworkIOException;
import com.dropbox.core.v2.DbxClientV2;
import com.dropbox.core.v2.files.DeletedMetadata;
import com.dropbox.core.v2.files.FileMetadata;
import com.dropbox.core.v2.files.GetMetadataErrorException;
import com.dropbox.core.v2.files.ListFolderContinueErrorException;
import com.dropbox.core.v2.files.ListFolderErrorException;
import com.dropbox.core.v2.files.ListFolderResult;
import com.dropbox.core.v2.files.MediaInfo;
import com.dropbox.core.v2.files.MediaMetadata;
import com.dropbox.core.v2.files.Metadata;
import com.dropbox.core.v2.files.VideoMetadata;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import jp.photoviewer.dbx.Prefs;
import jp.photoviewer.dbx.data.AppDatabase;
import jp.photoviewer.dbx.data.PhotoDao;
import jp.photoviewer.dbx.data.PhotoEntity;

/**
 * Dropbox との同期。
 * 1) list_folder / list_folder/continue でファイル一覧(差分)をローカルDBへ反映
 * 2) 撮影日時が未取得のものを get_thumbnail_batch(25件ずつ)/ get_metadata で補完
 * 同期は専用スレッドで直列に実行する。
 */
public class SyncManager {

    private static final String TAG = "SyncManager";
    private static final int BATCH_SIZE = 25;          // get_thumbnail_batch の上限
    private static final int INDEX_CHUNK = 100;        // DB へまとめて書く件数
    private static final int LOOKUP_CHUNK = 500;       // SQLite の変数上限対策
    /** 最新から何件の撮影日時を取ったら「更新完了」としてくるくるを止めるか。残りは裏で続ける。 */
    private static final int FOREGROUND_INDEX_COUNT = 100;

    /** UI に通知する同期状態。 */
    public static final class State {
        public final boolean running;
        @Nullable public final String message;
        public final boolean error;
        public final boolean authError;
        /** 引っぱって更新のくるくるは止めたが、撮影日時の取得を裏で続けている。 */
        public final boolean background;
        /** 進捗。max が 0 なら件数不明(不確定表示)。 */
        public final int progress;
        public final int max;

        State(boolean running, @Nullable String message, boolean error, boolean authError, boolean background,
              int progress, int max) {
            this.running = running;
            this.message = message;
            this.error = error;
            this.authError = authError;
            this.background = background;
            this.progress = progress;
            this.max = max;
        }

        static State idle() { return new State(false, null, false, false, false, 0, 0); }
        static State progress(String msg) { return new State(true, msg, false, false, false, 0, 0); }
        static State progress(String msg, int progress, int max) {
            return new State(true, msg, false, false, false, progress, max);
        }
        static State background(String msg) { return new State(false, msg, false, false, true, 0, 0); }
        static State failed(String msg, boolean auth) { return new State(false, msg, true, auth, false, 0, 0); }
    }

    private static final class MediaResult {
        final PhotoEntity photo;
        final Long timeTaken;
        final Long durationMs;
        final int state;

        MediaResult(PhotoEntity photo, Long timeTaken, Long durationMs, int state) {
            this.photo = photo;
            this.timeTaken = timeTaken;
            this.durationMs = durationMs;
            this.state = state;
        }
    }

    /** 同期中に対象フォルダ設定が変わったことを表す。 */
    private static final class ConfigChangedException extends Exception {}

    private final Prefs prefs;
    private final AppDatabase db;
    private final PhotoDao dao;
    private final DropboxClientProvider dropbox;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ExecutorService metadataPool = Executors.newFixedThreadPool(4);
    private final MutableLiveData<State> state = new MutableLiveData<>(State.idle());

    private boolean scheduled;
    private boolean rerun;

    public SyncManager(Prefs prefs, AppDatabase db, DropboxClientProvider dropbox) {
        this.prefs = prefs;
        this.db = db;
        this.dao = db.photos();
        this.dropbox = dropbox;
    }

    public LiveData<State> state() {
        return state;
    }

    /** 前回同期から minIntervalMs 以上たっていれば同期する。 */
    public void syncIfStale(long minIntervalMs) {
        boolean configChanged = !prefs.currentSyncConfig().equals(prefs.getCursorConfig());
        if (configChanged || System.currentTimeMillis() - prefs.getLastSync() >= minIntervalMs) {
            requestSync();
        }
    }

    /** 同期を要求する。実行中なら、終わったあとにもう一度実行する。 */
    public void requestSync() {
        synchronized (this) {
            if (scheduled) {
                rerun = true;
                return;
            }
            scheduled = true;
        }
        executor.execute(this::loop);
    }

    /** ログアウト時などにローカルDBを消す。 */
    public void clearLocalData() {
        executor.execute(dao::deleteAll);
    }

    private synchronized boolean rerunPending() {
        return rerun;
    }

    private void loop() {
        while (true) {
            runOnce();
            synchronized (this) {
                if (rerun) {
                    rerun = false;
                    continue;
                }
                scheduled = false;
                return;
            }
        }
    }

    private void runOnce() {
        try {
            doSync();
            state.postValue(State.idle());
        } catch (ConfigChangedException e) {
            // loop() 側で再実行される(設定変更時は requestSync() が呼ばれている)
            synchronized (this) {
                rerun = true;
            }
        } catch (DropboxClientProvider.NotLoggedInException e) {
            state.postValue(State.idle());
        } catch (InvalidAccessTokenException | InvalidTokenIOException e) {
            state.postValue(State.failed("認証の有効期限が切れました。再ログインしてください", true));
        } catch (NetworkIOException e) {
            state.postValue(State.failed("ネットワークに接続できません", false));
        } catch (ListFolderErrorException e) {
            if (e.errorValue.isPath()) {
                state.postValue(State.failed("対象フォルダが見つかりません。設定を確認してください", false));
            } else {
                state.postValue(State.failed("一覧の取得に失敗しました: " + e.errorValue, false));
            }
        } catch (DbxException e) {
            Log.w(TAG, "sync failed", e);
            state.postValue(State.failed("同期に失敗しました: " + e.getMessage(), false));
        } catch (RuntimeException e) {
            Log.e(TAG, "sync crashed", e);
            state.postValue(State.failed("同期中にエラーが発生しました: " + e.getMessage(), false));
        }
    }

    // ------------------------------------------------------------------
    // 一覧の同期
    // ------------------------------------------------------------------

    private void doSync() throws DbxException, ConfigChangedException,
            DropboxClientProvider.NotLoggedInException, InvalidTokenIOException {
        DbxClientV2 client = dropbox.requireClient();
        String config = prefs.currentSyncConfig();

        if (!config.equals(prefs.getCursorConfig())) {
            // 対象フォルダかサブフォルダ探索の設定が変わった → 作り直し
            dao.deleteAll();
            prefs.setCursor(null, config);
        }

        state.postValue(State.progress("一覧を取得中…"));
        ListFolderResult result;
        String cursor = prefs.getCursor();
        if (cursor == null) {
            result = listFromScratch(client);
        } else {
            try {
                result = client.files().listFolderContinue(cursor);
            } catch (ListFolderContinueErrorException e) {
                if (!e.errorValue.isReset()) throw e;
                // カーソルが無効になった → 全件取り直し
                dao.deleteAll();
                prefs.setCursor(null, config);
                result = listFromScratch(client);
            }
        }

        while (true) {
            checkConfig(config);
            applyPage(result.getEntries());
            prefs.setCursor(result.getCursor(), config);
            state.postValue(State.progress("一覧を取得中… " + dao.countAll() + "件"));
            if (!result.getHasMore()) break;
            result = client.files().listFolderContinue(result.getCursor());
        }

        indexMedia(client, config);
        prefs.setLastSync(System.currentTimeMillis());
    }

    private ListFolderResult listFromScratch(DbxClientV2 client) throws DbxException {
        return client.files().listFolderBuilder(prefs.getFolderPath())
                .withRecursive(prefs.isRecursive())
                .withLimit(2000L)
                .start();
    }

    private void checkConfig(String config) throws ConfigChangedException {
        if (!config.equals(prefs.currentSyncConfig())) throw new ConfigChangedException();
    }

    /** list_folder の1ページ分をDBへ反映する。 */
    private void applyPage(List<Metadata> entries) {
        List<String> deletes = new ArrayList<>();
        List<PhotoEntity> files = new ArrayList<>();

        for (Metadata m : entries) {
            if (m instanceof FileMetadata) {
                FileMetadata f = (FileMetadata) m;
                String ext = MediaTypes.extensionOf(f.getName());
                if (f.getPathLower() == null) continue;
                if (!MediaTypes.isMedia(ext)) {
                    // 拡張子が変わって対象外になった場合に備えて消しておく
                    deletes.add(f.getPathLower());
                    continue;
                }
                files.add(toEntity(f, ext));
            } else if (m instanceof DeletedMetadata) {
                if (m.getPathLower() != null) deletes.add(m.getPathLower());
            }
            // FolderMetadata は無視
        }

        // 既存行の撮影日時を引き継ぐ(rev が同じなら取り直さない)
        Map<String, PhotoEntity> existing = new HashMap<>();
        for (int i = 0; i < files.size(); i += LOOKUP_CHUNK) {
            List<String> ids = new ArrayList<>();
            for (PhotoEntity p : files.subList(i, Math.min(files.size(), i + LOOKUP_CHUNK))) {
                ids.add(p.id);
            }
            for (PhotoEntity p : dao.findByIds(ids)) existing.put(p.id, p);
        }
        for (PhotoEntity p : files) {
            PhotoEntity old = existing.get(p.id);
            if (old != null && old.rev.equals(p.rev)) {
                p.timeTaken = old.timeTaken;
                p.durationMs = old.durationMs;
                p.mediaState = old.mediaState;
                p.sortTime = old.timeTaken != null ? old.timeTaken : p.clientModified;
            }
        }

        db.runInTransaction(() -> {
            for (String path : deletes) dao.deletePathAndChildren(path);
            if (!files.isEmpty()) dao.upsertAll(files);
        });
    }

    private static PhotoEntity toEntity(FileMetadata f, String ext) {
        PhotoEntity p = new PhotoEntity();
        p.id = f.getId();
        p.pathLower = f.getPathLower();
        p.pathDisplay = f.getPathDisplay() != null ? f.getPathDisplay() : f.getPathLower();
        p.name = f.getName();
        p.ext = ext;
        p.size = f.getSize();
        p.rev = f.getRev();
        p.clientModified = f.getClientModified().getTime();
        p.sortTime = p.clientModified;
        p.isVideo = MediaTypes.isVideo(ext);
        p.mediaState = PhotoEntity.MEDIA_UNKNOWN;
        return p;
    }

    // ------------------------------------------------------------------
    // 撮影日時の補完
    // ------------------------------------------------------------------

    private void indexMedia(DbxClientV2 client, String config)
            throws DbxException, ConfigChangedException, InvalidTokenIOException {
        dao.resetPending();
        int total = dao.countMediaUnknown();
        int done = 0;
        boolean foreground = true;

        while (true) {
            checkConfig(config);
            // 裏で取得中に新しい同期(引っぱって更新など)が来たら、そちらを優先する
            if (!foreground && rerunPending()) return;
            // くるくる表示中は進捗バーが細かく進むよう、25件ずつ処理する
            List<PhotoEntity> chunk = dao.findMediaUnknown(foreground ? BATCH_SIZE : INDEX_CHUNK);
            if (chunk.isEmpty()) break;
            String msg = "撮影日時を取得中… " + done + "/" + total;
            int foregroundMax = Math.min(total, FOREGROUND_INDEX_COUNT);
            state.postValue(foreground ? State.progress(msg, done, foregroundMax) : State.background(msg));

            List<PhotoEntity> batchable = new ArrayList<>();
            List<PhotoEntity> singles = new ArrayList<>();
            for (PhotoEntity p : chunk) {
                if (MediaTypes.canUseThumbnailBatch(p.ext, p.size)) batchable.add(p);
                else singles.add(p);
            }

            List<MediaResult> results = new ArrayList<>();
            for (int i = 0; i < batchable.size(); i += BATCH_SIZE) {
                List<PhotoEntity> sub = batchable.subList(i, Math.min(batchable.size(), i + BATCH_SIZE));
                fetchByThumbnailBatch(sub, results, singles);
            }
            results.addAll(fetchByMetadata(client, singles));

            db.runInTransaction(() -> {
                for (MediaResult r : results) {
                    long sort = r.timeTaken != null ? r.timeTaken : r.photo.clientModified;
                    dao.updateMedia(r.photo.id, r.photo.rev, r.timeTaken, sort, r.durationMs, r.state);
                }
            });
            done += chunk.size();
            if (foreground && done >= FOREGROUND_INDEX_COUNT) {
                // 最新分はそろったので更新完了扱いにし、残りは裏で続ける
                foreground = false;
                prefs.setLastSync(System.currentTimeMillis());
                state.postValue(State.background("撮影日時を取得中… " + done + "/" + total));
            }
        }
    }

    /**
     * get_thumbnail_batch は各ファイルのメタデータ(media_info 付き)も返すので、
     * 最小サイズのサムネイルを要求して撮影日時だけを利用する。
     * 取れなかったものは fallback に回す。
     */
    private void fetchByThumbnailBatch(List<PhotoEntity> photos, List<MediaResult> out,
                                       List<PhotoEntity> fallback) throws InvalidTokenIOException {
        List<String> paths = new ArrayList<>();
        for (PhotoEntity p : photos) paths.add(p.id);
        List<ThumbnailApi.Entry> entries;
        try {
            entries = ThumbnailApi.batch(dropbox, paths, "w32h32", "strict");
        } catch (ThumbnailApi.AuthException e) {
            throw new InvalidTokenIOException(e);
        } catch (java.io.IOException e) {
            Log.w(TAG, "thumbnail batch failed, fallback to get_metadata", e);
            fallback.addAll(photos);
            return;
        }
        for (int i = 0; i < photos.size(); i++) {
            PhotoEntity p = photos.get(i);
            ThumbnailApi.Entry e = i < entries.size() ? entries.get(i) : null;
            if (e == null || !e.success || !e.hasMediaInfo) {
                fallback.add(p);
            } else if (e.mediaPending) {
                out.add(new MediaResult(p, null, null, PhotoEntity.MEDIA_PENDING));
            } else {
                out.add(new MediaResult(p, e.timeTaken, e.durationMs, PhotoEntity.MEDIA_DONE));
            }
        }
    }

    /** サムネイル API の認証エラーを、同期処理の中で区別して扱うための例外。 */
    private static final class InvalidTokenIOException extends Exception {
        InvalidTokenIOException(Throwable cause) {
            super(cause);
        }
    }

    /** get_metadata(include_media_info=true)を並列で1件ずつ呼ぶ。 */
    private List<MediaResult> fetchByMetadata(DbxClientV2 client, List<PhotoEntity> photos) throws DbxException {
        List<Future<MediaResult>> futures = new ArrayList<>();
        for (PhotoEntity p : photos) {
            futures.add(metadataPool.submit(() -> {
                try {
                    Metadata m = client.files().getMetadataBuilder(p.id).withIncludeMediaInfo(true).start();
                    MediaResult r = null;
                    if (m instanceof FileMetadata) r = fromMediaInfo(p, ((FileMetadata) m).getMediaInfo());
                    return r != null ? r : new MediaResult(p, null, null, PhotoEntity.MEDIA_NONE);
                } catch (GetMetadataErrorException e) {
                    // 削除済みなど。次回の差分同期で消える
                    return new MediaResult(p, null, null, PhotoEntity.MEDIA_NONE);
                }
            }));
        }
        List<MediaResult> results = new ArrayList<>();
        for (Future<MediaResult> f : futures) {
            try {
                results.add(f.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof DbxException) throw (DbxException) cause;
                throw new RuntimeException(cause);
            }
        }
        return results;
    }

    /** media_info を結果に変換する。media_info が無ければ null。 */
    @Nullable
    private static MediaResult fromMediaInfo(PhotoEntity p, @Nullable MediaInfo info) {
        if (info == null) return null;
        if (info.isPending()) {
            return new MediaResult(p, null, null, PhotoEntity.MEDIA_PENDING);
        }
        if (info.isMetadata()) {
            MediaMetadata mm = info.getMetadataValue();
            Date taken = mm.getTimeTaken();
            Long duration = (mm instanceof VideoMetadata) ? ((VideoMetadata) mm).getDuration() : null;
            return new MediaResult(p, taken != null ? taken.getTime() : null, duration, PhotoEntity.MEDIA_DONE);
        }
        return null;
    }
}
