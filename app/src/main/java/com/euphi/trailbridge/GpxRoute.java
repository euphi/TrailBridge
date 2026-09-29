package com.euphi.trailbridge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * An imported GPX route: the geometry (points with cumulative distance, and
 * elevation where the file has it) plus the manoeuvres along it. Plain data,
 * no Android dependencies, so it can be unit-tested on the JVM.
 */
public final class GpxRoute {

    /** One manoeuvre at a fixed distance along the route. */
    public static final class Step {
        public final double distM;
        public final int maneuver;        // Maneuver.* constant
        public final int roundaboutExit;  // 0 = unknown
        public final String name;

        public Step(double distM, int maneuver, int roundaboutExit, String name) {
            this.distM = distM;
            this.maneuver = maneuver;
            this.roundaboutExit = roundaboutExit;
            this.name = name == null ? "" : name;
        }
    }

    public final String name;
    public final double[] lat;
    public final double[] lon;
    /** Metres; NaN where the file has no elevation for that point. */
    public final float[] ele;
    /** Cumulative distance from the first point, metres. */
    public final double[] cum;
    public final double totalM;
    /** Sorted by distM; always starts with DEPART and ends with ARRIVE. */
    public final List<Step> steps;
    /** True if the file carried its own routing information (rtept/OsmAnd
     *  route segments) -- then steps were NOT derived from the geometry. */
    public final boolean hasRoutingInfo;

    /**
     * @param fileSteps steps as found in the file (may be empty); DEPART/ARRIVE
     *                  are added here if missing.
     */
    public GpxRoute(String name, double[] lat, double[] lon, float[] ele,
                    List<Step> fileSteps, boolean hasRoutingInfo) {
        this.name = name == null ? "" : name;
        this.lat = lat;
        this.lon = lon;
        this.ele = ele;
        this.cum = new double[lat.length];
        for (int i = 1; i < lat.length; i++) {
            cum[i] = cum[i - 1] + Geo.distanceM(lat[i - 1], lon[i - 1], lat[i], lon[i]);
        }
        this.totalM = lat.length == 0 ? 0 : cum[lat.length - 1];
        this.hasRoutingInfo = hasRoutingInfo;
        this.steps = Collections.unmodifiableList(withEnds(fileSteps, totalM));
    }

    private static List<Step> withEnds(List<Step> in, double totalM) {
        List<Step> out = new ArrayList<>();
        for (Step s : in) {
            if (s.maneuver != Maneuver.DEPART && s.maneuver != Maneuver.ARRIVE) {
                out.add(s);
            }
        }
        Collections.sort(out, new Comparator<Step>() {
            @Override
            public int compare(Step a, Step b) {
                return Double.compare(a.distM, b.distM);
            }
        });
        out.add(0, new Step(0, Maneuver.DEPART, 0, ""));
        out.add(new Step(totalM, Maneuver.ARRIVE, 0, ""));
        return out;
    }

    public int pointCount() {
        return lat.length;
    }

    /** True if enough points carry an elevation for a profile to make sense. */
    public boolean hasElevation() {
        int with = 0;
        for (float e : ele) {
            if (!Float.isNaN(e)) with++;
        }
        return lat.length >= 2 && with >= 2 && with >= lat.length * 0.8;
    }

    /** Total ascent in metres over the smoothed profile, 0 without elevation. */
    public int totalAscentM() {
        ElevationProfile p = ElevationProfile.of(this);
        return p == null ? 0 : p.totalAscentM();
    }
}
