package com.euphi.trailbridge;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

/**
 * Where the planned routes come from (see {@link PlannedRouteClient}) and which revision of them is
 * already here. Kept apart from the service's other preferences: the address is the user's.
 */
final class PlannedRouteSettings {
    private static final String PREFS = "planned_routes";
    private static final String KEY_URL = "base_url";
    private static final String KEY_OSMAND = "send_to_osmand";
    private static final String KEY_LOADED = "loaded_revision";
    private static final String KEY_CONFIRMED = "confirmed_revision";

    private final SharedPreferences prefs;

    PlannedRouteSettings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String baseUrl() {
        return prefs.getString(KEY_URL, "");
    }

    /**
     * Another service has other revisions: the ones remembered are forgotten when the address changes
     * (a coincidentally equal revision would otherwise never load).
     */
    void setBaseUrl(String baseUrl) {
        String url = baseUrl.trim();
        SharedPreferences.Editor edit = prefs.edit().putString(KEY_URL, url);
        if (!url.equals(baseUrl())) edit.remove(KEY_LOADED).remove(KEY_CONFIRMED);
        edit.apply();
    }

    /** Whether a loaded planned route is also put into OsmAnd's track list. On unless switched off. */
    boolean sendToOsmAnd() {
        return prefs.getBoolean(KEY_OSMAND, true);
    }

    void setSendToOsmAnd(boolean send) {
        prefs.edit().putBoolean(KEY_OSMAND, send).apply();
    }

    /** The client for the saved address, or null while there is none (= switched off). */
    @Nullable
    PlannedRouteClient client() {
        String url = baseUrl().trim();
        return url.isEmpty() ? null : new PlannedRouteClient(url, "");
    }

    /** The revision that was loaded as the route, null if none yet. */
    @Nullable
    String loadedRevision() {
        return prefs.getString(KEY_LOADED, null);
    }

    /** The revision the service knows to be fetched; behind {@link #loadedRevision()}, a confirmation is owed. */
    @Nullable
    String confirmedRevision() {
        return prefs.getString(KEY_CONFIRMED, null);
    }

    void setLoadedRevision(String revision) {
        prefs.edit().putString(KEY_LOADED, revision).apply();
    }

    void setConfirmedRevision(String revision) {
        prefs.edit().putString(KEY_CONFIRMED, revision).apply();
    }
}
