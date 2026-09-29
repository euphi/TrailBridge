package com.euphi.trailbridge;

/** Small geodesy helpers -- spherical earth is plenty for route matching. */
final class Geo {

    static final double EARTH_RADIUS_M = 6371008.8;

    private Geo() {
    }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dp = p2 - p1;
        double dl = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dp / 2) * Math.sin(dp / 2)
                + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2);
        return 2 * EARTH_RADIUS_M * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }

    /** Local east (x) / north (y) offset in metres of a point relative to an origin. */
    static double eastM(double originLat, double originLon, double lat, double lon) {
        return Math.toRadians(lon - originLon) * Math.cos(Math.toRadians(originLat)) * EARTH_RADIUS_M;
    }

    static double northM(double originLat, double lat) {
        return Math.toRadians(lat - originLat) * EARTH_RADIUS_M;
    }

    /** Compass bearing in degrees [0,360) of a planar vector (east, north). */
    static double bearingDeg(double dx, double dy) {
        double b = Math.toDegrees(Math.atan2(dx, dy));
        return b < 0 ? b + 360 : b;
    }

    /** Signed smallest difference to - from, in (-180, 180]; positive = clockwise (right). */
    static double angleDiffDeg(double from, double to) {
        double d = (to - from) % 360;
        if (d > 180) d -= 360;
        if (d <= -180) d += 360;
        return d;
    }
}
