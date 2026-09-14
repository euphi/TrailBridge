package com.euphi.trailbridge;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Encodes a NavState into the wire format defined in PROTOCOL.md. Keep the
 * two in sync -- this class IS the protocol, the .md file just explains it.
 */
public final class NavFrameEncoder {

    public static final int PROTOCOL_VERSION = 1;

    public static final int MSG_HELLO = 0x00;
    public static final int MSG_NAV_UPDATE = 0x01;
    public static final int MSG_NAV_NONE = 0x02;

    public static final int TAG_MANEUVER = 0x01;
    public static final int TAG_MANEUVER_DISTANCE_M = 0x02;
    public static final int TAG_ROUNDABOUT_EXIT = 0x03;
    public static final int TAG_STREET_NAME = 0x04;
    public static final int TAG_NEXT_MANEUVER = 0x05;
    public static final int TAG_NEXT_MANEUVER_DISTANCE_M = 0x06;
    public static final int TAG_NEXT_STREET_NAME = 0x07;
    public static final int TAG_REMAINING_DISTANCE_M = 0x08;
    public static final int TAG_REMAINING_TIME_S = 0x09;

    /** Comfortably under the 253 usable bytes of a 256-byte ATT_MTU, and
     *  still short if MTU negotiation hasn't finished when we first send. */
    private static final int MAX_STRING_BYTES = 48;

    private NavFrameEncoder() {
    }

    public static byte[] hello() {
        return new byte[]{PROTOCOL_VERSION, MSG_HELLO};
    }

    public static byte[] encode(NavState s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PROTOCOL_VERSION);

        if (!s.navigating) {
            out.write(MSG_NAV_NONE);
            return out.toByteArray();
        }
        out.write(MSG_NAV_UPDATE);

        writeU8(out, TAG_MANEUVER, s.maneuver);
        writeU32(out, TAG_MANEUVER_DISTANCE_M, s.maneuverDistanceM);
        if (s.maneuver == Maneuver.ROUNDABOUT && s.roundaboutExit > 0) {
            writeU8(out, TAG_ROUNDABOUT_EXIT, s.roundaboutExit);
        }
        writeString(out, TAG_STREET_NAME, s.streetName);

        if (s.nextManeuver != Maneuver.NONE) {
            writeU8(out, TAG_NEXT_MANEUVER, s.nextManeuver);
            writeU32(out, TAG_NEXT_MANEUVER_DISTANCE_M, s.nextManeuverDistanceM);
            writeString(out, TAG_NEXT_STREET_NAME, s.nextStreetName);
        }

        writeU32(out, TAG_REMAINING_DISTANCE_M, s.remainingDistanceM);
        writeU32(out, TAG_REMAINING_TIME_S, s.remainingTimeS);

        return out.toByteArray();
    }

    private static void writeU8(ByteArrayOutputStream out, int tag, int value) {
        out.write(tag);
        out.write(1);
        out.write(value & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, int tag, int value) {
        out.write(tag);
        out.write(4);
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeString(ByteArrayOutputStream out, int tag, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) {
            // Don't cut a multi-byte UTF-8 sequence in half: back up until we
            // land on a byte that isn't a continuation byte (10xxxxxx).
            int cut = MAX_STRING_BYTES;
            while (cut > 0 && (bytes[cut] & 0xC0) == 0x80) {
                cut--;
            }
            bytes = Arrays.copyOf(bytes, cut);
        }
        out.write(tag);
        out.write(bytes.length);
        out.write(bytes, 0, bytes.length);
    }
}
