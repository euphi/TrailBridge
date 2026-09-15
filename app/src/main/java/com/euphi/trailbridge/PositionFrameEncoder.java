package com.euphi.trailbridge;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;

/**
 * Encodes a PositionState into the GPS-Positions-Service wire format defined
 * in PROTOCOL.md. Keep the two in sync -- this class IS the protocol, the
 * .md file just explains it.
 */
public final class PositionFrameEncoder {

    public static final int PROTOCOL_VERSION = 1;

    public static final int MSG_HELLO = 0x00;
    public static final int MSG_POSITION_UPDATE = 0x01;
    public static final int MSG_POSITION_NONE = 0x02;

    public static final int TAG_LATITUDE_E7 = 0x01;
    public static final int TAG_LONGITUDE_E7 = 0x02;
    public static final int TAG_ALTITUDE_M = 0x03;
    public static final int TAG_SPEED_CMS = 0x04;
    public static final int TAG_BEARING_DEG_X100 = 0x05;
    public static final int TAG_ACCURACY_M_X10 = 0x06;
    public static final int TAG_FIX_AGE_MS = 0x07;

    private PositionFrameEncoder() {
    }

    public static byte[] hello() {
        return new byte[]{PROTOCOL_VERSION, MSG_HELLO};
    }

    public static byte[] encode(PositionState s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PROTOCOL_VERSION);

        if (!s.hasFix) {
            out.write(MSG_POSITION_NONE);
            return out.toByteArray();
        }
        out.write(MSG_POSITION_UPDATE);

        writeS32(out, TAG_LATITUDE_E7, s.latitudeE7);
        writeS32(out, TAG_LONGITUDE_E7, s.longitudeE7);
        if (s.hasAltitude) {
            writeS32(out, TAG_ALTITUDE_M, s.altitudeM);
        }
        if (s.hasSpeed) {
            writeS32(out, TAG_SPEED_CMS, s.speedCms);
        }
        if (s.hasBearing) {
            writeU16(out, TAG_BEARING_DEG_X100, s.bearingDegX100);
        }
        if (s.hasAccuracy) {
            writeU16(out, TAG_ACCURACY_M_X10, s.accuracyMx10);
        }
        long ageMs = SystemClock.elapsedRealtime() - s.fixElapsedRealtimeMs;
        writeS32(out, TAG_FIX_AGE_MS, (int) ageMs);

        return out.toByteArray();
    }

    /** Two's-complement byte splitting works identically for signed and
     *  unsigned values, so this doubles as the uint32 writer too. */
    private static void writeS32(ByteArrayOutputStream out, int tag, int value) {
        out.write(tag);
        out.write(4);
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream out, int tag, int value) {
        out.write(tag);
        out.write(2);
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }
}
