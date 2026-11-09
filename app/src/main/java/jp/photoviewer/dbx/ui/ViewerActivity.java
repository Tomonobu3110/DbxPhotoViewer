package jp.photoviewer.dbx.ui;

import androidx.activity.EdgeToEdge;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import android.content.ActivityNotFoundException;
import android.graphics.Color;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.appbar.MaterialToolbar;

import java.io.File;
import java.util.Collections;
import java.util.List;

import jp.photoviewer.dbx.App;
import jp.photoviewer.dbx.R;
import jp.photoviewer.dbx.data.PhotoEntity;

/** 1枚表示。左右スワイプで前後へ、タップで情報バーの表示切替。 */
public class ViewerActivity extends AppCompatActivity implements ViewerAdapter.Host {

    private static final String EXTRA_POSITION = "position";
    private static final String STATE_POSITION = "state_position";

    /** グリッドと同じ並びのリスト。Intent に載せるには大きすぎるのでメモリで受け渡す。 */
    private static List<PhotoEntity> pendingPhotos = Collections.emptyList();

    private List<PhotoEntity> photos;
    private ViewPager2 pager;
    private View topBar;
    private View infoBar;
    private TextView infoName;
    private TextView infoDate;
    private TextView infoPath;
    private MaterialToolbar toolbar;
    private boolean overlayVisible = true;
    private ActivityResultLauncher<Intent> slideshowLauncher;

    /** 現在表示中の写真について、共有できる画像ファイルが用意できていれば保持する。 */
    private String readyPhotoId;
    private File readyFile;

    public static void start(Context context, List<PhotoEntity> photos, int position) {
        pendingPhotos = photos;
        Intent i = new Intent(context, ViewerActivity.class);
        i.putExtra(EXTRA_POSITION, position);
        context.startActivity(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // targetSdk 36 では常に全画面(edge-to-edge)表示。余白はレイアウトの fitsSystemWindows で確保する
        EdgeToEdge.enable(this,
                SystemBarStyle.dark(Color.TRANSPARENT),
                SystemBarStyle.dark(Color.TRANSPARENT));
        photos = pendingPhotos;
        if (photos.isEmpty()) {
            // プロセス再生成などでリストが失われた
            finish();
            return;
        }
        setContentView(R.layout.activity_viewer);

        slideshowLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != RESULT_OK || result.getData() == null) return;
                    String id = result.getData().getStringExtra(SlideshowActivity.EXTRA_RESULT_PHOTO_ID);
                    if (id == null) return;
                    // スライドショーが Dropbox の更新を取り込んで一覧を入れ替えている場合があるので、
                    // 位置ではなく ID でこの画面のリスト内を探す。
                    for (int i = 0; i < photos.size(); i++) {
                        if (photos.get(i).id.equals(id)) {
                            pager.setCurrentItem(i, false);
                            break;
                        }
                    }
                });

        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        toolbar.setNavigationOnClickListener(v -> finish());

        topBar = findViewById(R.id.top_bar);
        infoBar = findViewById(R.id.info_bar);
        infoName = findViewById(R.id.info_name);
        infoDate = findViewById(R.id.info_date);
        infoPath = findViewById(R.id.info_path);

        pager = findViewById(R.id.pager);
        pager.setOffscreenPageLimit(1);
        pager.setAdapter(new ViewerAdapter(photos, this));
        int start = savedInstanceState != null
                ? savedInstanceState.getInt(STATE_POSITION)
                : getIntent().getIntExtra(EXTRA_POSITION, 0);
        start = Math.max(0, Math.min(photos.size() - 1, start));
        pager.setCurrentItem(start, false);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                updateReadyState(position);
                invalidateOptionsMenu();
                showInfo(position);
            }
        });
        updateReadyState(start);
        showInfo(start);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (pager != null) outState.putInt(STATE_POSITION, pager.getCurrentItem());
    }

    /**
     * 表示中ページのプレビューがすでにキャッシュ済みなら共有可能にする。
     * ViewPager2 は offscreenPageLimit で先読み済みのページを再バインドしないため、
     * 読み込み完了時のコールバック(onPreviewReady)だけでは、既に読み込み済みのページに
     * スワイプで戻った/進んだ場合に共有が有効化されないことがある。
     */
    private void updateReadyState(int position) {
        PhotoEntity p = photos.get(position);
        File cached = p.isVideo ? null : App.get().previews().getCachedFile(p);
        readyPhotoId = cached != null ? p.id : null;
        readyFile = cached;
    }

    private void showInfo(int position) {
        PhotoEntity p = photos.get(position);
        toolbar.setTitle((position + 1) + " / " + photos.size());
        infoName.setText(p.name);
        String date = Formats.dateTime(p.sortTime);
        if (p.timeTaken == null) date += "(更新日時)";
        if (p.isVideo && p.durationMs != null) date += " ・ " + Formats.duration(p.durationMs);
        infoDate.setText(date);
        infoPath.setText(p.parentDisplay());
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_viewer, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        // 画像の読み込みが終わるまで(動画のページでは常に)共有メニューをグレーアウトする
        boolean ready = readyFile != null
                && readyPhotoId != null
                && readyPhotoId.equals(photos.get(pager.getCurrentItem()).id);
        menu.findItem(R.id.menu_share).setEnabled(ready);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_slideshow) {
            SlideshowActivity.start(this, slideshowLauncher, photos, pager.getCurrentItem());
            return true;
        }
        if (item.getItemId() == R.id.menu_share) {
            sharePhoto();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /** ビューアに表示中の画像ファイル(キャッシュ済みプレビュー)をそのまま他アプリに渡す。 */
    private void sharePhoto() {
        if (readyFile == null) return;
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".fileprovider", readyFile);
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("image/jpeg");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(intent, null));
    }

    @Override
    public void onPreviewReady(PhotoEntity photo, File file) {
        if (pager.getCurrentItem() < 0 || pager.getCurrentItem() >= photos.size()) return;
        if (!photos.get(pager.getCurrentItem()).id.equals(photo.id)) return;
        readyPhotoId = photo.id;
        readyFile = file;
        invalidateOptionsMenu();
    }

    @Override
    public void onPageTapped() {
        overlayVisible = !overlayVisible;
        int v = overlayVisible ? View.VISIBLE : View.GONE;
        topBar.setVisibility(v);
        infoBar.setVisibility(v);
    }

    /** 一時リンク(4時間有効)を取得して、端末の動画プレーヤーで開く。 */
    @Override
    public void onPlayVideo(PhotoEntity photo) {
        Toast.makeText(this, "動画を準備中…", Toast.LENGTH_SHORT).show();
        App.get().io().execute(() -> {
            try {
                String link = App.get().dropbox().requireClient()
                        .files().getTemporaryLink(photo.id).getLink();
                runOnUiThread(() -> {
                    Intent i = new Intent(Intent.ACTION_VIEW);
                    i.setDataAndType(Uri.parse(link), "video/*");
                    try {
                        startActivity(i);
                    } catch (ActivityNotFoundException e) {
                        Toast.makeText(this, "動画を再生できるアプリがありません", Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "動画のリンクを取得できませんでした", Toast.LENGTH_LONG).show());
            }
        });
    }
}
