package com.euphi.bikenavrelay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import net.osmand.aidlapi.IOsmAndAidlCallback;
import net.osmand.aidlapi.IOsmAndAidlInterface;
import net.osmand.aidlapi.gpx.AGpxBitmap;
import net.osmand.aidlapi.info.AppInfoParams;
import net.osmand.aidlapi.logcat.OnLogcatMessageParams;
import net.osmand.aidlapi.navigation.ADirectionInfo;
import net.osmand.aidlapi.navigation.ANavigationUpdateParams;
import net.osmand.aidlapi.navigation.OnVoiceNavigationParams;
import net.osmand.aidlapi.search.SearchResult;

import java.util.List;

/**
 * Binds to OsmAnd's AIDL service (OsmandAidlServiceV2), subscribes for turn
 * push notifications, and polls getAppInfo() for the richer trip data
 * (street names, the maneuver after next, remaining distance/time).
 *
 * Every method/field/action name in here is verified against OsmAnd's own
 * source (osmandapp/OsmAnd, master branch, Sept. 2026):
 *  - Service action + gating:  OsmAnd/src/net/osmand/aidl/OsmandAidlServiceV2.java
 *  - Interface + callback:     OsmAnd-api/src/net/osmand/aidlapi/IOsmAndAidlInterface.aidl,
 *                              IOsmAndAidlCallback.aidl
 *  - turnInfo bundle keys:     OsmAnd/src/net/osmand/plus/helpers/ExternalApiHelper.java
 *                              (PARAM_NT_DIRECTION_*, PARAM_NT_DISTANCE, prefixes
 *                              "current_" / "next_" / "after_next" -- note: no
 *                              underscore after "after_next", that's not a typo)
 *  - turn type strings:        OsmAnd-java/src/main/java/net/osmand/router/TurnType.java
 *
 * Not yet in this class (Meilenstein 1 is "prove the data is right"): no BLE
 * here at all. onNavState() is called on the main thread with whatever we
 * most recently assembled; MainActivity just displays it for now.
 */
public class OsmAndLink {

    private static final String TAG = "OsmAndLink";

    /** Free, paid, nightly build -- tried in this order. */
    private static final String[] CANDIDATE_PACKAGES = {"net.osmand.plus", "net.osmand", "net.osmand.dev"};

    private static final String SERVICE_ACTION = "net.osmand.aidl.OsmandAidlServiceV2";

    /** How often we poll getAppInfo() for street names / ETA / after-next turn. */
    private static final long POLL_INTERVAL_MS = 1000L;

    /**
     * Right as you pass a turn, OsmAnd briefly reports no next-maneuver info
     * while it recalculates (turnInfo null, or next_turn_type/distance not
     * updated yet) -- observed to clear again well under 2s later. Only
     * relay a switch to "not navigating" once it has persisted this long, so
     * that blip doesn't flash a "no navigation" icon on the BikeComputer.
     */
    private static final long NONE_DEBOUNCE_MS = 2500L;

    public interface Listener {
        void onStatusChanged(String status);

        void onNavState(NavState state);
    }

    private final Context appContext;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private IOsmAndAidlInterface iface;
    private boolean bound = false;
    private boolean subscribed = false;
    private NavState lastState = NavState.NONE;
    private long noNavSinceMs = -1L;

    private final Runnable pollTask = new Runnable() {
        @Override
        public void run() {
            poll();
            mainHandler.postDelayed(this, POLL_INTERVAL_MS);
        }
    };

    public OsmAndLink(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
    }

    public void start() {
        String pkg = findInstalledOsmAnd();
        if (pkg == null) {
            setStatus("OsmAnd nicht gefunden (net.osmand.plus / net.osmand / net.osmand.dev)."
                    + " Ist es installiert und in AndroidManifest <queries> eingetragen?");
            return;
        }
        Intent intent = new Intent(SERVICE_ACTION);
        intent.setPackage(pkg);
        boolean ok = appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!ok) {
            setStatus(pkg + " gefunden, aber bindService() abgelehnt.");
        } else {
            setStatus("verbinde mit " + pkg + " ...");
        }
    }

    public void stop() {
        mainHandler.removeCallbacks(pollTask);
        if (bound) {
            appContext.unbindService(connection);
            bound = false;
        }
        iface = null;
        subscribed = false;
    }

    /**
     * Package visibility is restricted from Android 11 on -- without the
     * matching <queries> entries in AndroidManifest.xml this returns null
     * even when OsmAnd is plainly installed.
     */
    private String findInstalledOsmAnd() {
        PackageManager pm = appContext.getPackageManager();
        for (String pkg : CANDIDATE_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0);
                return pkg;
            } catch (PackageManager.NameNotFoundException e) {
                // try next candidate
            }
        }
        return null;
    }

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            iface = IOsmAndAidlInterface.Stub.asInterface(service);
            bound = true;
            subscribe();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            subscribed = false;
            iface = null;
            mainHandler.removeCallbacks(pollTask);
            setStatus("OsmAnd-Verbindung verloren (App beendet/aktualisiert?)");
        }
    };

    private void subscribe() {
        IOsmAndAidlInterface i = iface;
        if (i == null) return;
        try {
            ANavigationUpdateParams params = new ANavigationUpdateParams();
            params.setSubscribeToUpdates(true);
            params.setCallbackId(0L);
            long id = i.registerForNavigationUpdates(params, callback);
            subscribed = id >= 0;
            if (subscribed) {
                setStatus("verbunden, warte auf Navigationsdaten");
                mainHandler.removeCallbacks(pollTask);
                mainHandler.post(pollTask);
            } else {
                // This is the expected result on first contact -- see class
                // javadoc: OsmAnd registers unknown callers as disabled.
                setStatus("NICHT FREIGESCHALTET -- in OsmAnd: Menü > Plugins > "
                        + "BikeNavRelay > aktivieren, dann diese App neu starten");
            }
        } catch (Exception e) {
            subscribed = false;
            Log.w(TAG, "registerForNavigationUpdates failed", e);
            setStatus("Fehler beim Registrieren: " + e.getMessage());
        }
    }

    /** Re-attempt the subscription without re-binding -- for a "Retry" button
     *  after the user has gone into OsmAnd's plugin settings. */
    public void retrySubscribe() {
        if (iface != null && !subscribed) {
            subscribe();
        } else if (iface == null) {
            start();
        }
    }

    private void poll() {
        IOsmAndAidlInterface i = iface;
        if (i == null) return;
        AppInfoParams info;
        try {
            info = i.getAppInfo();
        } catch (Exception e) {
            Log.w(TAG, "getAppInfo failed", e);
            return;
        }
        NavState state = parse(info);

        if (!state.navigating) {
            long now = SystemClock.elapsedRealtime();
            if (noNavSinceMs < 0) {
                noNavSinceMs = now;
            }
            if (now - noNavSinceMs < NONE_DEBOUNCE_MS) {
                return; // transient blip -- keep showing the last real state
            }
        } else {
            noNavSinceMs = -1L;
        }

        if (!state.equals(lastState)) {
            lastState = state;
            if (listener != null) {
                listener.onNavState(state);
            }
        }
    }

    /**
     * Build a NavState from AppInfoParams.getTurnInfo(). Bundle keys and their
     * origin are documented on the class itself -- see ExternalApiHelper in
     * the OsmAnd source for the authoritative list.
     */
    private NavState parse(AppInfoParams info) {
        if (info == null) {
            return NavState.NONE;
        }
        Bundle turnInfo = info.getTurnInfo();
        if (turnInfo == null) {
            // getAppInfo() only fills turnInfo once routingHelper.isRouteCalculated();
            // null here means "not currently navigating", not an error.
            return NavState.NONE;
        }

        String nextType = turnInfo.getString("next_turn_type");
        String nextName = turnInfo.getString("next_turn_name");
        int nextDistance = turnInfo.getInt("next_turn_distance", 0);

        // Note the missing underscore: OsmAnd really does write "after_next"
        // + "turn_type" = "after_nextturn_type", not "after_next_turn_type".
        String afterType = turnInfo.getString("after_nextturn_type");
        String afterName = turnInfo.getString("after_nextturn_name");
        int afterDistance = turnInfo.getInt("after_nextturn_distance", 0);

        int maneuver = Maneuver.fromOsmAndXml(nextType);
        int exit = Maneuver.roundaboutExit(nextType);
        int nextManeuver = Maneuver.fromOsmAndXml(afterType);

        boolean navigating = maneuver != Maneuver.NONE || nextDistance > 0;

        return new NavState(navigating, maneuver, nextDistance, exit, nextName,
                nextManeuver, afterDistance, afterName,
                info.getLeftDistance(), info.getLeftTime());
    }

    private void setStatus(String status) {
        Log.i(TAG, status);
        if (listener != null) {
            mainHandler.post(() -> listener.onStatusChanged(status));
        }
    }

    /**
     * All methods must be implemented -- AIDL has no partial stubs -- but only
     * updateNavigationInfo() triggers anything here. It mainly exists so a
     * turn change is reflected within milliseconds rather than waiting up to
     * POLL_INTERVAL_MS for the next poll() tick; poll() still does the actual
     * work since it alone has the street names / after-next / ETA fields.
     */
    private final IOsmAndAidlCallback.Stub callback = new IOsmAndAidlCallback.Stub() {
        @Override
        public void updateNavigationInfo(ADirectionInfo directionInfo) {
            mainHandler.post(OsmAndLink.this::poll);
        }

        @Override
        public void onSearchComplete(List<SearchResult> resultSet) {
        }

        @Override
        public void onUpdate() {
        }

        @Override
        public void onAppInitialized() {
        }

        @Override
        public void onGpxBitmapCreated(AGpxBitmap bitmap) {
        }

        @Override
        public void onContextMenuButtonClicked(int buttonId, String pointId, String layerId) {
        }

        @Override
        public void onVoiceRouterNotify(OnVoiceNavigationParams params) {
        }

        @Override
        public void onKeyEvent(KeyEvent params) {
        }

        @Override
        public void onLogcatMessage(OnLogcatMessageParams params) {
        }
    };
}
