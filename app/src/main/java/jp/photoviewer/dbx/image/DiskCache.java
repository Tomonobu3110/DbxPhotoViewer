package jp.photoviewer.dbx.image;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Comparator;

/** ファイル名を sha1(key) にした単純なディスクキャッシュ。 */
public class DiskCache {

    private final File dir;

    public DiskCache(File dir) {
        this.dir = dir;
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    private File fileFor(String key) {
        return new File(dir, sha1(key));
    }

    /** キャッシュがあればファイルを返す(最終アクセス時刻を更新)。 */
    @Nullable
    public File get(String key) {
        File f = fileFor(key);
        if (f.isFile() && f.length() > 0) {
            //noinspection ResultOfMethodCallIgnored
            f.setLastModified(System.currentTimeMillis());
            return f;
        }
        return null;
    }

    public void put(String key, byte[] data) throws IOException {
        File tmp = newTempFile();
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
        }
        commit(tmp, key);
    }

    /** 書き込み途中のファイルを読まれないよう、一時ファイルに書いてから commit する。 */
    public File newTempFile() throws IOException {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return File.createTempFile("tmp", ".part", dir);
    }

    public File commit(File tmp, String key) throws IOException {
        File dst = fileFor(key);
        if (!tmp.renameTo(dst)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw new IOException("cache rename failed");
        }
        return dst;
    }

    /** 合計サイズが maxBytes を超えていたら、古いものから消す。 */
    public synchronized void trimTo(long maxBytes) {
        File[] files = dir.listFiles();
        if (files == null) return;
        long total = 0;
        for (File f : files) total += f.length();
        if (total <= maxBytes) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (File f : files) {
            if (total <= maxBytes * 0.8) break;
            total -= f.length();
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    public synchronized void clear() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
