package jp.photoviewer.dbx.dropbox;

import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * サムネイル系 API を HTTP で直接呼ぶ。
 *
 * Dropbox SDK 7.1.0 の get_thumbnail_batch / get_thumbnail_v2 は、現在の API が受け付けない
 * "quality" キーを送ってしまい、"unexpected key 'quality'" エラーになる。
 * そのためこの2つだけは SDK を通さず、必要なキーだけの JSON を自前で送る。
 */
public final class ThumbnailApi {

    private static final String TAG = "ThumbnailApi";
    private static final String BASE = "https://content.dropboxapi.com/2/files/";
    private static final int MAX_RATE_LIMIT_RETRIES = 3;

    /** get_thumbnail_batch の1件分の結果。 */
    public static final class Entry {
        public final boolean success;
        @Nullable public final String thumbnailBase64;
        /** media_info が返ったか(写真・動画として解析済みか pending)。 */
        public final boolean hasMediaInfo;
        public final boolean mediaPending;
        @Nullable public final Long timeTaken;
        @Nullable public final Long durationMs;
        @Nullable public final String error;

        Entry(boolean success, @Nullable String thumbnailBase64, boolean hasMediaInfo, boolean mediaPending,
              @Nullable Long timeTaken, @Nullable Long durationMs, @Nullable String error) {
            this.success = success;
            this.thumbnailBase64 = thumbnailBase64;
            this.hasMediaInfo = hasMediaInfo;
            this.mediaPending = mediaPending;
            this.timeTaken = timeTaken;
            this.durationMs = durationMs;
            this.error = error;
        }
    }

    /** 認証エラー(401)。再ログインが必要。 */
    public static class AuthException extends IOException {
        AuthException(String message) {
            super(message);
        }
    }

    private ThumbnailApi() {}

    /**
     * 最大25件のサムネイルとメタデータ(media_info 含む)を取得する。
     * @param size "w32h32", "w256h256" など
     * @param mode "strict", "bestfit", "fitone_bestfit"
     */
    public static List<Entry> batch(DropboxClientProvider dropbox, List<String> paths, String size, String mode)
            throws IOException {
        JSONObject body = new JSONObject();
        try {
            JSONArray entries = new JSONArray();
            for (String path : paths) {
                JSONObject e = new JSONObject();
                e.put("path", path);
                e.put("format", "jpeg");
                e.put("size", size);
                e.put("mode", mode);
                entries.put(e);
            }
            body.put("entries", entries);
        } catch (JSONException e) {
            throw new IOException(e);
        }

        byte[] response = post(dropbox, BASE + "get_thumbnail_batch", null,
                body.toString().getBytes(StandardCharsets.UTF_8), "application/json");

        List<Entry> result = new ArrayList<>();
        try {
            JSONArray arr = new JSONObject(new String(response, StandardCharsets.UTF_8)).getJSONArray("entries");
            for (int i = 0; i < arr.length(); i++) {
                result.add(parseEntry(arr.getJSONObject(i)));
            }
        } catch (JSONException e) {
            throw new IOException("unexpected response of get_thumbnail_batch", e);
        }
        return result;
    }

    /** get_thumbnail_v2 で1件取得し、画像バイト列を out に書く。 */
    public static void thumbnail(DropboxClientProvider dropbox, String path, String size, String mode,
                                 OutputStream out) throws IOException {
        String arg;
        try {
            JSONObject resource = new JSONObject();
            resource.put(".tag", "path");
            resource.put("path", path);
            JSONObject o = new JSONObject();
            o.put("resource", resource);
            o.put("format", "jpeg");
            o.put("size", size);
            o.put("mode", mode);
            arg = o.toString();
        } catch (JSONException e) {
            throw new IOException(e);
        }
        byte[] data = post(dropbox, BASE + "get_thumbnail_v2", arg, new byte[0], "application/octet-stream");
        out.write(data);
    }

    // ------------------------------------------------------------------

    private static Entry parseEntry(JSONObject o) {
        String tag = o.optString(".tag");
        if (!"success".equals(tag)) {
            JSONObject failure = o.optJSONObject("failure");
            return new Entry(false, null, false, false, null, null,
                    failure != null ? failure.optString(".tag", failure.toString()) : tag);
        }
        String thumb = o.optString("thumbnail", null);
        JSONObject metadata = o.optJSONObject("metadata");
        JSONObject mediaInfo = metadata != null ? metadata.optJSONObject("media_info") : null;
        if (mediaInfo == null) {
            return new Entry(true, thumb, false, false, null, null, null);
        }
        if ("pending".equals(mediaInfo.optString(".tag"))) {
            return new Entry(true, thumb, true, true, null, null, null);
        }
        JSONObject mm = mediaInfo.optJSONObject("metadata");
        Long taken = null;
        Long duration = null;
        if (mm != null) {
            String t = mm.optString("time_taken", null);
            if (t != null) {
                try {
                    taken = Instant.parse(t).toEpochMilli();
                } catch (RuntimeException ignore) {
                    // 形式が想定外なら撮影日時なし扱い
                }
            }
            if (mm.has("duration")) duration = mm.optLong("duration");
        }
        return new Entry(true, thumb, true, false, taken, duration, null);
    }

    /**
     * POST を送り、レスポンス本文を返す。
     * 401 は AuthException、429 は Retry-After だけ待って再試行、その他のエラーは IOException。
     */
    private static byte[] post(DropboxClientProvider dropbox, String url, @Nullable String apiArg,
                               byte[] body, String contentType) throws IOException {
        for (int attempt = 0; ; attempt++) {
            String token = dropbox.getFreshAccessToken();
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(20_000);
                conn.setReadTimeout(60_000);
                conn.setDoOutput(true);
                conn.setRequestProperty("Authorization", "Bearer " + token);
                conn.setRequestProperty("Content-Type", contentType);
                if (apiArg != null) conn.setRequestProperty("Dropbox-API-Arg", headerSafe(apiArg));
                conn.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }

                int code = conn.getResponseCode();
                if (code == 200) {
                    return readAll(conn.getInputStream());
                }
                String err = conn.getErrorStream() != null
                        ? new String(readAll(conn.getErrorStream()), StandardCharsets.UTF_8) : "";
                if (code == 401) {
                    throw new AuthException("unauthorized: " + err);
                }
                if (code == 429 && attempt < MAX_RATE_LIMIT_RETRIES) {
                    int wait = parseRetryAfter(conn.getHeaderField("Retry-After"));
                    Log.i(TAG, "rate limited, wait " + wait + "s");
                    sleep(wait * 1000L);
                    continue;
                }
                throw new IOException("HTTP " + code + " " + url + ": " + err);
            } finally {
                conn.disconnect();
            }
        }
    }

    /** Dropbox-API-Arg ヘッダは ASCII のみ可。非 ASCII 文字は \\uXXXX にエスケープする。 */
    private static String headerSafe(String json) {
        StringBuilder sb = new StringBuilder(json.length());
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c < 0x20 || c > 0x7e) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static int parseRetryAfter(@Nullable String v) {
        try {
            return v != null ? Math.max(1, Integer.parseInt(v.trim())) : 5;
        } catch (NumberFormatException e) {
            return 5;
        }
    }

    private static void sleep(long ms) throws IOException {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream is = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
