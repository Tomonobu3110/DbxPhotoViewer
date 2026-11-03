package jp.photoviewer.dbx.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/** 幅に合わせて高さを決める正方形のセル。 */
public class SquareFrameLayout extends FrameLayout {

    public SquareFrameLayout(Context context) {
        super(context);
    }

    public SquareFrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec);
    }
}
