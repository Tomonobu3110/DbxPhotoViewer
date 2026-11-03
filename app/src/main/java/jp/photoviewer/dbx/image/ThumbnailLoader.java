package jp.photoviewer.dbx.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.MainThread;
import androidx.annotation.Nullable;


import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jp.photoviewer.dbx.data.PhotoEntity;
import jp.photoviewer.dbx.dropbox.DropboxClientProvider;
import jp.photoviewer.dbx.dropbox.ThumbnailApi;

/**
 * グリッド用サムネイルの読み込み。(API 呼び出しは ThumbnailApi 経由)
 * メモリ → ディスク → ネットワークの順に探し、ネットワーク要求は最大25件ずつ
 * get_thumbnail_batch でまとめて取得する。新しく要求されたものを優先する。
 */
public class ThumbnailLoader {

    private static final String TAG = "ThumbnailLoader";
    private static final int BATCH_SIZE = 25;
    private static final long COLLECT_WAIT_MS = 40;
    private static final int NETWORK_THREADS = 2;

    private static final class Request {
        final String key;
        @DrawableRes final int errorRes;

        Request(String key, int errorRes) {
            this.key = key;
            this.errorRes = errorRes;
        }
    }

    private final DropboxClientProvider dropbox;
    private final DiskCache disk;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService diskExecutor = Executors.newFixedThreadPool(2);
    private final LruCache<String, Bitmap> memory;
    private final Set<String> failed = ConcurrentHashMap.newKeySet();

    /** どの ImageView がどのサムネイルを待っているか(メインスレッドのみ)。 */
    private final Map<ImageView, Request> wanted = new WeakHashMap<>();

    // ネットワーク待ち行列(lock で保護)。末尾ほど新しい
    private final Object lock = new Object();
    private final List<String> order = new ArrayList<>();
    private final Map<String, PhotoEntity> queued = new HashMap<>();
    private final Set<String> inFlight = new HashSet<>();
    /** 先読み待ち(新しい写真から順)。表示中セルの要求が無いときだけ処理する。 */
    private final List<PhotoEntity> prefetchQueue = new ArrayList<>();
    /** ディスクキャッシュにあると分かっているキー。 */
    private final Set<String> onDisk = ConcurrentHashMap.newKeySet();

    public ThumbnailLoader(DropboxClientProvider dropbox, DiskCache disk) {
        this.dropbox = dropbox;
        this.disk = disk;
        int maxKb = (int) (Runtime.getRuntime().maxMemory() / 1024 / 8);
        memory = new LruCache<String, Bitmap>(maxKb) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount() / 1024;
            }
        };
        for (int i = 0; i < NETWORK_THREADS; i++) {
            Thread t = new Thread(this::dispatchLoop, "thumb-net-" + i);
            t.setDaemon(true);
            t.start();
        }
    }

    /** ImageView にサムネイルを表示する。errorRes は取得できなかったときのアイコン。 */
    @MainThread
    public void load(ImageView view, PhotoEntity photo, @DrawableRes int errorRes) {
        String key = photo.cacheKey();
        Request old = wanted.put(view, new Request(key, errorRes));
        if (old != null && !old.key.equals(key)) dequeueIfUnwanted(old.key);

        Bitmap cached = memory.get(key);
        if (cached != null) {
            view.setScaleType(ImageView.ScaleType.CENTER_CROP);
            view.setImageBitmap(cached);
            return;
        }
        view.setImageDrawable(null);
        if (failed.contains(key)) {
            showError(view, errorRes);
            return;
        }
        diskExecutor.execute(() -> {
            File f = disk.get(key);
            if (f != null) {
                onDisk.add(key);
                Bitmap bm = decode(f);
                if (bm != null) {
                    memory.put(key, bm);
                    deliver(key, bm);
                    return;
                }
            }
            enqueue(key, photo);
        });
    }

    /** セルが再利用・破棄されるときに呼ぶ。 */
    @MainThread
    public void cancel(ImageView view) {
        Request old = wanted.remove(view);
        if (old != null) dequeueIfUnwanted(old.key);
    }

    public void clearMemory() {
        memory.evictAll();
        failed.clear();
        onDisk.clear();
    }

    /**
     * 新しい写真から順にサムネイルを先読みする(photos は新しい順に並んでいる前提)。
     * 表示中のセルの要求が常に優先され、先読みはその合間に行う。
     * 呼ぶたびに先読み対象を置き換える。
     */
    public void prefetch(List<PhotoEntity> photos, int maxCount) {
        final List<PhotoEntity> head = new ArrayList<>(photos.subList(0, Math.min(maxCount, photos.size())));
        diskExecutor.execute(() -> {
            List<PhotoEntity> todo = new ArrayList<>();
            for (PhotoEntity p : head) {
                String key = p.cacheKey();
                if (failed.contains(key) || onDisk.contains(key)) continue;
                if (disk.get(key) != null) {
                    onDisk.add(key);
                    continue;
                }
                todo.add(p);
            }
            synchronized (lock) {
                prefetchQueue.clear();
                prefetchQueue.addAll(todo);
                lock.notifyAll();
            }
        });
    }

    // ------------------------------------------------------------------

    private void dequeueIfUnwanted(String key) {
        for (Request r : wanted.values()) {
            if (r.key.equals(key)) return;
        }
        synchronized (lock) {
            if (queued.remove(key) != null) order.remove(key);
        }
    }

    private void enqueue(String key, PhotoEntity photo) {
        synchronized (lock) {
            if (inFlight.contains(key)) return;
            if (queued.containsKey(key)) {
                order.remove(key); // 末尾(最優先)へ移す
            }
            queued.put(key, photo);
            order.add(key);
            lock.notifyAll();
        }
    }

    private void dispatchLoop() {
        while (true) {
            try {
                synchronized (lock) {
                    while (order.isEmpty() && prefetchQueue.isEmpty()) lock.wait();
                }
                // 少し待って、まとめて要求できる分を集める
                Thread.sleep(COLLECT_WAIT_MS);
                List<PhotoEntity> batch = new ArrayList<>();
                synchronized (lock) {
                    while (!order.isEmpty() && batch.size() < BATCH_SIZE) {
                        String key = order.remove(order.size() - 1);
                        PhotoEntity p = queued.remove(key);
                        if (p != null) {
                            batch.add(p);
                            inFlight.add(key);
                        }
                    }
                    // 表示中セルの要求が無ければ、先読みを新しい順に処理する
                    while (batch.isEmpty() && !prefetchQueue.isEmpty()) {
                        while (!prefetchQueue.isEmpty() && batch.size() < BATCH_SIZE) {
                            PhotoEntity p = prefetchQueue.remove(0);
                            String key = p.cacheKey();
                            if (inFlight.contains(key) || queued.containsKey(key)
                                    || onDisk.contains(key) || failed.contains(key)) continue;
                            batch.add(p);
                            inFlight.add(key);
                        }
                    }
                }
                if (!batch.isEmpty()) {
                    try {
                        fetch(batch);
                    } finally {
                        synchronized (lock) {
                            for (PhotoEntity p : batch) inFlight.remove(p.cacheKey());
                        }
                    }
                }
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                // 例外でスレッドが止まるとサムネイルが二度と取得されなくなるため、すべて捕捉する
                Log.e(TAG, "dispatch error", e);
            }
        }
    }

    private void fetch(List<PhotoEntity> batch) {
        if (!dropbox.isLoggedIn()) return;
        List<String> paths = new ArrayList<>();
        for (PhotoEntity p : batch) paths.add(p.id);

        List<ThumbnailApi.Entry> entries;
        try {
            entries = ThumbnailApi.batch(dropbox, paths, "w256h256", "fitone_bestfit");
        } catch (IOException e) {
            // 通信エラー等。失敗扱いにはせず、次に表示されたとき再要求する
            Log.w(TAG, "thumbnail batch failed", e);
            return;
        }
        for (int i = 0; i < batch.size(); i++) {
            PhotoEntity p = batch.get(i);
            String key = p.cacheKey();
            ThumbnailApi.Entry entry = i < entries.size() ? entries.get(i) : null;
            if (entry == null || !entry.success || entry.thumbnailBase64 == null) {
                Log.d(TAG, "no thumbnail: " + p.pathDisplay + " " + (entry != null ? entry.error : "missing"));
                markFailed(key);
                continue;
            }
            try {
                byte[] data = Base64.decode(entry.thumbnailBase64, Base64.DEFAULT);
                disk.put(key, data);
                onDisk.add(key);
                Bitmap bm = decode(data);
                if (bm == null) {
                    markFailed(key);
                } else {
                    memory.put(key, bm);
                    deliver(key, bm);
                }
            } catch (IOException | IllegalArgumentException e) {
                Log.w(TAG, "thumbnail decode failed: " + p.pathDisplay, e);
                markFailed(key);
            }
        }
    }

    private void markFailed(String key) {
        failed.add(key);
        deliverError(key);
    }

    private void deliver(String key, Bitmap bm) {
        main.post(() -> {
            for (Map.Entry<ImageView, Request> e : wanted.entrySet()) {
                if (e.getValue().key.equals(key)) {
                    e.getKey().setScaleType(ImageView.ScaleType.CENTER_CROP);
                    e.getKey().setImageBitmap(bm);
                }
            }
        });
    }

    private void deliverError(String key) {
        main.post(() -> {
            for (Map.Entry<ImageView, Request> e : wanted.entrySet()) {
                if (e.getValue().key.equals(key)) showError(e.getKey(), e.getValue().errorRes);
            }
        });
    }

    private static void showError(ImageView view, @DrawableRes int res) {
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setImageResource(res);
    }

    @Nullable
    private static Bitmap decode(File f) {
        return BitmapFactory.decodeFile(f.getPath(), rgb565());
    }

    @Nullable
    private static Bitmap decode(byte[] data) {
        return BitmapFactory.decodeByteArray(data, 0, data.length, rgb565());
    }

    private static BitmapFactory.Options rgb565() {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.RGB_565; // メモリ節約
        return o;
    }
}
