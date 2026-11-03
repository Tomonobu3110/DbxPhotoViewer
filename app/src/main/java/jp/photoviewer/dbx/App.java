package jp.photoviewer.dbx;

import android.app.Application;

import androidx.room.Room;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jp.photoviewer.dbx.data.AppDatabase;
import jp.photoviewer.dbx.dropbox.DropboxClientProvider;
import jp.photoviewer.dbx.dropbox.SyncManager;
import jp.photoviewer.dbx.image.DiskCache;
import jp.photoviewer.dbx.image.PreviewLoader;
import jp.photoviewer.dbx.image.ThumbnailLoader;

/** アプリ全体で共有するシングルトンを保持する。 */
public class App extends Application {

    private static final long THUMB_CACHE_LIMIT = 200L * 1024 * 1024;
    private static final long PREVIEW_CACHE_LIMIT = 100L * 1024 * 1024;

    private static App instance;

    private Prefs prefs;
    private AppDatabase database;
    private DropboxClientProvider dropbox;
    private SyncManager syncManager;
    private DiskCache thumbCache;
    private DiskCache previewCache;
    private ThumbnailLoader thumbnailLoader;
    private PreviewLoader previewLoader;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(3);

    public static App get() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        prefs = new Prefs(this);
        database = Room.databaseBuilder(this, AppDatabase.class, "photos.db")
                .fallbackToDestructiveMigration()
                .build();
        dropbox = new DropboxClientProvider(prefs);
        thumbCache = new DiskCache(new File(getCacheDir(), "thumb256"));
        previewCache = new DiskCache(new File(getCacheDir(), "preview"));
        thumbnailLoader = new ThumbnailLoader(dropbox, thumbCache);
        previewLoader = new PreviewLoader(dropbox, previewCache, ioExecutor);
        syncManager = new SyncManager(prefs, database, dropbox);

        ioExecutor.execute(() -> {
            thumbCache.trimTo(THUMB_CACHE_LIMIT);
            previewCache.trimTo(PREVIEW_CACHE_LIMIT);
        });
    }

    public Prefs prefs() { return prefs; }
    public AppDatabase database() { return database; }
    public DropboxClientProvider dropbox() { return dropbox; }
    public SyncManager sync() { return syncManager; }
    public ThumbnailLoader thumbnails() { return thumbnailLoader; }
    public PreviewLoader previews() { return previewLoader; }
    public ExecutorService io() { return ioExecutor; }

    /** サムネイル・プレビューのキャッシュを全削除する。 */
    public void clearImageCaches() {
        thumbnailLoader.clearMemory();
        ioExecutor.execute(() -> {
            thumbCache.clear();
            previewCache.clear();
        });
    }
}
