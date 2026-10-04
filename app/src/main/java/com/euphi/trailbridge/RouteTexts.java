package com.euphi.trailbridge;

import android.content.Context;

/**
 * The few texts the route navigation itself puts into what the BikeComputer shows
 * (street name while off the route, name of the destination and of unnamed
 * waypoints). Not in the UI's string resources lookup so the navigation classes
 * stay free of Android: the service passes {@link #of(Context)}, tests the default.
 */
public final class RouteTexts {

    public static final RouteTexts DEFAULT = new RouteTexts("Off route", "Destination", "Waypoint %d");

    final String offRoute;
    final String destination;
    private final String unnamedWaypointFormat;

    RouteTexts(String offRoute, String destination, String unnamedWaypointFormat) {
        this.offRoute = offRoute;
        this.destination = destination;
        this.unnamedWaypointFormat = unnamedWaypointFormat;
    }

    public static RouteTexts of(Context context) {
        return new RouteTexts(context.getString(R.string.off_route),
                context.getString(R.string.destination_name),
                context.getString(R.string.waypoint_unnamed, 0).replace("0", "%d"));
    }

    String unnamedWaypoint(int number) {
        return String.format(java.util.Locale.ROOT, unnamedWaypointFormat, number);
    }
}
