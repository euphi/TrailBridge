package com.euphi.trailbridge;

/**
 * Our own maneuver codes for the BikeComputer protocol.
 *
 * Deliberately NOT the same numbering as Komoot's old icon table (0..31 into
 * NavImgTable) or as OsmAnd's internal net.osmand.router.TurnType constants.
 * Both of those are implementation details of systems we don't control; this
 * enum is the contract between TrailBridge (this app) and the firmware, so
 * it gets to be exactly as detailed as we need and no more.
 *
 * The firmware side maps these onto its own icon table.
 */
public final class Maneuver {

    private Maneuver() {
    }

    public static final int NONE = 0;              // no active navigation
    public static final int DEPART = 1;             // route start
    public static final int ARRIVE = 2;              // destination reached
    public static final int STRAIGHT = 3;
    public static final int TURN_SLIGHT_LEFT = 4;
    public static final int TURN_LEFT = 5;
    public static final int TURN_SHARP_LEFT = 6;
    public static final int TURN_SLIGHT_RIGHT = 7;
    public static final int TURN_RIGHT = 8;
    public static final int TURN_SHARP_RIGHT = 9;
    public static final int KEEP_LEFT = 10;          // fork/bear left
    public static final int KEEP_RIGHT = 11;         // fork/bear right
    public static final int UTURN_LEFT = 12;
    public static final int UTURN_RIGHT = 13;
    public static final int ROUNDABOUT = 14;         // exit number: separate field
    public static final int UNKNOWN = 255;           // fallback for unmapped types

    /**
     * Parse OsmAnd's turn-type string as found in the AppInfoParams turnInfo
     * bundle (keys "next_turn_type" / "after_nextturn_type"), e.g. "TL",
     * "RNDB2", "OFFR".
     *
     * Verified against net.osmand.router.TurnType#toXmlString() in the OsmAnd
     * source (OsmAnd-java/src/main/java/net/osmand/router/TurnType.java):
     * roundabouts serialise as "RNDB"/"RNLB" + the exit number (exitOut), all
     * other types as their bare short code.
     */
    public static int fromOsmAndXml(String xml) {
        if (xml == null || xml.isEmpty()) {
            return NONE;
        }
        if (xml.startsWith("RNDB") || xml.startsWith("RNLB")) {
            return ROUNDABOUT;
        }
        switch (xml) {
            case "C":
                return STRAIGHT;
            case "TL":
                return TURN_LEFT;
            case "TSLL":
                return TURN_SLIGHT_LEFT;
            case "TSHL":
                return TURN_SHARP_LEFT;
            case "TR":
                return TURN_RIGHT;
            case "TSLR":
                return TURN_SLIGHT_RIGHT;
            case "TSHR":
                return TURN_SHARP_RIGHT;
            case "KL":
                return KEEP_LEFT;
            case "KR":
                return KEEP_RIGHT;
            case "TU":
                return UTURN_LEFT;
            case "TRU":
                return UTURN_RIGHT;
            case "OFFR":
                return NONE; // off-route: treat like "nothing to show" for now
            default:
                return UNKNOWN;
        }
    }

    /**
     * Roundabout exit number encoded as the numeric suffix of "RNDB"/"RNLB",
     * e.g. "RNDB2" -> 2. Returns 0 (unknown) if not a roundabout string or the
     * suffix isn't parseable.
     */
    public static int roundaboutExit(String xml) {
        if (xml == null) return 0;
        String digits = null;
        if (xml.startsWith("RNDB")) {
            digits = xml.substring(4);
        } else if (xml.startsWith("RNLB")) {
            digits = xml.substring(4);
        }
        if (digits == null || digits.isEmpty()) return 0;
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String name(int maneuver) {
        switch (maneuver) {
            case NONE: return "NONE";
            case DEPART: return "DEPART";
            case ARRIVE: return "ARRIVE";
            case STRAIGHT: return "STRAIGHT";
            case TURN_SLIGHT_LEFT: return "TURN_SLIGHT_LEFT";
            case TURN_LEFT: return "TURN_LEFT";
            case TURN_SHARP_LEFT: return "TURN_SHARP_LEFT";
            case TURN_SLIGHT_RIGHT: return "TURN_SLIGHT_RIGHT";
            case TURN_RIGHT: return "TURN_RIGHT";
            case TURN_SHARP_RIGHT: return "TURN_SHARP_RIGHT";
            case KEEP_LEFT: return "KEEP_LEFT";
            case KEEP_RIGHT: return "KEEP_RIGHT";
            case UTURN_LEFT: return "UTURN_LEFT";
            case UTURN_RIGHT: return "UTURN_RIGHT";
            case ROUNDABOUT: return "ROUNDABOUT";
            default: return "UNKNOWN";
        }
    }
}
