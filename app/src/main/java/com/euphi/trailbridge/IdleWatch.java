package com.euphi.trailbridge;

/**
 * When the service has had nothing useful to do for long enough to end itself (energy: GPS at
 * 1 Hz and the foreground service otherwise run until the rider stops them by hand). Pure
 * logic, no Android dependencies; the service feeds it and sets the alarm for {@link #dueAtMs}.
 *
 * Useful means a BikeComputer is connected. Without one, the navi mode on the phone's own
 * screen still counts as long as the rider really moves (a test ride's fake position does not,
 * and neither does a test ride in general: it is only worth something with a BikeComputer).
 */
final class IdleWatch {

    /** Below this a fix does not count as movement (m/s; GPS speed jitters around 0.3 when standing). */
    static final float MOVING_MS = 1.0f;

    private long idleSinceMs;   // -1: not idle

    /** @param nowMs elapsedRealtime at the start of the service: nobody has connected yet, so it counts from there */
    IdleWatch(long nowMs) {
        idleSinceMs = nowMs;
    }

    static boolean active(boolean clientConnected, boolean naviOpen, boolean realMoving) {
        return clientConnected || (naviOpen && realMoving);
    }

    /** Whether a real GPS fix means the rider is moving. */
    static boolean moving(PositionState real) {
        return real != null && real.hasFix && real.hasSpeed && real.speedCms >= MOVING_MS * 100;
    }

    /** @return whether idle started or ended with this (the alarm needs setting or cancelling) */
    boolean update(long nowMs, boolean active) {
        if (active) {
            boolean was = idleSinceMs >= 0;
            idleSinceMs = -1;
            return was;
        }
        if (idleSinceMs >= 0) return false;
        idleSinceMs = nowMs;
        return true;
    }

    /** Idle starts over from now (somebody looked at the app); no effect while active. */
    void restart(long nowMs) {
        if (idleSinceMs >= 0) idleSinceMs = nowMs;
    }

    /** elapsedRealtime at which the service should end, -1 while active or if the timeout is off (&lt;= 0). */
    long dueAtMs(long timeoutMs) {
        return idleSinceMs < 0 || timeoutMs <= 0 ? -1 : idleSinceMs + timeoutMs;
    }

    boolean expired(long nowMs, long timeoutMs) {
        long due = dueAtMs(timeoutMs);
        return due >= 0 && nowMs >= due;
    }
}
