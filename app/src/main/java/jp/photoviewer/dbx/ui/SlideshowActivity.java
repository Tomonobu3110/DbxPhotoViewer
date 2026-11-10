package jp.photoviewer.dbx.ui;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.SystemBarStyle;
import androidx.activity.result.ActivityResultLauncher;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jp.photoviewer.dbx.App;
import jp.photoviewer.dbx.R;
import jp.photoviewer.dbx.data.PhotoEntity;
import jp.photoviewer.dbx.dropbox.SyncManager;
import jp.photoviewer.dbx.image.PreviewLoader;

/**
 * 現在の写真から過去方向へ最大60枚をスライドショー表示する。
 * 静止画・動画ともに1枚30秒。動画は無音・ループ再生。
 * 60枚終わったら Dropbox を同期し、最新の写真・動画を起点に次の60枚を再生する(無限ループ)。
 *
 * ランダムモード(isRandomMode)では、60枚は「過去方向30枚」と「全期間からランダムに選んだ30枚」を
 * 交互に並べたものになる。ループ時も同様に、最新30枚+新たに選んだランダム30枚で作り直す。
 */
public class SlideshowActivity extends AppCompatActivity {

    /** 停止時に返す、現在表示中の写真・動画のID(呼び出し元のリストでの位置を探すため)。 */
    public static final String EXTRA_RESULT_PHOTO_ID = "result_photo_id";
    private static final String EXTRA_START_POSITION = "start_position";
    private static final String EXTRA_RANDOM = "random";
    private static final String STATE_CURRENT_SLIDE = "state_current_slide";

    private static final int MAX_SLIDES = 60;
    /** ランダムモードでの、過去方向側・ランダム側それぞれの枚数。 */
    private static final int HALF_SLIDES = 30;
    private static final long SLIDE_DURATION_MS = 30_000;
    private static final long ERROR_SKIP_MS = 2_000;
    private static final long BAR_TIMEOUT_MS = 10_000;
    private static final long BAR_SLIDE_MS = 250;
    private static final long PHOTO_FADE_MS = 500;
    private static final int MAX_DISPLAY_SIDE = 2048;

    /** グリッドと同じ並びのリスト。Intent に載せるには大きすぎるのでメモリで受け渡す。 */
    private static List<PhotoEntity> pendingPhotos = Collections.emptyList();

    private List<PhotoEntity> slides;
    private int startPosition;
    private boolean isRandomMode;
    private int currentSlide;
    private int requestGen;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable advanceRunnable = this::advance;
    private final Runnable hideBarRunnable = this::hideBottomBar;
    private boolean syncSeenRunning;
    @Nullable
    private Observer<SyncManager.State> syncObserver;

    /** 次スライド用に先読み中/済みのデータの対象インデックス。slides 内の位置。 */
    private int prefetchIndex = -1;
    @Nullable
    private Bitmap prefetchedBitmap;
    @Nullable
    private String prefetchedVideoLink;
    /** スライド一覧の入れ替えや終了時にインクリメントし、進行中の先読みの結果を無効化する。 */
    private int prefetchEpoch;

    private ImageView photoA;
    private ImageView photoB;
    /** 現在画面に表示中(または表示完了直前)の静止画 ImageView。静止画を表示していなければ null。 */
    private ImageView activePhoto;
    /** 直前のスライドが静止画の表示に成功していたか。true のときだけ次の静止画とクロスフェードする。 */
    private boolean previousWasPhoto;
    private VideoView videoView;
    private ProgressBar progress;
    private TextView error;
    private TextView counter;
    private View bottomBar;

    public static void start(Context context, ActivityResultLauncher<Intent> launcher,
                              List<PhotoEntity> photos, int position, boolean random) {
        pendingPhotos = photos;
        Intent i = new Intent(context, SlideshowActivity.class);
        i.putExtra(EXTRA_START_POSITION, position);
        i.putExtra(EXTRA_RANDOM, random);
        launcher.launch(i);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this,
                SystemBarStyle.dark(Color.TRANSPARENT),
                SystemBarStyle.dark(Color.TRANSPARENT));

        List<PhotoEntity> photos = pendingPhotos;
        startPosition = getIntent().getIntExtra(EXTRA_START_POSITION, 0);
        isRandomMode = getIntent().getBooleanExtra(EXTRA_RANDOM, false);
        if (photos.isEmpty() || startPosition < 0 || startPosition >= photos.size()) {
            finish();
            return;
        }

        setContentView(R.layout.activity_slideshow);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        photoA = findViewById(R.id.photo_view);
        photoB = findViewById(R.id.photo_view2);
        videoView = findViewById(R.id.video_view);
        progress = findViewById(R.id.progress);
        error = findViewById(R.id.error);
        counter = findViewById(R.id.counter);
        bottomBar = findViewById(R.id.bottom_bar);
        findViewById(R.id.btn_stop).setOnClickListener(v -> stopSlideshow());
        findViewById(R.id.root).setOnClickListener(v -> showBottomBar());

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                stopSlideshow();
            }
        });

        int start = savedInstanceState != null ? savedInstanceState.getInt(STATE_CURRENT_SLIDE) : 0;
        if (isRandomMode) {
            int seqCount = Math.min(HALF_SLIDES, photos.size() - startPosition);
            List<PhotoEntity> sequential = new ArrayList<>(
                    photos.subList(startPosition, startPosition + seqCount));
            progress.setVisibility(View.VISIBLE);
            loadRandomHalfAndStart(sequential, start);
        } else {
            int count = Math.min(MAX_SLIDES, photos.size() - startPosition);
            slides = new ArrayList<>(photos.subList(startPosition, startPosition + count));
            start = Math.max(0, Math.min(slides.size() - 1, start));
            showSlide(start);
        }
        showBottomBar();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_CURRENT_SLIDE, currentSlide);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        handler.removeCallbacksAndMessages(null);
        ++prefetchEpoch;
        discardPrefetch();
        videoView.stopPlayback();
        clearAndRecycle(photoA);
        clearAndRecycle(photoB);
    }

    private void showBottomBar() {
        bottomBar.animate().cancel();
        if (bottomBar.getVisibility() != View.VISIBLE) {
            bottomBar.setVisibility(View.VISIBLE);
            bottomBar.setTranslationY(bottomBar.getHeight());
        }
        bottomBar.animate().translationY(0).setDuration(BAR_SLIDE_MS).start();
        handler.removeCallbacks(hideBarRunnable);
        handler.postDelayed(hideBarRunnable, BAR_TIMEOUT_MS);
    }

    private void hideBottomBar() {
        bottomBar.animate().cancel();
        bottomBar.animate().translationY(bottomBar.getHeight())
                .setDuration(BAR_SLIDE_MS)
                .withEndAction(() -> bottomBar.setVisibility(View.GONE))
                .start();
    }

    private void showSlide(int slideIndex) {
        handler.removeCallbacks(advanceRunnable);
        photoA.animate().cancel();
        photoB.animate().cancel();
        error.setVisibility(View.GONE);

        currentSlide = slideIndex;
        PhotoEntity p = slides.get(slideIndex);
        counter.setText((slideIndex + 1) + " / " + slides.size());
        int gen = ++requestGen;

        if (!tryShowPrefetched(slideIndex, p, gen)) {
            progress.setVisibility(View.VISIBLE);
            if (p.isVideo) {
                loadVideoFresh(gen, p);
            } else {
                loadPhotoFresh(gen, p);
            }
        }

        // 今表示したスライドの間に、次のスライドの動画リンク取得・画像デコードを済ませておく。
        prefetchNext(slideIndex);
    }

    /** 先読み済みのデータが使えればそれを表示する。表示できた場合 true を返す。 */
    private boolean tryShowPrefetched(int slideIndex, PhotoEntity p, int gen) {
        if (prefetchIndex != slideIndex) return false;

        if (p.isVideo) {
            if (prefetchedVideoLink == null) {
                discardPrefetch();
                return false;
            }
            String link = prefetchedVideoLink;
            resetPrefetchFields();
            hidePhotoViewsNow();
            videoView.stopPlayback();
            videoView.setVisibility(View.VISIBLE);
            progress.setVisibility(View.VISIBLE);
            playVideo(gen, link);
            return true;
        } else {
            if (prefetchedBitmap == null) {
                discardPrefetch();
                return false;
            }
            Bitmap bm = prefetchedBitmap;
            resetPrefetchFields();
            videoView.stopPlayback();
            videoView.setVisibility(View.GONE);
            progress.setVisibility(View.GONE);
            showPhoto(bm);
            scheduleAdvance(SLIDE_DURATION_MS);
            return true;
        }
    }

    private void loadVideoFresh(int gen, PhotoEntity p) {
        // 動画への切り替えはクロスフェードせず即切り替え。
        hidePhotoViewsNow();
        videoView.stopPlayback();
        // VideoView(内部のSurfaceView)は visibility=GONE のままだと Surface が生成されず
        // 再生準備が進まないため、再生開始前から VISIBLE にしておく(見た目はプログレスで覆う)。
        videoView.setVisibility(View.VISIBLE);
        App.get().io().execute(() -> {
            try {
                String link = App.get().dropbox().requireClient()
                        .files().getTemporaryLink(p.id).getLink();
                runOnUiThread(() -> {
                    if (gen != requestGen) return;
                    playVideo(gen, link);
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (gen != requestGen) return;
                    showErrorAndSkip("動画のリンクを取得できませんでした");
                });
            }
        });
    }

    private void loadPhotoFresh(int gen, PhotoEntity p) {
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE);
        App.get().previews().load(p, new PreviewLoader.Callback() {
            @Override
            public void onLoaded(File file) {
                if (gen != requestGen) return;
                App.get().io().execute(() -> {
                    Bitmap bm = decodeForDisplay(file);
                    runOnUiThread(() -> {
                        if (gen != requestGen) {
                            if (bm != null) bm.recycle();
                            return;
                        }
                        if (bm == null) {
                            showErrorAndSkip("画像を表示できません");
                            return;
                        }
                        progress.setVisibility(View.GONE);
                        showPhoto(bm);
                        scheduleAdvance(SLIDE_DURATION_MS);
                    });
                });
            }

            @Override
            public void onError(String message) {
                if (gen != requestGen) return;
                showErrorAndSkip(message);
            }
        });
    }

    /**
     * 次のスライド(slideIndex + 1)の表示に必要なデータを先読みしておく。
     * 動画は一時リンクの取得、静止画は表示用 Bitmap へのデコードまで済ませる。
     * 60枚目から1枚目への折り返しは対象外(例外として許容)。
     */
    private void prefetchNext(int slideIndex) {
        int nextIndex = slideIndex + 1;
        if (nextIndex >= slides.size()) return;
        if (prefetchIndex == nextIndex) return;
        discardPrefetch();
        prefetchIndex = nextIndex;
        final int epoch = prefetchEpoch;
        PhotoEntity p = slides.get(nextIndex);

        if (p.isVideo) {
            App.get().io().execute(() -> {
                String link;
                try {
                    link = App.get().dropbox().requireClient()
                            .files().getTemporaryLink(p.id).getLink();
                } catch (Exception e) {
                    return; // 失敗時は showSlide 側で通常通り取得し直す
                }
                runOnUiThread(() -> {
                    if (epoch != prefetchEpoch || prefetchIndex != nextIndex) return;
                    prefetchedVideoLink = link;
                });
            });
        } else {
            App.get().previews().load(p, new PreviewLoader.Callback() {
                @Override
                public void onLoaded(File file) {
                    if (epoch != prefetchEpoch || prefetchIndex != nextIndex) return;
                    App.get().io().execute(() -> {
                        Bitmap bm = decodeForDisplay(file);
                        runOnUiThread(() -> {
                            if (epoch != prefetchEpoch || prefetchIndex != nextIndex || bm == null) {
                                if (bm != null) bm.recycle();
                                return;
                            }
                            prefetchedBitmap = bm;
                        });
                    });
                }

                @Override
                public void onError(String message) {
                    // 失敗時は showSlide 側で通常通り取得し直す
                }
            });
        }
    }

    private void resetPrefetchFields() {
        prefetchIndex = -1;
        prefetchedBitmap = null;
        prefetchedVideoLink = null;
    }

    /** 先読み結果を使わずに捨てる(Bitmap があれば recycle する)。 */
    private void discardPrefetch() {
        if (prefetchedBitmap != null) prefetchedBitmap.recycle();
        resetPrefetchFields();
    }

    private void playVideo(int gen, String link) {
        videoView.setVideoURI(Uri.parse(link));
        videoView.setOnPreparedListener(mp -> {
            if (gen != requestGen) return;
            mp.setVolume(0f, 0f);
            mp.setLooping(true);
            progress.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            videoView.start();
            scheduleAdvance(SLIDE_DURATION_MS);
        });
        videoView.setOnErrorListener((mp, what, extra) -> {
            if (gen == requestGen) showErrorAndSkip("動画を再生できません");
            return true;
        });
    }

    /** 静止画を表示する。直前も静止画だった場合だけクロスフェードする。 */
    private void showPhoto(Bitmap bm) {
        if (previousWasPhoto && activePhoto != null) {
            ImageView incoming = (activePhoto == photoA) ? photoB : photoA;
            ImageView outgoing = activePhoto;
            incoming.setImageBitmap(bm);
            incoming.setAlpha(0f);
            incoming.setVisibility(View.VISIBLE);
            incoming.animate().alpha(1f).setDuration(PHOTO_FADE_MS).start();
            outgoing.animate().alpha(0f).setDuration(PHOTO_FADE_MS)
                    .withEndAction(() -> {
                        outgoing.setVisibility(View.GONE);
                        clearAndRecycle(outgoing);
                    }).start();
            activePhoto = incoming;
        } else {
            ImageView target = activePhoto != null ? activePhoto : photoA;
            ImageView other = (target == photoA) ? photoB : photoA;
            other.setVisibility(View.GONE);
            clearAndRecycle(other);
            setBitmapRecyclingOld(target, bm);
            target.setAlpha(1f);
            target.setVisibility(View.VISIBLE);
            activePhoto = target;
        }
        previousWasPhoto = true;
    }

    /** 動画・エラー表示に切り替える前に、静止画をフェードなしで即座に隠す。 */
    private void hidePhotoViewsNow() {
        photoA.setVisibility(View.GONE);
        photoB.setVisibility(View.GONE);
        activePhoto = null;
        previousWasPhoto = false;
    }

    private static void setBitmapRecyclingOld(ImageView view, Bitmap bm) {
        Drawable old = view.getDrawable();
        view.setImageBitmap(bm);
        if (old instanceof BitmapDrawable) {
            Bitmap oldBm = ((BitmapDrawable) old).getBitmap();
            if (oldBm != null && oldBm != bm && !oldBm.isRecycled()) oldBm.recycle();
        }
    }

    private static void clearAndRecycle(ImageView view) {
        Drawable d = view.getDrawable();
        view.setImageDrawable(null);
        if (d instanceof BitmapDrawable) {
            Bitmap bm = ((BitmapDrawable) d).getBitmap();
            if (bm != null && !bm.isRecycled()) bm.recycle();
        }
    }

    private void showErrorAndSkip(String message) {
        hidePhotoViewsNow();
        progress.setVisibility(View.GONE);
        error.setText(message);
        error.setVisibility(View.VISIBLE);
        scheduleAdvance(ERROR_SKIP_MS);
    }

    private void scheduleAdvance(long delayMs) {
        handler.postDelayed(advanceRunnable, delayMs);
    }

    private void advance() {
        int next = currentSlide + 1;
        if (next >= slides.size()) {
            restartFromLatest();
        } else {
            showSlide(next);
        }
    }

    /** 60枚終わったら Dropbox を同期し、最新の一覧を取得してまた先頭(最新)から60枚始める。 */
    private void restartFromLatest() {
        handler.removeCallbacks(advanceRunnable);
        ++prefetchEpoch;
        discardPrefetch();
        hidePhotoViewsNow();
        videoView.stopPlayback();
        videoView.setVisibility(View.GONE);
        error.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        final int gen = ++requestGen;

        if (syncObserver != null) App.get().sync().state().removeObserver(syncObserver);
        syncSeenRunning = false;
        syncObserver = state -> {
            if (state.running) {
                syncSeenRunning = true;
            } else if (syncSeenRunning) {
                App.get().sync().state().removeObserver(syncObserver);
                syncObserver = null;
                reloadLatestAndRestart(gen);
            }
        };
        App.get().sync().state().observe(this, syncObserver);
        App.get().sync().requestSync();
    }

    /**
     * 同期完了後、DB から最新の一覧を取得して先頭(最新)から新たに最大60枚を始める。
     * ランダムモードでは「最新30枚」+新たに選んだ「ランダム30枚」で作り直す。
     */
    private void reloadLatestAndRestart(int gen) {
        App.get().io().execute(() -> {
            int excludeSmall = App.get().prefs().isExcludeSmall() ? 1 : 0;
            List<PhotoEntity> fresh;
            if (isRandomMode) {
                List<PhotoEntity> latest = App.get().database().photos()
                        .listOnce(excludeSmall, HALF_SLIDES);
                List<PhotoEntity> random = App.get().database().photos()
                        .randomSample(excludeSmall, idsOf(latest), HALF_SLIDES);
                fresh = interleave(latest, random);
            } else {
                fresh = App.get().database().photos().listOnce(excludeSmall, MAX_SLIDES);
            }
            runOnUiThread(() -> {
                if (gen != requestGen) return;
                if (fresh.isEmpty()) {
                    stopSlideshow();
                    return;
                }
                slides = fresh;
                showSlide(0);
            });
        });
    }

    /**
     * ランダムモードの初回開始用。渡された過去方向30枚に加えて、DB から全期間のランダム30枚を
     * (過去方向30枚と重複しないように)取得し、交互に並べてから最初のスライドを表示する。
     */
    private void loadRandomHalfAndStart(List<PhotoEntity> sequential, int start) {
        final int gen = requestGen;
        App.get().io().execute(() -> {
            int excludeSmall = App.get().prefs().isExcludeSmall() ? 1 : 0;
            List<PhotoEntity> random = App.get().database().photos()
                    .randomSample(excludeSmall, idsOf(sequential), HALF_SLIDES);
            runOnUiThread(() -> {
                if (gen != requestGen) return;
                slides = interleave(sequential, random);
                showSlide(Math.max(0, Math.min(slides.size() - 1, start)));
            });
        });
    }

    private static List<String> idsOf(List<PhotoEntity> photos) {
        List<String> ids = new ArrayList<>(photos.size());
        for (PhotoEntity p : photos) ids.add(p.id);
        return ids;
    }

    /** 2つのリストを先頭から交互に並べる(a[0], b[0], a[1], b[1], ...)。長さが違う場合は残りをそのまま続ける。 */
    private static List<PhotoEntity> interleave(List<PhotoEntity> a, List<PhotoEntity> b) {
        List<PhotoEntity> result = new ArrayList<>(a.size() + b.size());
        int max = Math.max(a.size(), b.size());
        for (int i = 0; i < max; i++) {
            if (i < a.size()) result.add(a.get(i));
            if (i < b.size()) result.add(b.get(i));
        }
        return result;
    }

    private void stopSlideshow() {
        handler.removeCallbacks(advanceRunnable);
        ++requestGen;
        ++prefetchEpoch;
        discardPrefetch();
        if (syncObserver != null) {
            App.get().sync().state().removeObserver(syncObserver);
            syncObserver = null;
        }
        videoView.stopPlayback();
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_PHOTO_ID, slides.get(currentSlide).id);
        setResult(RESULT_OK, data);
        finish();
    }

    private static Bitmap decodeForDisplay(File file) {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        int sample = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / sample > MAX_DISPLAY_SIDE) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        return BitmapFactory.decodeFile(file.getPath(), opts);
    }
}
