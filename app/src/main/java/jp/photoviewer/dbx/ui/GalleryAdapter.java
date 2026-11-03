package jp.photoviewer.dbx.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import java.util.Objects;

import jp.photoviewer.dbx.R;
import jp.photoviewer.dbx.data.PhotoEntity;
import jp.photoviewer.dbx.image.ThumbnailLoader;

/** 日付見出しと写真セルを並べるアダプタ。 */
public class GalleryAdapter extends ListAdapter<GalleryItem, RecyclerView.ViewHolder> {

    public interface OnPhotoClickListener {
        void onPhotoClick(GalleryItem item);
    }

    private static final DiffUtil.ItemCallback<GalleryItem> DIFF = new DiffUtil.ItemCallback<GalleryItem>() {
        @Override
        public boolean areItemsTheSame(@NonNull GalleryItem a, @NonNull GalleryItem b) {
            return a.stableKey().equals(b.stableKey());
        }

        @Override
        public boolean areContentsTheSame(@NonNull GalleryItem a, @NonNull GalleryItem b) {
            return a.sameContent(b);
        }
    };

    private final ThumbnailLoader thumbnails;
    private final OnPhotoClickListener listener;

    public GalleryAdapter(ThumbnailLoader thumbnails, OnPhotoClickListener listener) {
        super(DIFF);
        this.thumbnails = thumbnails;
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        return getItem(position).type;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == GalleryItem.TYPE_HEADER) {
            return new HeaderHolder(inflater.inflate(R.layout.item_header, parent, false));
        }
        return new PhotoHolder(inflater.inflate(R.layout.item_photo, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        GalleryItem item = getItem(position);
        if (holder instanceof HeaderHolder) {
            HeaderHolder hh = (HeaderHolder) holder;
            hh.text.setText(item.header);
            hh.count.setText(item.count + " 件");
        } else {
            PhotoHolder h = (PhotoHolder) holder;
            PhotoEntity p = Objects.requireNonNull(item.photo);
            thumbnails.load(h.image, p, p.isVideo ? R.drawable.ic_video : R.drawable.ic_image);
            if (p.isVideo) {
                h.videoBadge.setVisibility(View.VISIBLE);
                h.duration.setText(p.durationMs != null ? Formats.duration(p.durationMs) : "");
            } else {
                h.videoBadge.setVisibility(View.GONE);
            }
            h.itemView.setOnClickListener(v -> {
                int pos = h.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onPhotoClick(getItem(pos));
            });
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof PhotoHolder) thumbnails.cancel(((PhotoHolder) holder).image);
    }

    static final class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView text;
        final TextView count;

        HeaderHolder(View v) {
            super(v);
            text = v.findViewById(R.id.header_text);
            count = v.findViewById(R.id.header_count);
        }
    }

    static final class PhotoHolder extends RecyclerView.ViewHolder {
        final ImageView image;
        final View videoBadge;
        final TextView duration;

        PhotoHolder(View v) {
            super(v);
            image = v.findViewById(R.id.thumb);
            videoBadge = v.findViewById(R.id.video_badge);
            duration = v.findViewById(R.id.duration);
        }
    }
}
