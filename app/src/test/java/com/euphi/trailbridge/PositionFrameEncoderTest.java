package com.euphi.trailbridge;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;

public class PositionFrameEncoderTest {

    private static PositionState fix(boolean hr, boolean cad, int sim) {
        return new PositionState(true, 525163000, 133777000, true, 34, true, 420, true, 8750, true, 50,
                1000, 0L, hr, 132, cad, 85, false, 0, false, 0, sim);
    }

    private static PositionState fixWithHeightAndPower(int baroDm, int watts) {
        return new PositionState(true, 525163000, 133777000, true, 34, true, 420, true, 8750, true, 50,
                1000, 0L, false, 0, false, 0, true, baroDm, true, watts,
                PositionState.SIM_POSITION | PositionState.SIM_SENSORS);
    }

    @Test
    public void gpsFixWithoutSensorsIsUnchanged() {
        byte[] b = PositionFrameEncoder.encode(fix(false, false, 0), 1320);
        assertArrayEquals(new byte[]{
                1, 1,
                1, 4, (byte) 0xF8, 0x59, 0x4D, 0x1F,       // LATITUDE_E7 = 525163000
                2, 4, 0x68, 0x46, (byte) 0xF9, 0x07,       // LONGITUDE_E7 = 133777000
                3, 4, 34, 0, 0, 0,                         // ALTITUDE_M
                4, 4, (byte) 0xA4, 0x01, 0, 0,             // SPEED_CMS = 420
                5, 2, 0x2E, 0x22,                          // BEARING_DEG_X100 = 8750
                6, 2, 50, 0,                               // ACCURACY_M_X10 = 50
                7, 4, 0x40, 0x01, 0, 0},                   // FIX_AGE_MS = 320
                b);
    }

    private static final int BOTH = PositionState.SIM_POSITION | PositionState.SIM_SENSORS;

    @Test
    public void simulatedSensorsFollowAfterTheGpsTags() {
        byte[] plain = PositionFrameEncoder.encode(fix(false, false, 0), 1320);
        byte[] b = PositionFrameEncoder.encode(fix(true, true, BOTH), 1320);
        assertArrayEquals(plain, Arrays.copyOf(b, plain.length));
        assertArrayEquals(new byte[]{
                9, 1, (byte) 132,                          // HEART_RATE_BPM
                10, 1, 85,                                 // CADENCE_RPM
                11, 1, 3},                                 // SIM_FLAGS = SIM_POSITION | SIM_SENSORS
                Arrays.copyOfRange(b, plain.length, b.length));
    }

    @Test
    public void simulatedWithoutSensorsJustSaysSo() {
        byte[] plain = PositionFrameEncoder.encode(fix(false, false, 0), 1320);
        byte[] b = PositionFrameEncoder.encode(fix(false, false, BOTH), 1320);
        assertArrayEquals(new byte[]{11, 1, 3}, Arrays.copyOfRange(b, plain.length, b.length));
    }

    @Test
    public void realSensorValuesCarryNoSimFlag() {
        // a heart rate strap the phone relays (not built yet): values, but nothing simulated
        byte[] plain = PositionFrameEncoder.encode(fix(false, false, 0), 1320);
        byte[] b = PositionFrameEncoder.encode(fix(true, false, 0), 1320);
        assertArrayEquals(new byte[]{9, 1, (byte) 132}, Arrays.copyOfRange(b, plain.length, b.length));
    }

    @Test
    public void onlyThePositionSimulated() {
        byte[] plain = PositionFrameEncoder.encode(fix(false, false, 0), 1320);
        byte[] b = PositionFrameEncoder.encode(fix(false, false, PositionState.SIM_POSITION), 1320);
        assertArrayEquals(new byte[]{11, 1, 1}, Arrays.copyOfRange(b, plain.length, b.length));
    }

    @Test
    public void baroHeightAndPowerAfterTheSimFlags() {
        byte[] plain = PositionFrameEncoder.encode(fix(false, false, 0), 1320);
        // 345.6 m = 3456 dm = 0x0D80; -12.3 m would be negative, below sea level is legal
        byte[] b = PositionFrameEncoder.encode(fixWithHeightAndPower(3456, 250), 1320);
        assertArrayEquals(plain, Arrays.copyOf(b, plain.length));
        assertArrayEquals(new byte[]{
                11, 1, 3,                                  // SIM_FLAGS
                12, 4, (byte) 0x80, 0x0D, 0, 0,            // BARO_HEIGHT_DM = 3456
                13, 2, (byte) 250, 0},                     // POWER_W = 250
                Arrays.copyOfRange(b, plain.length, b.length));
        b = PositionFrameEncoder.encode(fixWithHeightAndPower(-123, 0), 1320);
        assertArrayEquals(new byte[]{
                11, 1, 3,
                12, 4, (byte) 0x85, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,   // -123 dm
                13, 2, 0, 0},                              // coasting = 0 W, still sent
                Arrays.copyOfRange(b, plain.length, b.length));
    }

    @Test
    public void noFix() {
        assertArrayEquals(new byte[]{1, 2}, PositionFrameEncoder.encode(PositionState.NONE));
        assertEquals(2, PositionFrameEncoder.encode(PositionState.NONE, 5).length);
    }

    @Test
    public void heightAboveSeaLevelOfARealFixComesLast() {
        byte[] plain = PositionFrameEncoder.encode(fix(false, false, 0), 1320);
        // 359.4 m = 3594 dm = 0x0E0A
        PositionState real = new PositionState(true, 525163000, 133777000, true, 34, true, 420, true, 8750, true, 50,
                1000, 0L, false, 0, false, 0, false, 0, false, 0, 0, true, 3594);
        byte[] b = PositionFrameEncoder.encode(real, 1320);
        assertArrayEquals(plain, Arrays.copyOf(b, plain.length));
        assertArrayEquals(new byte[]{14, 4, 0x0A, 0x0E, 0, 0}, Arrays.copyOfRange(b, plain.length, b.length));
    }
}
