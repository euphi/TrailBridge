package com.euphi.trailbridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class IdleWatchTest {

    private static final long MIN = 60_000L;
    private static final long TIMEOUT = 30 * MIN;

    @Test
    public void aConnectedBikeComputerIsAlwaysUseful() {
        assertTrue(IdleWatch.active(true, false, false));
        assertTrue(IdleWatch.active(true, true, false));
    }

    @Test
    public void naviModeWithoutBikeComputerCountsOnlyWhileReallyMoving() {
        assertTrue(IdleWatch.active(false, true, true));
        assertFalse(IdleWatch.active(false, true, false));
        assertFalse(IdleWatch.active(false, false, true));   // moving alone, screen off: nobody uses it
        assertFalse(IdleWatch.active(false, false, false));
    }

    @Test
    public void countsFromTheStartOfTheService() {
        IdleWatch w = new IdleWatch(1000);
        assertEquals(1000 + TIMEOUT, w.dueAtMs(TIMEOUT));
        assertFalse(w.expired(1000 + TIMEOUT - 1, TIMEOUT));
        assertTrue(w.expired(1000 + TIMEOUT, TIMEOUT));
    }

    @Test
    public void activityCancelsAndTheEndOfItStartsTheTimeAgain() {
        IdleWatch w = new IdleWatch(0);
        assertTrue(w.update(10 * MIN, true));                  // client connects: idle ends
        assertEquals(-1, w.dueAtMs(TIMEOUT));
        assertFalse(w.expired(10 * TIMEOUT, TIMEOUT));
        assertFalse(w.update(20 * MIN, true));                 // still active: no change
        assertTrue(w.update(50 * MIN, false));                 // client gone: timer from here
        assertEquals(50 * MIN + TIMEOUT, w.dueAtMs(TIMEOUT));
        assertFalse(w.update(60 * MIN, false));                // idle goes on: the start stays
        assertEquals(50 * MIN + TIMEOUT, w.dueAtMs(TIMEOUT));
    }

    @Test
    public void neverMeansNoEnd() {
        IdleWatch w = new IdleWatch(0);
        assertEquals(-1, w.dueAtMs(0));
        assertFalse(w.expired(1000 * MIN, 0));
    }

    @Test
    public void lookingAtTheAppRestartsTheIdleTimeButNotAnActiveOne() {
        IdleWatch w = new IdleWatch(0);
        w.restart(25 * MIN);
        assertEquals(25 * MIN + TIMEOUT, w.dueAtMs(TIMEOUT));
        w.update(26 * MIN, true);
        w.restart(40 * MIN);
        assertEquals(-1, w.dueAtMs(TIMEOUT));
    }

    @Test
    public void onlyARealFixAboveWalkingPaceIsMovement() {
        assertFalse(IdleWatch.moving(null));
        assertFalse(IdleWatch.moving(PositionState.NONE));
        assertTrue(IdleWatch.moving(fix(true, 250)));
        assertFalse(IdleWatch.moving(fix(true, 30)));          // GPS jitter while standing
        assertFalse(IdleWatch.moving(fix(false, 0)));
    }

    @Test
    public void theChoicesCycleAndAStrayValueStartsOver() {
        assertEquals(30, AutoStopSettings.next(15));
        assertEquals(0, AutoStopSettings.next(120));
        assertEquals(15, AutoStopSettings.next(0));
        assertEquals(15, AutoStopSettings.next(7));
    }

    private static PositionState fix(boolean hasSpeed, int speedCms) {
        return new PositionState(true, 0, 0, false, 0, hasSpeed, speedCms, false, 0, false, 0, 0L, 0L);
    }
}
