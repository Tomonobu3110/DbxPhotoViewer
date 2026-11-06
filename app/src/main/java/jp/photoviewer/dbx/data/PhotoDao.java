package jp.photoviewer.dbx.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface PhotoDao {

    /**
     * グリッド表示用。
     * excludeSmall=1 のとき、写真のうち 15KB 未満と png を除外する(公式アプリと同じ)。
     */
    @Query("SELECT * FROM photos WHERE "
            + "(:excludeSmall = 0 OR is_video = 1 OR (size >= 15360 AND ext != 'png')) "
            + "ORDER BY sort_time DESC, name DESC")
    LiveData<List<PhotoEntity>> observe(int excludeSmall);

    /** スライドショーの再起動用。observe() と同じ条件・並びで、最新 limit 件だけ取得する。 */
    @Query("SELECT * FROM photos WHERE "
            + "(:excludeSmall = 0 OR is_video = 1 OR (size >= 15360 AND ext != 'png')) "
            + "ORDER BY sort_time DESC, name DESC LIMIT :limit")
    List<PhotoEntity> listOnce(int excludeSmall, int limit);

    @Query("SELECT * FROM photos WHERE id IN (:ids)")
    List<PhotoEntity> findByIds(List<String> ids);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void upsertAll(List<PhotoEntity> photos);

    /** 削除通知: そのパス自身と、フォルダだった場合はその配下をまとめて消す。 */
    @Query("DELETE FROM photos WHERE path_lower = :path OR substr(path_lower, 1, length(:path) + 1) = :path || '/'")
    void deletePathAndChildren(String path);

    @Query("SELECT * FROM photos WHERE media_state = 0 ORDER BY sort_time DESC LIMIT :limit")
    List<PhotoEntity> findMediaUnknown(int limit);

    @Query("SELECT COUNT(*) FROM photos WHERE media_state = 0")
    int countMediaUnknown();

    @Query("SELECT COUNT(*) FROM photos")
    int countAll();

    /** pending だったものを次の同期で取り直すため、未取得に戻す。 */
    @Query("UPDATE photos SET media_state = 0 WHERE media_state = 2")
    void resetPending();

    @Query("UPDATE photos SET time_taken = :timeTaken, sort_time = :sortTime, duration_ms = :durationMs, "
            + "media_state = :state WHERE id = :id AND rev = :rev")
    void updateMedia(String id, String rev, Long timeTaken, long sortTime, Long durationMs, int state);

    @Query("DELETE FROM photos")
    void deleteAll();
}
