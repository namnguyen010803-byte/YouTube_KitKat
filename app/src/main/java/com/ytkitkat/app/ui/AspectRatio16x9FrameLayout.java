package com.ytkitkat.app.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.FrameLayout;

/**
 * Khung chứa tự động tính chiều cao theo đúng tỷ lệ 16:9 dựa trên chiều rộng.
 * Giúp hiển thị Thumbnail chuẩn YouTube Mobile 2026 trên mọi độ phân giải màn hình (như Sharp Aquos 305SH 720x1280).
 */
public class AspectRatio16x9FrameLayout extends FrameLayout {

    public AspectRatio16x9FrameLayout(Context context) {
        super(context);
    }

    public AspectRatio16x9FrameLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatio16x9FrameLayout(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width > 0) {
            int height = (width * 9) / 16;
            int exactHeightSpec = MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY);
            super.onMeasure(widthMeasureSpec, exactHeightSpec);
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
