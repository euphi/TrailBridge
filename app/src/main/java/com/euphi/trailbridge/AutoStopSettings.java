package com.euphi.trailbridge;

import android.content.Context;
import android.content.SharedPreferences;

/** How long the service may be idle (see {@link IdleWatch}) before it ends itself. */
final class AutoStopSettings {
    private static final String PREFS = "auto_stop";
    private static final String KEY_MINUTES = "minutes";

    /** The choices in the order the settings button cycles through them; 0 = never. */
    static final int[] CHOICES_MIN = {15, 30, 60, 120, 0};
    static final int DEFAULT_MIN = 30;

    private final SharedPreferences prefs;

    AutoStopSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 0 = never. */
    int minutes() {
        return prefs.getInt(KEY_MINUTES, DEFAULT_MIN);
    }

    void setMinutes(int minutes) {
        prefs.edit().putInt(KEY_MINUTES, minutes).apply();
    }

    /** The choice after the current one; a stored value that is not a choice any more starts over at the first. */
    static int next(int minutes) {
        for (int i = 0; i < CHOICES_MIN.length; i++) {
            if (CHOICES_MIN[i] == minutes) return CHOICES_MIN[(i + 1) % CHOICES_MIN.length];
        }
        return CHOICES_MIN[0];
    }
}
