package com.euphi.trailbridge;

import java.io.ByteArrayOutputStream;

/**
 * Encodes the elevation-profile service's frames, see PROTOCOL.md ("Höhenprofil-
 * Service"). Keep the two in sync -- this class IS the protocol.
 */
public final class ProfileFrameEncoder {

    public static final int PROTOCOL_VERSION = 1;

    public static final int MSG_HELLO = 0x00;
    public static final int MSG_PROFILE_UPDATE = 0x01;
    public static final int MSG_PROFILE_NONE = 0x02;

    public static final int TAG_START_REMAINING_DISTANCE_M = 0x01;
    public static final int TAG_STEP_M = 0x02;
    public static final int TAG_BASE_ALT_DM = 0x03;
    public static final int TAG_DELTAS_DM = 0x04;

    /** version + type, then START (6) + STEP (3) + BASE (4) + the DELTAS header (2). */
    private static final int OVERHEAD = 2 + 6 + 3 + 4 + 2;
    private static final int MAX_STEPS = 200;
    /** A profile shorter than this (8 * 25 m = 200 m) isn't worth drawing. */
    public static final int MIN_STEPS = 8;

    private ProfileFrameEncoder() {
    }

    public static byte[] hello() {
        return new byte[]{PROTOCOL_VERSION, MSG_HELLO};
    }

    public static byte[] none() {
        return new byte[]{PROTOCOL_VERSION, MSG_PROFILE_NONE};
    }

    /**
     * How many deltas fit into one indicate of at most maxPayload bytes
     * (ATT_MTU - 3). 0 if not even MIN_STEPS fit.
     */
    public static int maxSteps(int maxPayload) {
        int n = Math.min(MAX_STEPS, maxPayload - OVERHEAD);
        return n < MIN_STEPS ? 0 : n;
    }

    public static byte[] encode(ProfileFrame f) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(PROTOCOL_VERSION);
        out.write(MSG_PROFILE_UPDATE);

        out.write(TAG_START_REMAINING_DISTANCE_M);
        out.write(4);
        writeLe(out, f.startRemainingM, 4);

        out.write(TAG_STEP_M);
        out.write(1);
        out.write(f.stepM & 0xFF);

        out.write(TAG_BASE_ALT_DM);
        out.write(2);
        writeLe(out, f.baseAltDm, 2);

        out.write(TAG_DELTAS_DM);
        out.write(f.deltasDm.length);
        out.write(f.deltasDm, 0, f.deltasDm.length);
        return out.toByteArray();
    }

    private static void writeLe(ByteArrayOutputStream out, int value, int bytes) {
        for (int i = 0; i < bytes; i++) {
            out.write((value >>> (8 * i)) & 0xFF);
        }
    }
}
