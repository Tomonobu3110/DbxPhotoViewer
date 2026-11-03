package jp.photoviewer.dbx.ui;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

final class Formats {

    private Formats() {}

    /** 動画の長さ "1:05" / "1:02:03"。 */
    static String duration(long ms) {
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total % 3600) / 60;
        long s = total % 60;
        return h > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s)
                : String.format(Locale.ROOT, "%d:%02d", m, s);
    }

    static String dateTime(long time) {
        return new SimpleDateFormat("yyyy年M月d日(E) H:mm", Locale.JAPAN).format(new Date(time));
    }
}
