package jp.photoviewer.dbx;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import jp.photoviewer.dbx.ui.GroupMode;

/** 設定値と同期状態の保存。設定画面(Preference)と同じ既定の SharedPreferences を使う。 */
public class Prefs {

    public static final String KEY_FOLDER = "folder_path";
    public static final String KEY_FOLDER_DISPLAY = "folder_display";
    public static final String KEY_RECURSIVE = "recursive";
    public static final String KEY_EXCLUDE_SMALL = "exclude_small";
    private static final String KEY_CREDENTIAL = "credential";
    private static final String KEY_GROUP_MODE = "group_mode";
    private static final String KEY_MEDIA_FILTER = "media_filter";
    private static final String KEY_COLUMNS = "columns";

    /** 1行に並べるサムネイル数の範囲と初期値(ピンチで変更)。 */
    public static final int MIN_COLUMNS = 3;
    public static final int MAX_COLUMNS = 8;
    private static final int DEFAULT_COLUMNS = 4;
    private static final String KEY_CURSOR = "cursor";
    private static final String KEY_CURSOR_CONFIG = "cursor_config";
    private static final String KEY_LAST_SYNC = "last_sync";

    public static final int MEDIA_ALL = 0;
    public static final int MEDIA_PHOTO = 1;
    public static final int MEDIA_VIDEO = 2;

    private final SharedPreferences sp;

    public Prefs(Context context) {
        sp = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public SharedPreferences raw() {
        return sp;
    }

    // ---- 認証 ----
    @Nullable
    public String getCredential() {
        return sp.getString(KEY_CREDENTIAL, null);
    }

    public void setCredential(@Nullable String json) {
        sp.edit().putString(KEY_CREDENTIAL, json).apply();
    }

    // ---- 対象フォルダ ----
    /** API に渡すパス。ルートは空文字。 */
    public String getFolderPath() {
        return sp.getString(KEY_FOLDER, "");
    }

    public String getFolderDisplay() {
        String d = sp.getString(KEY_FOLDER_DISPLAY, "");
        return d.isEmpty() ? "/" : d;
    }

    public void setFolder(String pathLower, String pathDisplay) {
        sp.edit().putString(KEY_FOLDER, pathLower).putString(KEY_FOLDER_DISPLAY, pathDisplay).apply();
    }

    public boolean isRecursive() {
        return sp.getBoolean(KEY_RECURSIVE, true);
    }

    public boolean isExcludeSmall() {
        return sp.getBoolean(KEY_EXCLUDE_SMALL, true);
    }

    // ---- 表示状態 ----
    public GroupMode getGroupMode() {
        return GroupMode.fromOrdinal(sp.getInt(KEY_GROUP_MODE, GroupMode.MONTH.ordinal()));
    }

    public void setGroupMode(GroupMode mode) {
        sp.edit().putInt(KEY_GROUP_MODE, mode.ordinal()).apply();
    }

    public int getColumns() {
        int c = sp.getInt(KEY_COLUMNS, DEFAULT_COLUMNS);
        return Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, c));
    }

    public void setColumns(int columns) {
        sp.edit().putInt(KEY_COLUMNS, columns).apply();
    }

    public int getMediaFilter() {
        return sp.getInt(KEY_MEDIA_FILTER, MEDIA_ALL);
    }

    public void setMediaFilter(int filter) {
        sp.edit().putInt(KEY_MEDIA_FILTER, filter).apply();
    }

    // ---- 同期状態 ----
    /** 同期対象を表すキー。これが変わったらローカルDBを作り直す。 */
    public String currentSyncConfig() {
        return getFolderPath() + "|" + isRecursive();
    }

    @Nullable
    public String getCursor() {
        return sp.getString(KEY_CURSOR, null);
    }

    @Nullable
    public String getCursorConfig() {
        return sp.getString(KEY_CURSOR_CONFIG, null);
    }

    public void setCursor(@Nullable String cursor, @Nullable String config) {
        sp.edit().putString(KEY_CURSOR, cursor).putString(KEY_CURSOR_CONFIG, config).commit();
    }

    public long getLastSync() {
        return sp.getLong(KEY_LAST_SYNC, 0);
    }

    public void setLastSync(long time) {
        sp.edit().putLong(KEY_LAST_SYNC, time).apply();
    }

    /** ログアウト時に、表示設定以外の状態を消す。 */
    public void clearSession() {
        sp.edit()
                .remove(KEY_CREDENTIAL)
                .remove(KEY_CURSOR)
                .remove(KEY_CURSOR_CONFIG)
                .remove(KEY_LAST_SYNC)
                .remove(KEY_FOLDER)
                .remove(KEY_FOLDER_DISPLAY)
                .apply();
    }
}
