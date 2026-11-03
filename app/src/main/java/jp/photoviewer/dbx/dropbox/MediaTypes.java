package jp.photoviewer.dbx.dropbox;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** 表示対象の拡張子判定。 */
public final class MediaTypes {

    private static final Set<String> PHOTO = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "heic", "heif", "gif", "webp", "bmp", "tif", "tiff", "dng"));

    private static final Set<String> VIDEO = new HashSet<>(Arrays.asList(
            "mp4", "mov", "m4v", "3gp", "mkv", "webm", "avi"));

    /** get_thumbnail_batch が公式に対応している拡張子。 */
    private static final Set<String> THUMBNAIL_BATCH = new HashSet<>(Arrays.asList(
            "jpg", "jpeg", "png", "tiff", "tif", "gif", "webp", "ppm", "bmp"));

    /** get_thumbnail_batch が変換してくれる元画像サイズの上限。 */
    public static final long THUMBNAIL_MAX_BYTES = 20L * 1024 * 1024;

    /** 「小さい画像を除外」の閾値(公式アプリと同じ 15KB)。 */
    public static final long SMALL_IMAGE_BYTES = 15L * 1024;

    private MediaTypes() {}

    public static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return "";
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isPhoto(String ext) {
        return PHOTO.contains(ext);
    }

    public static boolean isVideo(String ext) {
        return VIDEO.contains(ext);
    }

    public static boolean isMedia(String ext) {
        return isPhoto(ext) || isVideo(ext);
    }

    /** 撮影日時の取得にサムネイルバッチを使えるか。 */
    public static boolean canUseThumbnailBatch(String ext, long size) {
        return THUMBNAIL_BATCH.contains(ext) && size <= THUMBNAIL_MAX_BYTES;
    }
}
