package com.euphi.trailbridge;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.ColorRes;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import java.util.Locale;

/**
 * The "Position" and "Navigation" cards (layouts card_position / card_navigation) -- shared by
 * the main screen and the navigation mode, so both show the same thing the same way.
 * Construct it once the activity's content view is set.
 */
final class LiveCards {

    /** Full scale of the speed ring. */
    private static final double SPEED_ARC_MAX_KMH = 50;
    /** Distance at which the navigation ring starts to fill. */
    private static final double NAV_ARC_MAX_M = 500;

    private final Context context;

    private final GappedArcView speedArc;
    private final TextView speedView;
    private final TextView altView;
    private final TextView hrView;
    private final TextView cadView;
    private final TextView gpsAccView;
    private final View simChip;
    private final GappedArcView navArc;
    private final ImageView navArrow;
    private final TextView navDistView;
    private final TextView navManeuverView;
    private final TextView navStreetView;
    private final TextView navThenView;
    private final TextView navRemainView;

    LiveCards(Activity a) {
        context = a;
        speedArc = a.findViewById(R.id.speedArc);
        speedView = a.findViewById(R.id.speedView);
        altView = a.findViewById(R.id.altView);
        hrView = a.findViewById(R.id.hrView);
        cadView = a.findViewById(R.id.cadView);
        gpsAccView = a.findViewById(R.id.gpsAccView);
        simChip = a.findViewById(R.id.simChip);
        navArc = a.findViewById(R.id.navArc);
        navArrow = a.findViewById(R.id.navArrow);
        navDistView = a.findViewById(R.id.navDistView);
        navManeuverView = a.findViewById(R.id.navManeuverView);
        navStreetView = a.findViewById(R.id.navStreetView);
        navThenView = a.findViewById(R.id.navThenView);
        navRemainView = a.findViewById(R.id.navRemainView);
        showNav(NavState.NONE);
        showPosition(PositionState.NONE);
    }

    /** Tints a status dot (R.drawable.status_dot). */
    static void setDot(Context context, View dot, @ColorRes int colorRes) {
        dot.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(context, colorRes)));
    }

    /** A value, or the dash placeholder in the muted colour when there is none. */
    private void setValue(TextView view, @Nullable String value, @ColorRes int valueColorRes) {
        view.setText(value != null ? value : context.getString(R.string.dash));
        view.setTextColor(ContextCompat.getColor(context, value != null ? valueColorRes : R.color.rr_muted));
    }

    /** Empty text takes the whole view out of the layout instead of leaving a blank line. */
    private static void setTextOrGone(TextView view, CharSequence text) {
        view.setText(text);
        view.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    void showPosition(PositionState p) {
        double kmh = p.hasFix && p.hasSpeed ? p.speedCms / 100.0 * 3.6 : -1;
        setValue(speedView, kmh >= 0 ? String.format(Locale.getDefault(), "%.1f", kmh) : null,
                R.color.rr_parchment_bright);
        speedArc.setFraction(kmh >= 0 ? (float) (kmh / SPEED_ARC_MAX_KMH) : 0f);
        setValue(altView, p.hasFix && p.hasAltitude ? String.valueOf(p.altitudeM) : null, R.color.rr_parchment);
        setValue(hrView, p.hasHeartRate ? String.valueOf(p.heartRateBpm) : null, R.color.rr_parchment);
        setValue(cadView, p.hasCadence ? String.valueOf(p.cadenceRpm) : null, R.color.rr_parchment);
        gpsAccView.setText(!p.hasFix ? context.getString(R.string.gps_none)
                : p.hasAccuracy ? String.format(Locale.getDefault(), "±%d m", Math.round(p.accuracyMx10 / 10.0))
                : "Fix");
        simChip.setVisibility(p.simFlags != 0 ? View.VISIBLE : View.GONE);
    }

    void showNav(NavState st) {
        if (!st.navigating) {
            navArc.setFraction(0f);
            navArrow.setVisibility(View.INVISIBLE);
            setValue(navDistView, null, R.color.rr_parchment_bright);
            navManeuverView.setText(R.string.nav_none);
            setTextOrGone(navStreetView, "");
            setTextOrGone(navThenView, "");
            setTextOrGone(navRemainView, "");
            return;
        }
        navArc.setFraction((float) (1 - Math.min(st.maneuverDistanceM, NAV_ARC_MAX_M) / NAV_ARC_MAX_M));
        navDistView.setTextColor(ContextCompat.getColor(context, R.color.rr_parchment_bright));
        navDistView.setText(distance(st.maneuverDistanceM, 0.42f));
        navManeuverView.setText(maneuverLabel(st.maneuver, st.roundaboutExit));
        setTextOrGone(navStreetView, st.streetName);

        float angle = arrowAngle(st.maneuver);
        navArrow.setVisibility(Float.isNaN(angle) ? View.INVISIBLE : View.VISIBLE);
        if (!Float.isNaN(angle)) navArrow.setRotation(angle);

        setTextOrGone(navThenView, st.nextManeuver == Maneuver.NONE ? ""
                : context.getString(R.string.nav_then, maneuverLabel(st.nextManeuver, 0),
                        distance(st.nextManeuverDistanceM, 1f)));
        setTextOrGone(navRemainView, st.remainingDistanceM <= 0 ? ""
                : context.getString(R.string.nav_remaining, distance(st.remainingDistanceM, 1f),
                        duration(st.remainingTimeS)));
    }

    /** "180 m" / "1,2 km"; the unit at {@code unitScale} of the number's size. */
    static CharSequence distance(int meters, float unitScale) {
        String num;
        String unit;
        if (meters < 1000) {
            num = String.valueOf(meters);
            unit = " m";
        } else {
            num = String.format(Locale.getDefault(), "%.1f", meters / 1000.0);
            unit = " km";
        }
        SpannableString s = new SpannableString(num + unit);
        if (unitScale != 1f) {
            s.setSpan(new RelativeSizeSpan(unitScale), num.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return s;
    }

    private static String duration(int seconds) {
        int min = Math.round(seconds / 60f);
        return min >= 60 ? String.format(Locale.getDefault(), "%d:%02d h", min / 60, min % 60) : min + " min";
    }

    /** Clockwise rotation of the straight-ahead arrow for a manoeuvre; NaN = no arrow. */
    private static float arrowAngle(int maneuver) {
        switch (maneuver) {
            case Maneuver.STRAIGHT: return 0;
            case Maneuver.KEEP_LEFT: return -30;
            case Maneuver.KEEP_RIGHT: return 30;
            case Maneuver.TURN_SLIGHT_LEFT: return -45;
            case Maneuver.TURN_SLIGHT_RIGHT: return 45;
            case Maneuver.TURN_LEFT: return -90;
            case Maneuver.TURN_RIGHT: return 90;
            case Maneuver.TURN_SHARP_LEFT: return -135;
            case Maneuver.TURN_SHARP_RIGHT: return 135;
            case Maneuver.UTURN_LEFT: return -180;
            case Maneuver.UTURN_RIGHT: return 180;
            default: return Float.NaN;
        }
    }

    private String maneuverLabel(int maneuver, int roundaboutExit) {
        switch (maneuver) {
            case Maneuver.DEPART: return context.getString(R.string.maneuver_depart);
            case Maneuver.ARRIVE: return context.getString(R.string.maneuver_arrive);
            case Maneuver.STRAIGHT: return context.getString(R.string.maneuver_straight);
            case Maneuver.TURN_SLIGHT_LEFT: return context.getString(R.string.maneuver_slight_left);
            case Maneuver.TURN_LEFT: return context.getString(R.string.maneuver_left);
            case Maneuver.TURN_SHARP_LEFT: return context.getString(R.string.maneuver_sharp_left);
            case Maneuver.TURN_SLIGHT_RIGHT: return context.getString(R.string.maneuver_slight_right);
            case Maneuver.TURN_RIGHT: return context.getString(R.string.maneuver_right);
            case Maneuver.TURN_SHARP_RIGHT: return context.getString(R.string.maneuver_sharp_right);
            case Maneuver.KEEP_LEFT: return context.getString(R.string.maneuver_keep_left);
            case Maneuver.KEEP_RIGHT: return context.getString(R.string.maneuver_keep_right);
            case Maneuver.UTURN_LEFT:
            case Maneuver.UTURN_RIGHT: return context.getString(R.string.maneuver_uturn);
            case Maneuver.ROUNDABOUT:
                return roundaboutExit > 0 ? context.getString(R.string.maneuver_roundabout_exit, roundaboutExit)
                        : context.getString(R.string.maneuver_roundabout);
            default: return context.getString(R.string.maneuver_unknown);
        }
    }
}
