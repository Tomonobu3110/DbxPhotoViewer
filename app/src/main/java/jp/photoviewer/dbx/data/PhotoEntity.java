package jp.photoviewer.dbx.data;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** Dropbox 上の写真・動画1件。 */
@Entity(tableName = "photos",
        indices = {@Index("path_lower"), @Index("sort_time"), @Index("media_state")})
public class PhotoEntity {

    public static final int MEDIA_UNKNOWN = 0;
    public static final int MEDIA_DONE = 1;
    public static final int MEDIA_PENDING = 2;
    public static final int MEDIA_NONE = 3;

    /** Dropbox のファイルID("id:..." 形式)。ファイルを移動しても変わらない。 */
    @PrimaryKey
    @NonNull
    public String id = "";

    @ColumnInfo(name = "path_lower")
    @NonNull
    public String pathLower = "";

    @ColumnInfo(name = "path_display")
    @NonNull
    public String pathDisplay = "";

    @NonNull
    public String name = "";

    @NonNull
    public String ext = "";

    public long size;

    @NonNull
    public String rev = "";

    @ColumnInfo(name = "client_modified")
    public long clientModified;

    @ColumnInfo(name = "time_taken")
    @Nullable
    public Long timeTaken;

    /** 並べ替えキー。撮影日時があればそれ、無ければ client_modified。 */
    @ColumnInfo(name = "sort_time")
    public long sortTime;

    @ColumnInfo(name = "is_video")
    public boolean isVideo;

    @ColumnInfo(name = "duration_ms")
    @Nullable
    public Long durationMs;

    @ColumnInfo(name = "media_state")
    public int mediaState = MEDIA_UNKNOWN;

    /** キャッシュのキー。内容が変わると rev が変わるので、id と rev の組で一意になる。 */
    public String cacheKey() {
        return id + "@" + rev;
    }

    /** 親フォルダの表示用パス。 */
    public String parentDisplay() {
        int slash = pathDisplay.lastIndexOf('/');
        return slash <= 0 ? "/" : pathDisplay.substring(0, slash);
    }
}
