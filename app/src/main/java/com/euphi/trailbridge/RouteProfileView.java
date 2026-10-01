package com.euphi.trailbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import java.util.Locale;

/**
 * The route as an elevation profile over the distance, with the manoeuvres
 * marked on it and the rider's position as a cursor. Touching or dragging it
 * picks a position -- the "fast forward" of the GPX test playback.
 *
 * Without elevation data in the file only the baseline, markers and cursor are drawn.
 *
 * Styled as the "ridge" of Rim & Ridge: the terrain in sage (the colour of the height icon),
 * manoeuvres in brass like everything navigation, the rider in bright parchment.
 */
public class RouteProfileView extends View {

    public interface OnSeekListener {
        /**
         * @param committed false while the finger is still moving (preview only),
         *                  true when it is lifted -- then jump there.
         */
        void onScrub(double distM, boolean committed);
    }

    private final int colorProfile;
    private final int colorBackground;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fillPath = new Path();
    private final Path linePath = new Path();

    private final float dp;
    private final float padLeft, padRight, padTop, padBottom;

    @Nullable private GpxRoute route;
    @Nullable private ElevationProfile elevation;
    private double positionM = 0;
    private boolean dragging = false;
    @Nullable private OnSeekListener listener;

    // Cached per size/route: altitude of every pixel column.
    private float[] columnAlt;
    private double minAlt, maxAlt;

    public RouteProfileView(Context context) {
        this(context, null);
    }

    public RouteProfileView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        padLeft = 46 * dp;
        padRight = 8 * dp;
        padTop = 14 * dp;
        padBottom = 20 * dp;

        colorProfile = ContextCompat.getColor(context, R.color.rr_sage);
        colorBackground = ContextCompat.getColor(context, R.color.rr_tour_bg);
        int brass = ContextCompat.getColor(context, R.color.rr_brass);

        fillPaint.setStyle(Paint.Style.FILL);
        linePaint.setColor(colorProfile);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2 * dp);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        baselinePaint.setColor(ContextCompat.getColor(context, R.color.rr_rim_outline));
        baselinePaint.setStyle(Paint.Style.STROKE);
        baselinePaint.setStrokeWidth(1 * dp);
        markerPaint.setColor(brass);
        markerPaint.setStrokeWidth(1 * dp);
        cursorPaint.setColor(ContextCompat.getColor(context, R.color.rr_parchment_bright));
        cursorPaint.setStrokeWidth(2 * dp);
        cursorDotPaint.setColor(brass);
        cursorRingPaint.setColor(colorBackground);
        textPaint.setTextSize(10 * dp);
        textPaint.setColor(ContextCompat.getColor(context, R.color.rr_muted));
        textPaint.setTypeface(ResourcesCompat.getFont(context, R.font.ibm_plex_mono_regular));
    }

    public void setRoute(@Nullable GpxRoute route) {
        if (route == this.route) return;
        this.route = route;
        this.elevation = route == null ? null : ElevationProfile.of(route);
        this.positionM = 0;
        columnAlt = null;
        invalidate();
    }

    /** Where the rider is (ignored while the user is dragging). */
    public void setPosition(double distM) {
        if (dragging) return;
        positionM = distM;
        invalidate();
    }

    public void setOnSeekListener(@Nullable OnSeekListener l) {
        this.listener = l;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int h = (int) (150 * dp);
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        columnAlt = null;
        // Terrain fill: sage fading out towards the baseline.
        int top = (colorProfile & 0x00FFFFFF) | 0x66000000;
        int bottom = (colorProfile & 0x00FFFFFF) | 0x05000000;
        fillPaint.setShader(new LinearGradient(0, padTop, 0, h - padBottom, top, bottom, Shader.TileMode.CLAMP));
    }

    private float plotWidth() {
        return Math.max(1, getWidth() - padLeft - padRight);
    }

    private float plotHeight() {
        return Math.max(1, getHeight() - padTop - padBottom);
    }

    private float xOf(double distM) {
        GpxRoute r = route;
        return padLeft + (float) (r == null || r.totalM <= 0 ? 0 : distM / r.totalM * plotWidth());
    }

    private double distOf(float x) {
        GpxRoute r = route;
        if (r == null) return 0;
        double f = (x - padLeft) / plotWidth();
        return Math.max(0, Math.min(1, f)) * r.totalM;
    }

    private void ensureColumns() {
        if (columnAlt != null || elevation == null || route == null) return;
        int cols = (int) plotWidth() + 1;
        columnAlt = new float[cols];
        minAlt = Double.MAX_VALUE;
        maxAlt = -Double.MAX_VALUE;
        for (int i = 0; i < cols; i++) {
            double a = elevation.altitudeAt(i / (double) Math.max(1, cols - 1) * route.totalM);
            columnAlt[i] = (float) a;
            minAlt = Math.min(minAlt, a);
            maxAlt = Math.max(maxAlt, a);
        }
        // Don't blow flat terrain up into mountains: show at least 20 m of range.
        if (maxAlt - minAlt < 20) {
            double mid = (maxAlt + minAlt) / 2;
            minAlt = mid - 10;
            maxAlt = mid + 10;
        }
    }

    private float yOf(double alt) {
        double f = (alt - minAlt) / (maxAlt - minAlt);
        return (float) (getHeight() - padBottom - f * plotHeight());
    }

    @Override
    protected void onDraw(Canvas c) {
        GpxRoute r = route;
        if (r == null) return;
        float left = padLeft;
        float right = getWidth() - padRight;
        float bottom = getHeight() - padBottom;

        c.drawLine(left, bottom, right, bottom, baselinePaint);

        ensureColumns();
        if (columnAlt != null) {
            fillPath.reset();
            linePath.reset();
            for (int i = 0; i < columnAlt.length; i++) {
                float x = left + i;
                float y = yOf(columnAlt[i]);
                if (i == 0) {
                    fillPath.moveTo(x, bottom);
                    fillPath.lineTo(x, y);
                    linePath.moveTo(x, y);
                } else {
                    fillPath.lineTo(x, y);
                    linePath.lineTo(x, y);
                }
            }
            fillPath.lineTo(left + columnAlt.length - 1, bottom);
            fillPath.close();
            c.drawPath(fillPath, fillPaint);
            c.drawPath(linePath, linePaint);
            textPaint.setTextAlign(Paint.Align.RIGHT);
            c.drawText(String.format(Locale.getDefault(), "%d m", Math.round(maxAlt)),
                    left - 10 * dp, padTop + 8 * dp, textPaint);
            c.drawText(String.format(Locale.getDefault(), "%d m", Math.round(minAlt)),
                    left - 10 * dp, bottom, textPaint);
        } else {
            textPaint.setTextAlign(Paint.Align.LEFT);
            c.drawText("keine Höhendaten", left + 4 * dp, bottom - 6 * dp, textPaint);
        }

        // Manoeuvres: a thin line through the profile and a dot on top.
        for (GpxRoute.Step s : r.steps) {
            if (s.maneuver == Maneuver.DEPART || s.maneuver == Maneuver.ARRIVE) continue;
            float x = xOf(s.distM);
            markerPaint.setAlpha(70);
            c.drawLine(x, padTop, x, bottom, markerPaint);
            markerPaint.setAlpha(255);
            c.drawCircle(x, padTop - 4 * dp, 2.5f * dp, markerPaint);
        }

        // Axis labels: distance at both ends.
        textPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText("0", left, getHeight() - 4 * dp, textPaint);
        textPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(String.format(Locale.getDefault(), "%.1f km", r.totalM / 1000), right, getHeight() - 4 * dp, textPaint);

        // Rider.
        float cx = xOf(positionM);
        c.drawLine(cx, padTop - 4 * dp, cx, bottom, cursorPaint);
        if (columnAlt != null && columnAlt.length > 0) {
            int i = Math.max(0, Math.min(columnAlt.length - 1, Math.round(cx - left)));
            float cy = yOf(columnAlt[i]);
            c.drawCircle(cx, cy, 6.5f * dp, cursorRingPaint);   // ring in the card colour separates dot from line
            c.drawCircle(cx, cy, 4.5f * dp, cursorDotPaint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (route == null) return false;
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Inside a ScrollView: this gesture is ours, not a scroll.
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                dragging = true;
                scrubTo(e.getX(), false);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragging) scrubTo(e.getX(), false);
                return true;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    scrubTo(e.getX(), true);
                    dragging = false;
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                invalidate();
                return true;
            default:
                return super.onTouchEvent(e);
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    private void scrubTo(float x, boolean committed) {
        double d = distOf(x);
        positionM = d;
        invalidate();
        if (listener != null) listener.onScrub(d, committed);
    }
}
