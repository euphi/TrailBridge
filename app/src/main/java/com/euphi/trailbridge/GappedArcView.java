package com.euphi.trailbridge;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * The "gapped arc" of the Rim & Ridge design system: a 270 degree ring with the
 * 90 degree gap at the bottom, filling clockwise from the lower left, inside a
 * thin bezel ring. On the BikeComputer it carries the speed (main screen) and the
 * distance to the next manoeuvre (navigation screen) -- here it frames the same two
 * numbers, which are ordinary TextViews laid over the view's free centre.
 *
 * Proportions are those of the 480 px display (bezel r=234, arc r=222, 16 px thick
 * on a 240 px radius), the arc a little bolder to hold up on a phone.
 */
public class GappedArcView extends View {

    private static final float START_DEG = 135f;
    private static final float SWEEP_DEG = 270f;

    private final Paint rimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bounds = new RectF();

    private float fraction = 0f;
    @Nullable private ValueAnimator animator;

    public GappedArcView(Context context) {
        this(context, null);
    }

    public GappedArcView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        rimPaint.setStyle(Paint.Style.STROKE);
        rimPaint.setColor(ContextCompat.getColor(context, R.color.rr_rim_outline));
        rimPaint.setStrokeWidth(getResources().getDisplayMetrics().density);
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(ContextCompat.getColor(context, R.color.rr_arc_track));
        fillPaint.setStyle(Paint.Style.STROKE);
        fillPaint.setStrokeCap(Paint.Cap.ROUND);
        fillPaint.setColor(ContextCompat.getColor(context, R.color.rr_brass));
    }

    /** 0 = empty ring, 1 = full ring; eased over a short animation so 1 Hz updates don't jump. */
    public void setFraction(float f) {
        float target = Math.max(0f, Math.min(1f, f));
        if (animator != null) animator.cancel();
        if (!isShown() || Math.abs(target - fraction) < 0.002f) {
            fraction = target;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(fraction, target);
        animator.setDuration(300);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(a -> {
            fraction = (float) a.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }

    public void setFillColor(@ColorInt int color) {
        fillPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Always square: the smaller of what the layout offers.
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int s = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED ? h
                : MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ? w
                : Math.min(w, h);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas c) {
        float size = Math.min(getWidth(), getHeight());
        float radius = size / 2f;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;

        c.drawCircle(cx, cy, radius * 0.975f, rimPaint);

        float arcR = radius * 0.90f;
        float stroke = radius * 0.08f;
        trackPaint.setStrokeWidth(stroke);
        fillPaint.setStrokeWidth(stroke);
        bounds.set(cx - arcR, cy - arcR, cx + arcR, cy + arcR);
        c.drawArc(bounds, START_DEG, SWEEP_DEG, false, trackPaint);
        if (fraction > 0.001f) {
            c.drawArc(bounds, START_DEG, SWEEP_DEG * fraction, false, fillPaint);
        }
    }
}
