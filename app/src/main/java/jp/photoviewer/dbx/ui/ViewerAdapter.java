package jp.photoviewer.dbx.ui;

import android.graphics.Bitmap;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.davemorrissey.labs.subscaleview.ImageSource;
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView;

import java.io.File;
import java.util.List;
import java.util.concurrent.Future;

import jp.photoviewer.dbx.App;
import jp.photoviewer.dbx.R;
import jp.photoviewer.dbx.data.PhotoEntity;
import jp.photoviewer.dbx.image.PreviewLoader;

/** ビューアの各ページ。写真はズーム可能表示、動画はサムネイル+再生ボタン。 */
public class ViewerAdapter extends RecyclerView.Adapter<ViewerAdapter.PageHolder> {

    public interface Host {
        void onPageTapped();
        void onPlayVideo(PhotoEntity photo);
        /** ズーム表示用の画像ファイルが用意できた(共有メニューの有効化に使う)。 */
        void onPreviewReady(PhotoEntity photo, File file);
    }

    private final List<PhotoEntity> photos;
    private final Host host;

    public ViewerAdapter(List<PhotoEntity> photos, Host host) {
        this.photos = photos;
        this.host = host;
    }

    @Override
    public int getItemCount() {
        return photos.size();
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_viewer_page, parent, false);
        return new PageHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder h, int position) {
        PhotoEntity p = photos.get(position);
        h.bound = p;
        h.error.setVisibility(View.GONE);
        h.itemView.setOnClickListener(v -> host.onPageTapped());

        if (p.isVideo) {
            h.zoom.setVisibility(View.GONE);
            h.progress.setVisibility(View.GONE);
            h.placeholder.setVisibility(View.GONE);
            h.videoThumb.setVisibility(View.VISIBLE);
            h.play.setVisibility(View.VISIBLE);
            App.get().thumbnails().load(h.videoThumb, p, R.drawable.ic_video);
            h.videoThumb.setOnClickListener(v -> host.onPageTapped());
            h.play.setOnClickListener(v -> host.onPlayVideo(p));
            return;
        }

        h.videoThumb.setVisibility(View.GONE);
        h.play.setVisibility(View.GONE);
        h.zoom.setVisibility(View.VISIBLE);
        h.zoom.recycle();
        h.zoom.setOnClickListener(v -> host.onPageTapped());
        h.progress.setVisibility(View.VISIBLE);

        Bitmap thumb = App.get().thumbnails().getCachedBitmap(p);
        if (thumb != null) {
            h.placeholder.setImageBitmap(thumb);
            h.placeholder.setVisibility(View.VISIBLE);
        } else {
            h.placeholder.setVisibility(View.GONE);
        }

        h.task = App.get().previews().load(p, new PreviewLoader.Callback() {
            @Override
            public void onLoaded(File file) {
                if (h.bound != p) return;
                h.progress.setVisibility(View.GONE);
                h.placeholder.setVisibility(View.GONE);
                h.zoom.setImage(ImageSource.uri(Uri.fromFile(file)));
                host.onPreviewReady(p, file);
            }

            @Override
            public void onError(String message) {
                if (h.bound != p) return;
                h.progress.setVisibility(View.GONE);
                h.placeholder.setVisibility(View.GONE);
                h.error.setText(message);
                h.error.setVisibility(View.VISIBLE);
            }
        });
    }

    @Override
    public void onViewRecycled(@NonNull PageHolder h) {
        h.bound = null;
        if (h.task != null) {
            h.task.cancel(false);
            h.task = null;
        }
        h.zoom.recycle();
        h.placeholder.setImageDrawable(null);
        App.get().thumbnails().cancel(h.videoThumb);
    }

    static final class PageHolder extends RecyclerView.ViewHolder {
        final ImageView placeholder;
        final SubsamplingScaleImageView zoom;
        final ImageView videoThumb;
        final View play;
        final ProgressBar progress;
        final TextView error;
        PhotoEntity bound;
        Future<?> task;

        PageHolder(View v) {
            super(v);
            placeholder = v.findViewById(R.id.placeholder);
            zoom = v.findViewById(R.id.zoom);
            videoThumb = v.findViewById(R.id.video_thumb);
            play = v.findViewById(R.id.play);
            progress = v.findViewById(R.id.progress);
            error = v.findViewById(R.id.error);
            zoom.setMaxScale(6f);
        }
    }
}
