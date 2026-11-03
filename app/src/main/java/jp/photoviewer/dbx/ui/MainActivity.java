package jp.photoviewer.dbx.ui;

import androidx.activity.EdgeToEdge;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;

import jp.photoviewer.dbx.App;
import jp.photoviewer.dbx.Prefs;
import jp.photoviewer.dbx.R;
import jp.photoviewer.dbx.dropbox.DropboxClientProvider;
import jp.photoviewer.dbx.dropbox.SyncManager;

/** 写真グリッド(公式アプリの「写真」タブ相当)。未ログイン時は接続画面を出す。 */
public class MainActivity extends AppCompatActivity {

    private static final long AUTO_SYNC_INTERVAL_MS = 60_000;
    /** 先読みするサムネイルの件数(最新から)。 */
    private static final int PREFETCH_COUNT = 100;

    private DropboxClientProvider dropbox;
    private SyncManager sync;
    private Prefs prefs;
    private GalleryViewModel viewModel;
    private GalleryAdapter adapter;
    private GridLayoutManager layoutManager;

    private MaterialToolbar toolbar;
    private SwipeRefreshLayout swipe;
    private RecyclerView recycler;
    private View loginPanel;
    private TextView emptyText;
    private LinearProgressIndicator syncProgress;

    private GalleryViewModel.Gallery currentGallery;
    private SyncManager.State lastState;
    private String lastShownError;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // targetSdk 36 では常に全画面(edge-to-edge)表示。余白はレイアウトの fitsSystemWindows で確保する
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        App app = App.get();
        dropbox = app.dropbox();
        sync = app.sync();
        prefs = app.prefs();
        viewModel = new ViewModelProvider(this).get(GalleryViewModel.class);

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        swipe = findViewById(R.id.swipe);
        recycler = findViewById(R.id.recycler);
        loginPanel = findViewById(R.id.login_panel);
        emptyText = findViewById(R.id.empty_text);
        syncProgress = findViewById(R.id.sync_progress);
        findViewById(R.id.login_button).setOnClickListener(v -> startLogin());

        adapter = new GalleryAdapter(app.thumbnails(), this::openViewer);
        layoutManager = new GridLayoutManager(this, prefs.getColumns());
        layoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                if (position < 0 || position >= adapter.getItemCount()) return 1;
                return adapter.getItemViewType(position) == GalleryItem.TYPE_HEADER
                        ? layoutManager.getSpanCount() : 1;
            }
        });
        recycler.setLayoutManager(layoutManager);
        recycler.setAdapter(adapter);
        recycler.setItemAnimator(null);
        setupPinch();

        swipe.setOnRefreshListener(() -> {
            if (dropbox.isLoggedIn()) sync.requestSync();
            else swipe.setRefreshing(false);
        });

        viewModel.gallery().observe(this, g -> {
            GalleryViewModel.Gallery previous = currentGallery;
            currentGallery = g;
            boolean modeChanged = previous != null && previous.mode != g.mode;
            if (modeChanged) invalidateOptionsMenu();

            if (modeChanged) {
                // 表示単位の切り替えでは見出しが総入れ替えになり、差分計算(DiffUtil)が非常に重くなる。
                // 差分計算をせずに一度空にしてから新しいリストを入れ、見ていた写真の位置へ戻す。
                String anchorId = firstVisiblePhotoId();
                adapter.submitList(null);
                adapter.submitList(g.items, () -> scrollToPhoto(g, anchorId));
            } else {
                // 先頭(最新)を見ている間は、同期で並びが変わっても先頭に留まる
                boolean atTop = !recycler.canScrollVertically(-1);
                adapter.submitList(g.items, () -> {
                    if (atTop) recycler.scrollToPosition(0);
                });
            }
            // 最新の写真から順にサムネイルを先読みする
            App.get().thumbnails().prefetch(g.photos, PREFETCH_COUNT);
            updateEmpty();
        });
        sync.state().observe(this, this::onSyncState);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (dropbox.handleAuthResult()) {
            sync.requestSync();
        }
        viewModel.reloadFilter();
        updateLoginUi();
        if (dropbox.isLoggedIn()) sync.syncIfStale(AUTO_SYNC_INTERVAL_MS);
    }

    /** ピンチ操作で1行に並べるサムネイルの数を変える(年/月/日のグループ化は変えない)。 */
    private void changeColumns(int delta) {
        int next = Math.max(Prefs.MIN_COLUMNS, Math.min(Prefs.MAX_COLUMNS, layoutManager.getSpanCount() + delta));
        if (next == layoutManager.getSpanCount()) return;
        prefs.setColumns(next);
        layoutManager.setSpanCount(next);
    }

    /** 画面に見えている最初の写真のID(無ければ null)。 */
    private String firstVisiblePhotoId() {
        int first = layoutManager.findFirstVisibleItemPosition();
        if (first == RecyclerView.NO_POSITION) return null;
        int count = adapter.getItemCount();
        for (int i = first; i < Math.min(count, first + 16); i++) {
            GalleryItem item = adapter.getCurrentList().get(i);
            if (item.photo != null) return item.photo.id;
        }
        return null;
    }

    /** 新しいリストの中で、指定した写真が先頭に来るようにスクロールする。 */
    private void scrollToPhoto(GalleryViewModel.Gallery g, String photoId) {
        if (photoId == null) {
            recycler.scrollToPosition(0);
            return;
        }
        for (int i = 0; i < g.items.size(); i++) {
            GalleryItem item = g.items.get(i);
            if (item.photo != null && item.photo.id.equals(photoId)) {
                // 直前の見出しごと見えるように、見出しがあればそこへ
                int pos = (i > 0 && g.items.get(i - 1).type == GalleryItem.TYPE_HEADER) ? i - 1 : i;
                layoutManager.scrollToPositionWithOffset(pos, 0);
                return;
            }
        }
        recycler.scrollToPosition(0);
    }

    private void updateLoginUi() {
        boolean loggedIn = dropbox.isLoggedIn();
        loginPanel.setVisibility(loggedIn ? View.GONE : View.VISIBLE);
        swipe.setVisibility(loggedIn ? View.VISIBLE : View.GONE);
        updateSubtitle();
        updateEmpty();
        invalidateOptionsMenu();
    }

    private void updateEmpty() {
        boolean loggedIn = dropbox.isLoggedIn();
        boolean empty = currentGallery == null || currentGallery.items.isEmpty();
        boolean running = lastState != null && lastState.running;
        emptyText.setVisibility(loggedIn && empty && !running ? View.VISIBLE : View.GONE);
    }

    private void updateSubtitle() {
        if (!dropbox.isLoggedIn()) {
            toolbar.setSubtitle(null);
            return;
        }
        String folder = prefs.getFolderDisplay() + (prefs.isRecursive() ? "(サブフォルダ含む)" : "");
        if (lastState != null && (lastState.running || lastState.background) && lastState.message != null) {
            toolbar.setSubtitle(folder + " ・ " + lastState.message);
        } else {
            toolbar.setSubtitle(folder);
        }
    }

    private void onSyncState(SyncManager.State state) {
        lastState = state;
        if (!state.running) swipe.setRefreshing(false);
        updateProgressBar(state);
        updateSubtitle();
        updateEmpty();
        if (state.error && state.message != null && !state.message.equals(lastShownError)) {
            lastShownError = state.message;
            Snackbar bar = Snackbar.make(recycler, state.message, Snackbar.LENGTH_LONG);
            if (state.authError) bar.setAction("再ログイン", v -> startLogin());
            bar.show();
        } else if (!state.error) {
            lastShownError = null;
        }
    }

    /** くるくる表示中だけ、ツールバー直下の細い進捗バーを出す。 */
    private void updateProgressBar(SyncManager.State state) {
        if (!state.running) {
            syncProgress.setVisibility(View.GONE);
            return;
        }
        boolean indeterminate = state.max <= 0;
        if (syncProgress.isIndeterminate() != indeterminate) {
            // 表示中は不確定/確定の切り替えが効かないため、一度隠してから切り替える
            syncProgress.setVisibility(View.GONE);
            syncProgress.setIndeterminate(indeterminate);
        }
        if (!indeterminate) {
            syncProgress.setMax(state.max);
            syncProgress.setProgressCompat(Math.min(state.progress, state.max), true);
        }
        syncProgress.setVisibility(View.VISIBLE);
    }

    private void startLogin() {
        if (!dropbox.hasAppKey()) {
            new AlertDialog.Builder(this)
                    .setTitle("App key が未設定です")
                    .setMessage("local.properties に dropbox.appKey=(Dropbox の App key) を書いて、ビルドし直してください。")
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        dropbox.startLogin(this);
    }

    private void openViewer(GalleryItem item) {
        if (currentGallery == null) return;
        ViewerActivity.start(this, currentGallery.photos, item.photoIndex);
    }

    /** ピンチアウトで細かい単位、ピンチインで粗い単位に切り替える。 */
    @SuppressLint("ClickableViewAccessibility")
    private void setupPinch() {
        final float[] scale = {1f};
        // 2本指のピンチだけを対象にする(3本指のスクリーンショット操作などで切り替わらないように)
        final boolean[] twoFingers = {false};
        ScaleGestureDetector detector = new ScaleGestureDetector(this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override
                    public boolean onScaleBegin(@NonNull ScaleGestureDetector d) {
                        scale[0] = 1f;
                        return twoFingers[0];
                    }

                    @Override
                    public boolean onScale(@NonNull ScaleGestureDetector d) {
                        if (!twoFingers[0]) return true;
                        scale[0] *= d.getScaleFactor();
                        // ピンチアウトで列を減らして大きく、ピンチインで列を増やして小さく。
                        // 指を離さなくても、一定量広げる/狭めるごとに1段ずつ変わる
                        if (scale[0] > 1.3f) {
                            changeColumns(-1);
                            scale[0] = 1f;
                        } else if (scale[0] < 0.77f) {
                            changeColumns(+1);
                            scale[0] = 1f;
                        }
                        return true;
                    }
                });
        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                trackPointers(e);
                detector.onTouchEvent(e);
                return detector.isInProgress();
            }

            /** 一連のタッチ操作で、指の本数が最大2本だったかを記録する。 */
            private void trackPointers(MotionEvent e) {
                int action = e.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    twoFingers[0] = false;
                } else if (action == MotionEvent.ACTION_POINTER_DOWN) {
                    twoFingers[0] = e.getPointerCount() == 2;
                }
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                trackPointers(e);
                detector.onTouchEvent(e);
            }
        });
    }

    // ---- メニュー ----

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        boolean loggedIn = dropbox.isLoggedIn();
        menu.findItem(R.id.menu_group).setVisible(loggedIn);
        menu.findItem(R.id.menu_media).setVisible(loggedIn);
        menu.findItem(R.id.menu_refresh).setVisible(loggedIn);

        switch (viewModel.getGroupMode()) {
            case YEAR: menu.findItem(R.id.group_year).setChecked(true); break;
            case MONTH: menu.findItem(R.id.group_month).setChecked(true); break;
            default: menu.findItem(R.id.group_day).setChecked(true); break;
        }
        switch (viewModel.getMediaFilter()) {
            case Prefs.MEDIA_PHOTO: menu.findItem(R.id.media_photo).setChecked(true); break;
            case Prefs.MEDIA_VIDEO: menu.findItem(R.id.media_video).setChecked(true); break;
            default: menu.findItem(R.id.media_all).setChecked(true); break;
        }
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.group_day) {
            viewModel.setGroupMode(GroupMode.DAY);
        } else if (id == R.id.group_month) {
            viewModel.setGroupMode(GroupMode.MONTH);
        } else if (id == R.id.group_year) {
            viewModel.setGroupMode(GroupMode.YEAR);
        } else if (id == R.id.media_all) {
            viewModel.setMediaFilter(Prefs.MEDIA_ALL);
        } else if (id == R.id.media_photo) {
            viewModel.setMediaFilter(Prefs.MEDIA_PHOTO);
        } else if (id == R.id.media_video) {
            viewModel.setMediaFilter(Prefs.MEDIA_VIDEO);
        } else if (id == R.id.menu_refresh) {
            swipe.setRefreshing(true);
            sync.requestSync();
        } else if (id == R.id.menu_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
        } else {
            return super.onOptionsItemSelected(item);
        }
        invalidateOptionsMenu();
        return true;
    }
}
