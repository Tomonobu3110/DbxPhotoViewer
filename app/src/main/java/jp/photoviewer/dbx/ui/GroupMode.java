package jp.photoviewer.dbx.ui;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/** グリッドのグループ化単位(見出しのまとめ方)。列数とは独立。 */
public enum GroupMode {
    DAY("yyyy年M月d日(E)"),
    MONTH("yyyy年M月"),
    YEAR("yyyy年");

    private final String pattern;

    GroupMode(String pattern) {
        this.pattern = pattern;
    }

    public static GroupMode fromOrdinal(int ordinal) {
        GroupMode[] values = values();
        return ordinal >= 0 && ordinal < values.length ? values[ordinal] : MONTH;
    }

    /** 同じグループかどうかの判定に使う数値キー。 */
    public int groupKey(Calendar cal, long time) {
        cal.setTimeInMillis(time);
        int y = cal.get(Calendar.YEAR);
        switch (this) {
            case YEAR:
                return y;
            case MONTH:
                return y * 100 + cal.get(Calendar.MONTH);
            default:
                return y * 1000 + cal.get(Calendar.DAY_OF_YEAR);
        }
    }

    public SimpleDateFormat newFormat() {
        return new SimpleDateFormat(pattern, Locale.JAPAN);
    }

    public String label(SimpleDateFormat format, long time) {
        return format.format(new Date(time));
    }
}
