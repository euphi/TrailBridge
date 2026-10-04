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

    /** A named place along the route (GPX wpt), projected onto it. */
    public static final class Waypoint {
        public final double distM;
        /** "" if the file gives it no name. */
        public final String name;

        public Waypoint(double distM, String name) {
            this.distM = distM;
            this.name = name == null ? "" : name;
        }
    }

    public static final long NO_TIME = -1;
    public static final short NO_VALUE = -1;

    public final String name;
    public final double[] lat;
    public final double[] lon;
    /** Metres; NaN where the file has no elevation for that point. */
    public final float[] ele;
    /** Cumulative distance from the first point, metres. */
    public final double[] cum;
    /** Recording time per point, ms since the epoch; {@link #NO_TIME} where the file has none. */
    public final long[] timeMs;
    /** Heart rate per point in bpm; {@link #NO_VALUE} where the file has none (TrackPointExtension hr). */
    public final short[] hr;
    /** Cadence per point in rpm (0 = coasting is a real value); {@link #NO_VALUE} where the file has none. */
    public final short[] cad;
    /** Power per point in watts (0 = coasting is a real value); {@link #NO_VALUE} where the file has none. */
    public final short[] power;
    public final double totalM;
    /** Sorted by distM; always starts with DEPART and ends with ARRIVE. */
    public final List<Step> steps;
    /** True if the file carried its own routing information (rtept/OsmAnd
     *  route segments) -- then steps were NOT derived from the geometry. */
    public final boolean hasRoutingInfo;
    /** Sorted by distM; only the waypoints that lie on the route. */
    public final List<Waypoint> waypoints;

    /**
     * @param fileSteps steps as found in the file (may be empty); DEPART/ARRIVE
     *                  are added here if missing.
     */
    public GpxRoute(String name, double[] lat, double[] lon, float[] ele,
                    List<Step> fileSteps, boolean hasRoutingInfo) {
        this(name, lat, lon, ele, null, null, null, fileSteps, hasRoutingInfo);
    }

    public GpxRoute(String name, double[] lat, double[] lon, float[] ele,
                    long[] timeMs, short[] hr, short[] cad,
                    List<Step> fileSteps, boolean hasRoutingInfo) {
        this(name, lat, lon, ele, timeMs, hr, cad, null, fileSteps, hasRoutingInfo);
    }

    public GpxRoute(String name, double[] lat, double[] lon, float[] ele,
                    long[] timeMs, short[] hr, short[] cad, short[] power,
                    List<Step> fileSteps, boolean hasRoutingInfo) {
        this(name, lat, lon, ele, timeMs, hr, cad, power, fileSteps, hasRoutingInfo,
                Collections.<Waypoint>emptyList());
    }

    /** @param timeMs, hr, cad, power per-point recorded values; null = the file has none of that kind. */
    public GpxRoute(String name, double[] lat, double[] lon, float[] ele,
                    long[] timeMs, short[] hr, short[] cad, short[] power,
                    List<Step> fileSteps, boolean hasRoutingInfo, List<Waypoint> waypoints) {
        this.name = name == null ? "" : name;
        this.power = power != null ? power : filled(new short[lat.length], NO_VALUE);
        this.timeMs = timeMs != null ? timeMs : filled(new long[lat.length], NO_TIME);
        this.hr = hr != null ? hr : filled(new short[lat.length], NO_VALUE);
        this.cad = cad != null ? cad : filled(new short[lat.length], NO_VALUE);
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
        List<Waypoint> sorted = new ArrayList<>(waypoints);
        Collections.sort(sorted, new Comparator<Waypoint>() {
            @Override
            public int compare(Waypoint a, Waypoint b) {
                return Double.compare(a.distM, b.distM);
            }
        });
        this.waypoints = Collections.unmodifiableList(sorted);
    }

    private static long[] filled(long[] a, long v) {
        java.util.Arrays.fill(a, v);
        return a;
    }

    private static short[] filled(short[] a, short v) {
        java.util.Arrays.fill(a, v);
        return a;
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

    /** True if (almost) every point has a recording time, so the file says how fast it was ridden. */
    public boolean hasTimes() {
        int with = 0;
        for (long t : timeMs) {
            if (t != NO_TIME) with++;
        }
        return mostly(with);
    }

    /** True if enough points carry a heart rate to take it from the file. */
    public boolean hasHeartRate() {
        int with = 0;
        for (short v : hr) {
            if (v != NO_VALUE) with++;
        }
        return mostly(with);
    }

    /** True if enough points carry a cadence to take it from the file. */
    public boolean hasCadence() {
        int with = 0;
        for (short v : cad) {
            if (v != NO_VALUE) with++;
        }
        return mostly(with);
    }

    /** True if enough points carry a power value to take it from the file. */
    public boolean hasPower() {
        int with = 0;
        for (short v : power) {
            if (v != NO_VALUE) with++;
        }
        return mostly(with);
    }

    private boolean mostly(int with) {
        return lat.length >= 2 && with >= 2 && with >= lat.length * 0.8;
    }

    /** Total ascent in metres over the smoothed profile, 0 without elevation. */
    public int totalAscentM() {
        ElevationProfile p = ElevationProfile.of(this);
        return p == null ? 0 : p.totalAscentM();
    }
}
