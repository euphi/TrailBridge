package com.euphi.trailbridge;

import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Encodes the route-overview service's value, see PROTOCOL.md
 * ("Streckenübersicht-Service"). Keep the two in sync -- this class IS the
 * protocol.
 *
 * Unlike the other services' frames this one is only ever read, never
 * indicated, so it may be longer than one ATT packet (the BikeComputer reads
 * it with a long read).
 */
public final class OverviewFrameEncoder {

    public static final int PROTOCOL_VERSION = 1;

    public static final int MSG_HELLO = 0x00;
    public static final int MSG_OVERVIEW = 0x01;
    public static final int MSG_OVERVIEW_NONE = 0x02;

    public static final int TAG_REVISION = 0x01;
    public static final int TAG_WAYPOINTS_AHEAD = 0x02;
    public static final int TAG_CLIMBS_TOTAL = 0x03;
    public static final int TAG_WAYPOINT = 0x04;
    public static final int TAG_CLIMB = 0x05;

    public static final int WAYPOINT_FLAG_DESTINATION = 0x01;
    /** REMAINING_TIME_AT_S of a waypoint when the GPX file has no time stamps. */
    public static final int TIME_UNKNOWN = 0xFFFFFFFF;

    /** Longest attribute value GATT allows; what does not fit is left out, the furthest first. */
    static final int MAX_FRAME_BYTES = 512;
    static final int MAX_NAME_BYTES = 32;

    private OverviewFrameEncoder() {
    }

    public static byte[] hello() {
        return new byte[]{PROTOCOL_VERSION, MSG_HELLO};
    }

    public static byte[] none() {
        return new byte[]{PROTOCOL_VERSION, MSG_OVERVIEW_NONE};
    }

    /** @param revision 1..255, the OVERVIEW_REVISION of the nav frames this overview goes with */
    public static byte[] encode(RouteOverview o, int revision) {
        return encode(o, revision, MAX_FRAME_BYTES);
    }

    static byte[] encode(RouteOverview o, int revision, int maxBytes) {
        List<RouteOverview.Waypoint> waypoints = o.waypointsAhead();
        List<RouteOverview.Climb> climbs = o.climbsAhead();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PROTOCOL_VERSION);
        out.write(MSG_OVERVIEW);
        writeU8(out, TAG_REVISION, revision);
        writeU8(out, TAG_WAYPOINTS_AHEAD, Math.min(255, waypoints.size()));
        writeU8(out, TAG_CLIMBS_TOTAL, Math.min(255, o.climbTotal()));
        writeWaypoint(out, o.destination());

        // Waypoints and climbs in the order the rider meets them (a climb at its
        // foot), so that whatever is cut off at the end is the furthest away.
        int w = 0;
        int c = 0;
        while (w < waypoints.size() || c < climbs.size()) {
            boolean climbNext = w >= waypoints.size()
                    || (c < climbs.size() && climbs.get(c).footM < waypoints.get(w).distM);
            ByteArrayOutputStream entry = new ByteArrayOutputStream();
            if (climbNext) {
                RouteOverview.Climb climb = climbs.get(c++);
                if (climb.number > 255) continue;   // the number is one byte
                writeClimb(entry, climb);
            } else {
                writeWaypoint(entry, waypoints.get(w++));
            }
            if (out.size() + entry.size() > maxBytes) break;
            out.write(entry.toByteArray(), 0, entry.size());
        }
        return out.toByteArray();
    }

    private static void writeWaypoint(ByteArrayOutputStream out, RouteOverview.Waypoint w) {
        byte[] name = NavFrameEncoder.utf8(w.name, MAX_NAME_BYTES);
        out.write(TAG_WAYPOINT);
        out.write(9 + name.length);
        out.write(w.destination ? WAYPOINT_FLAG_DESTINATION : 0);
        writeLe(out, w.remainingAtM, 4);
        writeLe(out, w.remainingTimeAtS < 0 ? TIME_UNKNOWN : w.remainingTimeAtS, 4);
        out.write(name, 0, name.length);
    }

    private static void writeClimb(ByteArrayOutputStream out, RouteOverview.Climb c) {
        out.write(TAG_CLIMB);
        out.write(11);
        out.write(c.number);
        writeLe(out, Math.min(0xFFFF, c.gainM), 2);
        writeLe(out, c.lengthM, 4);
        writeLe(out, c.footRemainingAtM, 4);
    }

    private static void writeU8(ByteArrayOutputStream out, int tag, int value) {
        out.write(tag);
        out.write(1);
        out.write(value & 0xFF);
    }

    private static void writeLe(ByteArrayOutputStream out, int value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((value >>> (8 * i)) & 0xFF);
        }
    }
}
