package jp.photoviewer.dbx.ui;

import androidx.annotation.Nullable;

import java.util.Objects;

import jp.photoviewer.dbx.data.PhotoEntity;

/** グリッドの1要素(日付見出し、または写真)。 */
public final class GalleryItem {

    public static final int TYPE_HEADER = 0;
    public static final int TYPE_PHOTO = 1;

    public final int type;
    @Nullable public final String header;
    @Nullable public final PhotoEntity photo;
    /** 写真の場合、写真だけを並べたリスト内での位置(ビューアに渡す)。 */
    public final int photoIndex;
    /** 見出しの場合、そのグループの件数。 */
    public final int count;

    private GalleryItem(int type, @Nullable String header, @Nullable PhotoEntity photo, int photoIndex, int count) {
        this.type = type;
        this.header = header;
        this.photo = photo;
        this.photoIndex = photoIndex;
        this.count = count;
    }

    public static GalleryItem header(String label, int count) {
        return new GalleryItem(TYPE_HEADER, label, null, -1, count);
    }

    public static GalleryItem photo(PhotoEntity photo, int index) {
        return new GalleryItem(TYPE_PHOTO, null, photo, index, 0);
    }

    public String stableKey() {
        return type == TYPE_HEADER ? "h:" + header : "p:" + Objects.requireNonNull(photo).id;
    }

    public boolean sameContent(GalleryItem other) {
        if (type != other.type) return false;
        if (type == TYPE_HEADER) return Objects.equals(header, other.header) && count == other.count;
        PhotoEntity a = Objects.requireNonNull(photo);
        PhotoEntity b = Objects.requireNonNull(other.photo);
        return a.rev.equals(b.rev) && Objects.equals(a.durationMs, b.durationMs) && photoIndex == other.photoIndex;
    }
}
