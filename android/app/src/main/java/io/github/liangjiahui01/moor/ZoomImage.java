package io.github.liangjiahui01.moor;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

/** An image that fits the screen; pinch or double-tap to zoom, drag to move around. */
final class ZoomImage extends ImageView {
    private final Matrix m = new Matrix();
    private final ScaleGestureDetector scale;
    private final GestureDetector gestures;
    private float fit = 1f;

    ZoomImage(Context c) {
        super(c);
        setScaleType(ScaleType.MATRIX);
        scale = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector d) {
                float now = current(), f = d.getScaleFactor();
                f = Math.max(fit / now, Math.min(f, fit * 8 / now));
                m.postScale(f, f, d.getFocusX(), d.getFocusY());
                settle();
                return true;
            }
        });
        gestures = new GestureDetector(c, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onScroll(MotionEvent a, MotionEvent b, float dx, float dy) {
                m.postTranslate(-dx, -dy);
                settle();
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (current() > fit * 1.05f) reset();
                else {
                    m.postScale(2.5f, 2.5f, e.getX(), e.getY());
                    settle();
                }
                return true;
            }
        });
    }

    @Override
    public void setImageBitmap(Bitmap bm) {
        super.setImageBitmap(bm);
        post(this::reset);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        reset();
    }

    private void reset() {
        if (getDrawable() == null || getWidth() == 0) return;
        float iw = getDrawable().getIntrinsicWidth(), ih = getDrawable().getIntrinsicHeight();
        // Small images keep their size (up to 2x on a dense screen) rather than being blown up
        fit = Math.min(Math.min(getWidth() / iw, getHeight() / ih), getResources().getDisplayMetrics().density);
        m.reset();
        m.postScale(fit, fit);
        m.postTranslate((getWidth() - iw * fit) / 2, (getHeight() - ih * fit) / 2);
        setImageMatrix(m);
    }

    private float current() {
        float[] v = new float[9];
        m.getValues(v);
        return v[Matrix.MSCALE_X];
    }

    /** Keeps the image on screen: centered when smaller than the view, edges not pulled inside when larger. */
    private void settle() {
        if (getDrawable() == null) return;
        RectF r = new RectF(0, 0, getDrawable().getIntrinsicWidth(), getDrawable().getIntrinsicHeight());
        m.mapRect(r);
        float dx = 0, dy = 0, w = getWidth(), h = getHeight();
        if (r.width() <= w) dx = (w - r.width()) / 2 - r.left;
        else if (r.left > 0) dx = -r.left;
        else if (r.right < w) dx = w - r.right;
        if (r.height() <= h) dy = (h - r.height()) / 2 - r.top;
        else if (r.top > 0) dy = -r.top;
        else if (r.bottom < h) dy = h - r.bottom;
        m.postTranslate(dx, dy);
        setImageMatrix(m);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        scale.onTouchEvent(e);
        gestures.onTouchEvent(e);
        return true;
    }
}
