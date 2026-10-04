package com.euphi.trailbridge;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Synthetic routes for the tests. Origin near Berlin; 1 m ~ 9e-6 deg latitude. */
final class Routes {

    static final double LAT0 = 52.5;
    static final double LON0 = 13.4;
    static final double M_PER_DEG_LAT = Math.PI / 180 * Geo.EARTH_RADIUS_M;

    private Routes() {
    }

    static double lat(double northM) {
        return LAT0 + northM / M_PER_DEG_LAT;
    }

    static double lon(double eastM) {
        return LON0 + eastM / (M_PER_DEG_LAT * Math.cos(Math.toRadians(LAT0)));
    }

    /** Polyline through (east, north) corner points in metres, sampled every 10 m; ele(distance) may be null. */
    static GpxRoute polyline(double[][] corners, java.util.function.DoubleUnaryOperator ele) {
        List<double[]> pts = new ArrayList<>();
        double dist = 0;
        for (int c = 0; c < corners.length - 1; c++) {
            double dx = corners[c + 1][0] - corners[c][0];
            double dy = corners[c + 1][1] - corners[c][1];
            double len = Math.hypot(dx, dy);
            int n = (int) Math.round(len / 10);
            for (int k = 0; k < n; k++) {
                double t = k / (double) n;
                pts.add(new double[]{corners[c][0] + t * dx, corners[c][1] + t * dy, dist + t * len});
            }
            dist += len;
        }
        double[] last = corners[corners.length - 1];
        pts.add(new double[]{last[0], last[1], dist});
        double[] la = new double[pts.size()];
        double[] lo = new double[pts.size()];
        float[] el = new float[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            double[] p = pts.get(i);
            lo[i] = lon(p[0]);
            la[i] = lat(p[1]);
            el[i] = ele == null ? Float.NaN : (float) ele.applyAsDouble(p[2]);
        }
        return new GpxRoute("test", la, lo, el, new ArrayList<GpxRoute.Step>(), false);
    }

    /** Like polyline(), but with the turns TurnDetector finds -- what an import of a plain track gives. */
    static GpxRoute navRoute(double[][] corners, java.util.function.DoubleUnaryOperator ele) {
        GpxRoute base = polyline(corners, ele);
        return withSteps(base, TurnDetector.detect(base));
    }

    static GpxRoute withSteps(GpxRoute base, List<GpxRoute.Step> steps) {
        return new GpxRoute(base.name, base.lat, base.lon, base.ele, steps, true);
    }

    static GpxRoute withWaypoints(GpxRoute base, GpxRoute.Waypoint... waypoints) {
        return new GpxRoute(base.name, base.lat, base.lon, base.ele, null, null, null, null,
                base.steps, base.hasRoutingInfo, java.util.Arrays.asList(waypoints));
    }

    /** The route with a time stamp at every point, as if ridden at secondsAt(distance). */
    static GpxRoute withTimes(GpxRoute base, java.util.function.DoubleUnaryOperator secondsAt) {
        long[] time = new long[base.pointCount()];
        for (int i = 0; i < time.length; i++) {
            time[i] = Math.round(secondsAt.applyAsDouble(base.cum[i]) * 1000);
        }
        return new GpxRoute(base.name, base.lat, base.lon, base.ele, time, null, null, null,
                base.steps, base.hasRoutingInfo, base.waypoints);
    }

    static java.io.InputStream stream(String s) {
        return new ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8));
    }

    /** GPX text of a track through the corners (10 m sampling, elevation 100 m flat). */
    static String trkGpx(double[][] corners) {
        GpxRoute r = polyline(corners, d -> 100);
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\"?><gpx version=\"1.1\"><trk><name>T</name><trkseg>");
        for (int i = 0; i < r.pointCount(); i++) {
            b.append("<trkpt lat=\"").append(r.lat[i]).append("\" lon=\"").append(r.lon[i])
                    .append("\"><ele>").append(r.ele[i]).append("</ele></trkpt>");
        }
        return b.append("</trkseg></trk></gpx>").toString();
    }
}
