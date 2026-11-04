package jp.photoviewer.dbx.ui;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jp.photoviewer.dbx.App;
import jp.photoviewer.dbx.Prefs;
import jp.photoviewer.dbx.data.PhotoEntity;

/** DB の写真一覧を、グループ見出し付きのグリッド要素に変換する。 */
public class GalleryViewModel extends AndroidViewModel {

    /** 同期中は DB 更新が続くので、少し待ってからまとめて組み立て直す。 */
    private static final long REBUILD_DELAY_MS = 300;

    public static final class Gallery {
        public final List<GalleryItem> items;
        public final List<PhotoEntity> photos;
        public final GroupMode mode;

        Gallery(List<GalleryItem> items, List<PhotoEntity> photos, GroupMode mode) {
            this.items = items;
            this.photos = photos;
            this.mode = mode;
        }
    }

    private final Prefs prefs;
    /** 「小さい画像と PNG を除外」の設定。 */
    private final MutableLiveData<Boolean> excludeSmall = new MutableLiveData<>();
    private final MutableLiveData<GroupMode> groupMode = new MutableLiveData<>();
    private final MediatorLiveData<Gallery> gallery = new MediatorLiveData<>();
    private final ExecutorService builder = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable rebuild = this::rebuildNow;

    private LiveData<List<PhotoEntity>> currentSource;
    private List<PhotoEntity> latestPhotos = Collections.emptyList();
    private int generation;

    public GalleryViewModel(@NonNull Application application) {
        super(application);
        prefs = App.get().prefs();
        groupMode.setValue(prefs.getGroupMode());
        excludeSmall.setValue(prefs.isExcludeSmall());

        // 除外設定が変わるたびに DB の監視対象を差し替える(switchMap 相当)
        gallery.addSource(excludeSmall, (Observer<Boolean>) exclude -> {
            if (currentSource != null) gallery.removeSource(currentSource);
            currentSource = App.get().database().photos().observe(Boolean.TRUE.equals(exclude) ? 1 : 0);
            gallery.addSource(currentSource, (Observer<List<PhotoEntity>>) list -> {
                boolean first = latestPhotos.isEmpty();
                latestPhotos = list != null ? list : Collections.emptyList();
                scheduleRebuild(first ? 0 : REBUILD_DELAY_MS);
            });
        });
        gallery.addSource(groupMode, (Observer<GroupMode>) m -> scheduleRebuild(0));
    }

    public LiveData<Gallery> gallery() {
        return gallery;
    }

    public GroupMode getGroupMode() {
        return Objects.requireNonNull(groupMode.getValue());
    }

    public void setGroupMode(GroupMode mode) {
        if (mode == groupMode.getValue()) return;
        prefs.setGroupMode(mode);
        groupMode.setValue(mode);
    }

    /** 設定画面から戻ったときなどに、除外設定を読み直す。 */
    public void reloadFilter() {
        boolean v = prefs.isExcludeSmall();
        if (!Boolean.valueOf(v).equals(excludeSmall.getValue())) excludeSmall.setValue(v);
    }

    private void scheduleRebuild(long delay) {
        main.removeCallbacks(rebuild);
        main.postDelayed(rebuild, delay);
    }

    private void rebuildNow() {
        final List<PhotoEntity> photos = latestPhotos;
        final GroupMode mode = getGroupMode();
        final int gen = ++generation;
        builder.execute(() -> {
            List<GalleryItem> items = new ArrayList<>(photos.size() + 64);
            Calendar cal = Calendar.getInstance();
            SimpleDateFormat fmt = mode.newFormat();
            // 同じグループ(日/月/年)に属する連続した範囲ごとに、見出しと件数を付ける
            int i = 0;
            while (i < photos.size()) {
                int key = mode.groupKey(cal, photos.get(i).sortTime);
                int end = i + 1;
                while (end < photos.size() && mode.groupKey(cal, photos.get(end).sortTime) == key) end++;
                String label = mode.label(fmt, photos.get(i).sortTime);
                items.add(GalleryItem.header(label, end - i));
                for (int j = i; j < end; j++) items.add(GalleryItem.photo(photos.get(j), j));
                i = end;
            }
            Gallery g = new Gallery(items, photos, mode);
            main.post(() -> {
                if (gen == generation) gallery.setValue(g);
            });
        });
    }

    @Override
    protected void onCleared() {
        main.removeCallbacks(rebuild);
        builder.shutdownNow();
    }
}
