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
    public static final int TAG_UTC_TIME_MS = 0x08;
    public static final int TAG_HEART_RATE_BPM = 0x09;
    public static final int TAG_CADENCE_RPM = 0x0A;
    public static final int TAG_SIM_FLAGS = 0x0B;
    public static final int TAG_BARO_HEIGHT_DM = 0x0C;
    public static final int TAG_POWER_W = 0x0D;
    public static final int TAG_MSL_ALTITUDE_DM = 0x0E;

    private PositionFrameEncoder() {
    }

    public static byte[] hello() {
        return new byte[]{PROTOCOL_VERSION, MSG_HELLO};
    }

    public static byte[] encode(PositionState s) {
        return encode(s, s.hasFix ? SystemClock.elapsedRealtime() : 0);
    }

    /** @param nowElapsedMs SystemClock.elapsedRealtime() at send time (FIX_AGE_MS = now - fix). */
    static byte[] encode(PositionState s, long nowElapsedMs) {
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
        long ageMs = nowElapsedMs - s.fixElapsedRealtimeMs;
        writeS32(out, TAG_FIX_AGE_MS, (int) ageMs);
        if (s.fixUtcTimeMs > 0) {
            writeS64(out, TAG_UTC_TIME_MS, s.fixUtcTimeMs);
        }
        if (s.hasHeartRate) {
            writeU8(out, TAG_HEART_RATE_BPM, s.heartRateBpm);
        }
        if (s.hasCadence) {
            writeU8(out, TAG_CADENCE_RPM, s.cadenceRpm);
        }
        if (s.simFlags != 0) {
            writeU8(out, TAG_SIM_FLAGS, s.simFlags);
        }
        if (s.hasBaroHeight) {
            writeS32(out, TAG_BARO_HEIGHT_DM, s.baroHeightDm);
        }
        if (s.hasPower) {
            writeU16(out, TAG_POWER_W, Math.max(0, Math.min(65535, s.powerW)));
        }
        if (s.hasMslAltitude) {
            writeS32(out, TAG_MSL_ALTITUDE_DM, s.mslAltitudeDm);
        }

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

    private static void writeS64(ByteArrayOutputStream out, int tag, long value) {
        out.write(tag);
        out.write(8);
        for (int i = 0; i < 8; i++) {
            out.write((int) (value >>> (8 * i)) & 0xFF);
        }
    }

    private static void writeU8(ByteArrayOutputStream out, int tag, int value) {
        out.write(tag);
        out.write(1);
        out.write(Math.max(0, Math.min(255, value)));
    }

    private static void writeU16(ByteArrayOutputStream out, int tag, int value) {
        out.write(tag);
        out.write(2);
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }
}
