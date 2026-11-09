package jp.photoviewer.dbx.image;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;

import com.dropbox.core.NetworkIOException;
import com.dropbox.core.v2.DbxClientV2;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

import jp.photoviewer.dbx.data.PhotoEntity;
import jp.photoviewer.dbx.dropbox.DropboxClientProvider;
import jp.photoviewer.dbx.dropbox.ThumbnailApi;

/**
 * ビューア用の大きな画像を取得する。
 * まず Dropbox 側で 2048×1536 以内に縮小した JPEG を取得し、
 * 縮小版が作れない形式(HEIC など)は元ファイルを落として端末側で JPEG に変換する。
 * どちらの場合も、結果は表示しやすい JPEG ファイルとしてキャッシュする。
 */
public class PreviewLoader {

    private static final String TAG = "PreviewLoader";
    private static final int MAX_DECODE_SIDE = 4096;

    public interface Callback {
        void onLoaded(File file);
        void onError(String message);
    }

    private final DropboxClientProvider dropbox;
    private final DiskCache cache;
    private final ExecutorService executor;
    private final Handler main = new Handler(Looper.getMainLooper());

    public PreviewLoader(DropboxClientProvider dropbox, DiskCache cache, ExecutorService executor) {
        this.dropbox = dropbox;
        this.cache = cache;
        this.executor = executor;
    }

    /** すでにキャッシュ済みならファイルを返す(未キャッシュなら null)。ダウンロードはしない。 */
    @Nullable
    public File getCachedFile(PhotoEntity photo) {
        return cache.get(photo.cacheKey() + "#preview");
    }

    public Future<?> load(PhotoEntity photo, Callback callback) {
        return executor.submit(() -> {
            try {
                File f = loadBlocking(photo);
                main.post(() -> callback.onLoaded(f));
            } catch (NetworkIOException e) {
                main.post(() -> callback.onError("ネットワークに接続できません"));
            } catch (Exception e) {
                Log.w(TAG, "preview failed: " + photo.pathDisplay, e);
                main.post(() -> callback.onError("画像を表示できません"));
            }
        });
    }

    private File loadBlocking(PhotoEntity photo) throws Exception {
        String key = photo.cacheKey() + "#preview";
        File cached = cache.get(key);
        if (cached != null) return cached;

        DbxClientV2 client = dropbox.requireClient();

        // 1) Dropbox 側の縮小版
        File tmp = cache.newTempFile();
        try {
            try (OutputStream out = new FileOutputStream(tmp)) {
                ThumbnailApi.thumbnail(dropbox, photo.id, "w2048h1536", "bestfit", out);
            }
            return cache.commit(tmp, key);
        } catch (ThumbnailApi.AuthException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            Log.i(TAG, "thumbnail not available, download original: " + photo.name + " (" + e.getMessage() + ")");
        }

        // 2) 元ファイルを落として端末でデコード
        File original = cache.newTempFile();
        try {
            try (OutputStream out = new FileOutputStream(original)) {
                client.files().download(photo.id).download(out);
            }
            Bitmap bm = decodeDownsampled(original);
            if (bm == null) throw new IOException("decode failed");
            File jpeg = cache.newTempFile();
            try (OutputStream out = new FileOutputStream(jpeg)) {
                bm.compress(Bitmap.CompressFormat.JPEG, 92, out);
            } finally {
                bm.recycle();
            }
            return cache.commit(jpeg, key);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            original.delete();
        }
    }

    @Nullable
    private static Bitmap decodeDownsampled(File file) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder は HEIF / DNG にも対応(Android 9 以降)
            ImageDecoder.Source src = ImageDecoder.createSource(file);
            return ImageDecoder.decodeBitmap(src, (decoder, info, s) -> {
                int w = info.getSize().getWidth();
                int h = info.getSize().getHeight();
                int longest = Math.max(w, h);
                if (longest > MAX_DECODE_SIDE) {
                    float scale = (float) MAX_DECODE_SIDE / longest;
                    decoder.setTargetSize(Math.round(w * scale), Math.round(h * scale));
                }
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_DECODE_SIDE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }
}
