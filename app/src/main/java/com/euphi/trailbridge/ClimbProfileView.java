package com.euphi.trailbridge;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import java.util.Locale;

/**
 * The elevation profile that TrailBridge sends to the BikeComputer for a climb ahead
 * ({@link ProfileFrame}), with the rider in it. The stretch already ridden is dimmed, the
 * stretch ahead drawn in full -- same look as {@link RouteProfileView} (the "ridge" of
 * Rim & Ridge), but not interactive.
 */
public class ClimbProfileView extends View {

    private final int colorProfile;
    private final int colorBackground;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint baselinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path fillPath = new Path();
    private final Path linePath = new Path();

    private final float dp;
    private final float padLeft, padRight, padTop, padBottom;

    @Nullable private ProfileFrame frame;
    private float[] alt;
    private double minAlt, maxAlt;
    private double riderM = Double.NaN;

    public ClimbProfileView(Context context) {
        this(context, null);
    }

    public ClimbProfileView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;
        padLeft = 46 * dp;
        padRight = 10 * dp;
        padTop = 12 * dp;
        padBottom = 20 * dp;

        colorProfile = ContextCompat.getColor(context, R.color.rr_sage);
        colorBackground = ContextCompat.getColor(context, R.color.rr_tour_bg);

        fillPaint.setStyle(Paint.Style.FILL);
        linePaint.setColor(colorProfile);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.5f * dp);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        baselinePaint.setColor(ContextCompat.getColor(context, R.color.rr_rim_outline));
        baselinePaint.setStyle(Paint.Style.STROKE);
        baselinePaint.setStrokeWidth(1 * dp);
        cursorPaint.setColor(ContextCompat.getColor(context, R.color.rr_parchment_bright));
        cursorPaint.setStrokeWidth(2 * dp);
        cursorDotPaint.setColor(ContextCompat.getColor(context, R.color.rr_brass));
        cursorRingPaint.setColor(colorBackground);
        textPaint.setTextSize(10 * dp);
        textPaint.setColor(ContextCompat.getColor(context, R.color.rr_muted));
        textPaint.setTypeface(ResourcesCompat.getFont(context, R.font.ibm_plex_mono_regular));
    }

    /** @param riderOffsetM rider's distance from the profile's first point, NaN = not in it */
    public void setProfile(@Nullable ProfileFrame frame, double riderOffsetM) {
        if (frame != this.frame) {
            this.frame = frame;
            alt = frame == null ? null : frame.altitudesM();
            if (alt != null) {
                minAlt = Double.MAX_VALUE;
                maxAlt = -Double.MAX_VALUE;
                for (float a : alt) {
                    minAlt = Math.min(minAlt, a);
                    maxAlt = Math.max(maxAlt, a);
                }
                // Don't blow a gentle slope up into a wall: show at least 20 m of range.
                if (maxAlt - minAlt < 20) {
                    double mid = (maxAlt + minAlt) / 2;
                    minAlt = mid - 10;
                    maxAlt = mid + 10;
                }
            }
        }
        this.riderM = riderOffsetM;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(resolveSize(0, widthMeasureSpec), resolveSize((int) (130 * dp), heightMeasureSpec));
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int top = (colorProfile & 0x00FFFFFF) | 0x66000000;
        int bottom = (colorProfile & 0x00FFFFFF) | 0x05000000;
        fillPaint.setShader(new LinearGradient(0, padTop, 0, h - padBottom, top, bottom, Shader.TileMode.CLAMP));
    }

    private float xOf(double offsetM) {
        ProfileFrame f = frame;
        float w = Math.max(1, getWidth() - padLeft - padRight);
        return padLeft + (float) (f == null || f.lengthM() <= 0 ? 0 : offsetM / f.lengthM() * w);
    }

    private float yOf(double altM) {
        double f = (altM - minAlt) / (maxAlt - minAlt);
        float h = Math.max(1, getHeight() - padTop - padBottom);
        return (float) (getHeight() - padBottom - f * h);
    }

    @Override
    protected void onDraw(Canvas c) {
        ProfileFrame f = frame;
        if (f == null || alt == null) return;
        float left = padLeft;
        float right = getWidth() - padRight;
        float bottom = getHeight() - padBottom;

        c.drawLine(left, bottom, right, bottom, baselinePaint);

        fillPath.reset();
        linePath.reset();
        for (int k = 0; k < alt.length; k++) {
            float x = xOf(k * (double) f.stepM);
            float y = yOf(alt[k]);
            if (k == 0) {
                fillPath.moveTo(x, bottom);
                fillPath.lineTo(x, y);
                linePath.moveTo(x, y);
            } else {
                fillPath.lineTo(x, y);
                linePath.lineTo(x, y);
            }
        }
        fillPath.lineTo(xOf(f.lengthM()), bottom);
        fillPath.close();

        float cx = Double.isNaN(riderM) ? left : xOf(riderM);
        // Behind the rider: dimmed. Ahead: full.
        c.save();
        c.clipRect(left - 2 * dp, 0, cx, getHeight());
        fillPaint.setAlpha(90);
        linePaint.setAlpha(90);
        c.drawPath(fillPath, fillPaint);
        c.drawPath(linePath, linePaint);
        c.restore();
        c.save();
        c.clipRect(cx, 0, right + 2 * dp, getHeight());
        fillPaint.setAlpha(255);
        linePaint.setAlpha(255);
        c.drawPath(fillPath, fillPaint);
        c.drawPath(linePath, linePaint);
        c.restore();

        textPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(String.format(Locale.getDefault(), "%d m", Math.round(maxAlt)), left - 10 * dp, padTop + 8 * dp, textPaint);
        c.drawText(String.format(Locale.getDefault(), "%d m", Math.round(minAlt)), left - 10 * dp, bottom, textPaint);
        // Distance still to go in the profile, under its end.
        if (!Double.isNaN(riderM)) {
            double ahead = f.lengthM() - riderM;
            String text = ahead >= 1000 ? String.format(Locale.getDefault(), "%.1f km", ahead / 1000)
                    : String.format(Locale.getDefault(), "%d m", Math.round(ahead / 10) * 10);
            c.drawText(text, right, getHeight() - 4 * dp, textPaint);
        }

        if (!Double.isNaN(riderM)) {
            c.drawLine(cx, padTop - 4 * dp, cx, bottom, cursorPaint);
            float cy = yOf(f.altitudeAtM(riderM));
            c.drawCircle(cx, cy, 6.5f * dp, cursorRingPaint);   // ring in the card colour separates dot from line
            c.drawCircle(cx, cy, 4.5f * dp, cursorDotPaint);
        }
    }
}
