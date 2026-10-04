package com.euphi.trailbridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Haelt OsmAndLink und BikeComputerGattServer am Leben, unabhaengig vom
 * Lebenszyklus von MainActivity. Ohne diesen Service wuerde
 * Activity.onStop() (z.B. beim Sperren des Bildschirms -- genau der
 * Anwendungsfall dieser App auf dem Fahrrad) Advertising und GATT-Server
 * sofort abwuergen.
 *
 * Zusaetzlich spielt der Service eine importierte GPX-Route ab
 * (RouteNavigator, gespeist von GpsLink) -- solange sie aktiv ist, ersetzt
 * sie die OsmAnd-Daten im Nav-Service, sendet das Hoehenprofil und stellt die
 * Streckenuebersicht (Wegpunkte, Anstiege) zum Lesen bereit.
 *
 * Fuer Testzwecke kann die Route auch "abgefahren" werden (RoutePlayer): der
 * Service erzeugt dann einmal pro Sekunde eine Fake-Position samt simulierten
 * Sensorwerten (Geschwindigkeit, Puls, Trittfrequenz) und schickt sie statt
 * des echten GPS-Fixes an den BikeComputer und in den RouteNavigator.
 *
 * MainActivity bindet sich nur noch dran, um Status-Updates fuers UI zu
 * bekommen; der Service selbst laeuft als Foreground Service mit
 * Dauerbenachrichtigung weiter, auch wenn keine Activity gebunden ist.
 */
public class TrailBridgeService extends Service
        implements OsmAndLink.Listener, GpsLink.Listener, BikeComputerGattServer.Listener {

    public interface UiListener {
        void onStatusChanged(String status, OsmAndLink.Status kind);
        void onNavState(NavState state);
        void onGpsStatusChanged(String status);
        void onPositionUpdate(PositionState state);
        void onSubscriberCountChanged(int count);
        void onBleError(String message);
        /**
         * @param summary "" if no route is loaded
         * @param error   summary is the message of a failed import; the loaded route (if any) stays as it was
         */
        void onRouteChanged(String summary, boolean active, boolean error);
        /** @param state null if no route is loaded */
        void onPlaybackChanged(@Nullable PlaybackState state);
        /**
         * The elevation profile that is currently on its way to the BikeComputer
         * (see PROTOCOL.md "Höhenprofil-Service").
         * @param frame null if there is none (no climb ahead, route ended, ...)
         */
        default void onProfileChanged(@Nullable ProfileFrame frame) {
        }
    }

    public static final String ACTION_STOP = "com.euphi.trailbridge.action.STOP";
    private static final String CHANNEL_ID = "trailbridge_running";
    private static final int NOTIFICATION_ID = 1;
    private static final String TAG = "TrailBridge";
    private static final String PREFS = "trailbridge";
    private static final String PREF_ROUTE_ACTIVE = "route_active";
    private static final String PREF_EMULATE_HR = "emulate_hr";
    private static final String PREF_EMULATE_CAD = "emulate_cad";
    private static final String PREF_EMULATE_POWER = "emulate_power";
    private static final long PLAYBACK_TICK_MS = 1000L;
    private static final String ROUTE_FILE = "route.gpx";

    private final IBinder binder = new LocalBinder();

    private OsmAndLink osmAndLink;
    private GpsLink gpsLink;
    private BikeComputerGattServer gattServer;
    @Nullable private UiListener uiListener;

    // MainActivity ruft TrailBridgeService.start() bei jedem onStart() auf
    // (auch wenn der Service laengst laeuft, z.B. nach Bildschirm an/aus)
    // -- onStartCommand() muss daher idempotent sein, sonst startet
    // osmAndLink.start()/gattServer.start() ein zweites Mal auf demselben
    // Objekt und startAdvertising() schlaegt mit
    // ADVERTISE_FAILED_ALREADY_STARTED fehl.
    private boolean started = false;

    // ---- importierte GPX-Route ----
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    @Nullable private GpxRoute route;
    private String routeSummary = "";
    @Nullable private RouteNavigator navigator;   // nur solange die Route aktiv ist
    // Letzter OsmAnd-Stand, damit er nach "Route beenden" sofort wieder greift.
    @Nullable private NavState lastOsmAndNav;
    // Zaehlt mit jeder neuen Streckenuebersicht weiter (1..255, nie 0), auch ueber Routen
    // hinweg: der BikeComputer erkennt daran, dass er neu lesen muss. 0 = keine Uebersicht.
    private int overviewRevision = 0;

    // ---- Testfahrt (GPX abspielen) ----
    @Nullable private RoutePlayer player;          // zur geladenen Route, auch ohne laufende Testfahrt
    private boolean playbackActive = false;        // Fake-Position wird gesendet (spielt oder pausiert)
    private boolean playbackPlaying = false;
    private boolean playbackStartedRoute = false;  // die Testfahrt hat die Navigation gestartet
    private long playbackLastTickMs;
    private boolean emulateHr = true;
    private boolean emulateCad = true;
    private boolean emulatePower = true;
    // Der echte GPS-Fix laeuft waehrend der Testfahrt weiter, wird aber nicht weitergereicht.
    @Nullable private PositionState lastRealPosition;
    @Nullable private PlaybackState lastPlayback;
    @Nullable private ProfileFrame lastProfile;
    private final Runnable playbackTick = this::onPlaybackTick;

    // Zuletzt bekannter Stand, damit eine (neu) gebundene Activity sofort den
    // aktuellen Stand zeigt statt bis zum naechsten Event zu warten.
    private String lastStatus = "";
    private OsmAndLink.Status lastStatusKind = OsmAndLink.Status.IDLE;
    @Nullable private NavState lastNavState;
    private String lastGpsStatus = "";
    @Nullable private PositionState lastPositionState;
    private int lastSubscriberCount = 0;
    @Nullable private String lastBleError;

    public class LocalBinder extends Binder {
        TrailBridgeService getService() {
            return TrailBridgeService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        osmAndLink = new OsmAndLink(this, this);
        gpsLink = new GpsLink(this, this);
        gattServer = new BikeComputerGattServer(this, this);
        createNotificationChannel();
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        emulateHr = prefs.getBoolean(PREF_EMULATE_HR, true);
        emulateCad = prefs.getBoolean(PREF_EMULATE_CAD, true);
        emulatePower = prefs.getBoolean(PREF_EMULATE_POWER, true);
        restoreRoute();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startForegroundNotification();
        if (!started) {
            started = true;
            // Wird nur aufgerufen, nachdem MainActivity die Bluetooth-Berechtigungen
            // bekommen hat (MainActivity.startServiceIfPermitted) -- der
            // Service selbst kann keine Berechtigungsdialoge zeigen.
            osmAndLink.start();
            gattServer.start();
        }
        // Standort ist optional und kann später dazukommen: GpsLink.start() tut nichts, wenn
        // es schon läuft, und meldet sonst die fehlende Berechtigung.
        gpsLink.start();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mainHandler.removeCallbacks(playbackTick);
        worker.shutdownNow();
        osmAndLink.stop();
        gpsLink.stop();
        gattServer.stop();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    // ---- API fuer die (optional) gebundene MainActivity ----

    public void setUiListener(@Nullable UiListener listener) {
        this.uiListener = listener;
        if (listener != null) {
            // aktuellen Stand sofort nachreichen
            listener.onStatusChanged(lastStatus, lastStatusKind);
            if (lastNavState != null) listener.onNavState(lastNavState);
            listener.onGpsStatusChanged(lastGpsStatus);
            if (lastPositionState != null) listener.onPositionUpdate(lastPositionState);
            listener.onSubscriberCountChanged(lastSubscriberCount);
            if (lastBleError != null) listener.onBleError(lastBleError);
            listener.onRouteChanged(routeSummary, navigator != null, false);
            listener.onPlaybackChanged(lastPlayback);
            listener.onProfileChanged(lastProfile);
        }
    }

    /**
     * Detaches a listener -- but only if it is still the current one: when one
     * activity hands over to another, the old one's onStop may arrive after the new
     * one has already registered.
     */
    public void removeUiListener(UiListener listener) {
        if (uiListener == listener) uiListener = null;
    }

    /** Sends the profile to the BikeComputer (null: none any more) and mirrors it to the UI. */
    private void publishProfile(@Nullable ProfileFrame frame) {
        lastProfile = frame;
        gattServer.updateProfile(frame);
        if (uiListener != null) uiListener.onProfileChanged(frame);
    }

    // ---- GPX-Route ----

    /**
     * Parst eine GPX-Datei (im Hintergrund), merkt sie sich (auch ueber einen
     * Neustart des Dienstes) und meldet die Zusammenfassung ans UI. Startet
     * die Navigation noch nicht -- das macht startRoute().
     */
    public void loadGpx(byte[] data, String displayName) {
        worker.execute(() -> {
            GpxRoute result;
            try {
                result = GpxParser.parse(new ByteArrayInputStream(data), displayName);
            } catch (GpxParser.GpxException e) {
                String msg = getString(R.string.gpx_import_failed, e.localized(this));
                mainHandler.post(() -> notifyRoute(msg, true));
                return;
            }
            final GpxRoute parsed = result;
            try {
                saveRouteFile(data);
            } catch (IOException e) {
                // Die Route laeuft trotzdem, nur ein Neustart des Dienstes vergisst sie.
                Log.w(TAG, "Could not save the route", e);
            }
            String summary = summarize(parsed);
            RoutePlayer parsedPlayer = new RoutePlayer(parsed);
            mainHandler.post(() -> {
                playbackStop();
                stopRoute();
                setRoute(parsed, parsedPlayer);
                publishRoute(summary);
            });
        });
    }

    /** Beginnt die Navigation entlang der geladenen Route (Position kommt von GpsLink). */
    public void startRoute() {
        startNavigation(true);
    }

    /** @param persist merken, dass die Route aktiv ist (ueberlebt einen Neustart des Dienstes) */
    private void startNavigation(boolean persist) {
        if (route == null) return;
        navigator = new RouteNavigator(route, RouteTexts.of(this));
        if (playbackActive && player != null) {
            navigator.seekTo(player.distanceM());
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ROUTE_ACTIVE, persist).apply();
        notifyRoute(routeSummary);
        // Sofort die erste Position durchreichen, falls schon ein Fix da ist
        // (waehrend einer Testfahrt ist lastPositionState die simulierte).
        if (lastPositionState != null && lastPositionState.hasFix) {
            followRoute(lastPositionState);
        }
    }

    /** Beendet die Navigation; OsmAnd (falls aktiv) uebernimmt wieder. */
    public void stopRoute() {
        if (navigator == null) return;
        navigator = null;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(PREF_ROUTE_ACTIVE, false).apply();
        publishProfile(null);
        gattServer.updateOverview(null);
        NavState back = lastOsmAndNav != null ? lastOsmAndNav : NavState.NONE;
        lastNavState = back;
        if (uiListener != null) uiListener.onNavState(back);
        gattServer.update(back);
        notifyRoute(routeSummary);
    }

    private void publishRoute(String summary) {
        routeSummary = summary;
        notifyRoute(summary);
    }

    private void setRoute(GpxRoute r, RoutePlayer p) {
        route = r;
        player = p;
        p.setEmulation(emulateHr, emulateCad, emulatePower);
        publishPlayback(null);
    }

    // ---- Testfahrt: GPX abspielen ----

    /** Start / Pause; nach dem Ende der Route startet es von vorn. */
    public void playbackToggle() {
        RoutePlayer p = player;
        if (p == null) return;
        if (!playbackActive) {
            playbackActive = true;
            playbackPlaying = true;
            playbackLastTickMs = SystemClock.elapsedRealtime();
            if (p.finished()) p.seek(0);
            // Laeuft die Navigation schon (mit dem Stand des echten GPS), an die
            // Startstelle der Testfahrt setzen.
            if (navigator != null) navigator.seekTo(p.distanceM());
            // Erst die simulierte Position setzen, dann ggf. die Navigation starten: sie
            // bekommt sofort diese Position statt der des echten GPS.
            emitPlaybackSample(p.step(0, true));
            if (navigator == null) {
                playbackStartedRoute = true;
                startNavigation(false);
            }
            mainHandler.postDelayed(playbackTick, PLAYBACK_TICK_MS);
        } else if (p.finished()) {
            playbackPlaying = true;
            playbackSeek(0);
        } else {
            playbackPlaying = !playbackPlaying;
            playbackLastTickMs = SystemClock.elapsedRealtime();
            emitPlaybackSample(p.step(0, playbackPlaying));
        }
    }

    /** Beendet die Testfahrt; der echte GPS-Fix gilt wieder, die Navigation endet, falls die Testfahrt sie gestartet hat. */
    public void playbackStop() {
        if (!playbackActive) return;
        playbackActive = false;
        playbackPlaying = false;
        mainHandler.removeCallbacks(playbackTick);
        if (playbackStartedRoute) {
            playbackStartedRoute = false;
            stopRoute();
        }
        PositionState real = lastRealPosition != null ? lastRealPosition : PositionState.NONE;
        lastPositionState = real;
        if (uiListener != null) uiListener.onPositionUpdate(real);
        gattServer.updatePosition(real);
        publishPlayback(null);
    }

    /** Springt an eine Stelle der Route (Meter ab Start); auch ohne laufende Testfahrt (= Startpunkt). */
    public void playbackSeek(double distM) {
        RoutePlayer p = player;
        if (p == null) return;
        p.seek(distM);
        if (!playbackActive) {
            publishPlayback(null);
            return;
        }
        if (navigator != null) navigator.seekTo(p.distanceM());
        // Das alte Profil gehoert zur Stelle vor dem Sprung.
        publishProfile(null);
        playbackLastTickMs = SystemClock.elapsedRealtime();
        emitPlaybackSample(p.step(0, playbackPlaying));
    }

    /** Puls / Trittfrequenz / Leistung emulieren, wo die GPX keine hat (bei vorhandenen ohne Wirkung). */
    public void setPlaybackEmulation(boolean heartRate, boolean cadence, boolean power) {
        emulateHr = heartRate;
        emulateCad = cadence;
        emulatePower = power;
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putBoolean(PREF_EMULATE_HR, heartRate)
                .putBoolean(PREF_EMULATE_CAD, cadence)
                .putBoolean(PREF_EMULATE_POWER, power).apply();
        RoutePlayer p = player;
        if (p == null) return;
        p.setEmulation(heartRate, cadence, power);
        if (playbackActive) {
            emitPlaybackSample(p.step(0, playbackPlaying));
        } else {
            publishPlayback(null);
        }
    }

    private void onPlaybackTick() {
        RoutePlayer p = player;
        if (!playbackActive || p == null) return;
        long now = SystemClock.elapsedRealtime();
        // Gedeckelt: nach einem Aussetzer (Doze) soll die Fahrt nicht springen.
        double dt = Math.min(5.0, (now - playbackLastTickMs) / 1000.0);
        playbackLastTickMs = now;
        RoutePlayer.Sample s = p.step(dt, playbackPlaying);
        if (playbackPlaying && p.finished()) {
            playbackPlaying = false;   // angekommen: stehen bleiben, Sitzung bleibt offen
        }
        emitPlaybackSample(s);
        mainHandler.postDelayed(playbackTick, PLAYBACK_TICK_MS);
    }

    /** Behandelt die simulierte Position wie einen GPS-Fix, nur mit Sensorwerten dazu. */
    private void emitPlaybackSample(RoutePlayer.Sample s) {
        boolean hasEle = !Double.isNaN(s.eleM);
        PositionState state = new PositionState(
                true,
                (int) Math.round(s.lat * 1e7), (int) Math.round(s.lon * 1e7),
                hasEle, hasEle ? (int) Math.round(s.eleM) : 0,
                true, (int) Math.round(s.speedMs * 100),
                true, (int) Math.round(s.bearingDeg * 100) % 36000,
                true, 50,   // "5 m genau", wie ein guter GPS-Fix
                SystemClock.elapsedRealtime(),
                // Die Uhr des BikeComputers soll richtig bleiben: echte Zeit, nicht die der GPX.
                System.currentTimeMillis(),
                s.hr >= 0, Math.max(0, s.hr), s.cad >= 0, Math.max(0, s.cad),
                // Der Hoehenverlauf der Route steht fuer das Barometer des BikeComputers
                // (der berechnet daraus seine Steigung selbst, die wird nicht gesendet).
                hasEle, hasEle ? (int) Math.round(s.eleM * 10) : 0,
                s.power >= 0, Math.max(0, s.power),
                PositionState.SIM_POSITION | PositionState.SIM_SENSORS);
        lastPositionState = state;
        if (uiListener != null) uiListener.onPositionUpdate(state);
        gattServer.updatePosition(state);
        followRoute(state);
        publishPlayback(s);
    }

    private void publishPlayback(@Nullable RoutePlayer.Sample s) {
        RoutePlayer p = player;
        GpxRoute r = route;
        if (p == null || r == null) {
            lastPlayback = null;
        } else {
            lastPlayback = new PlaybackState(r, playbackActive, playbackPlaying, p.finished(),
                    s != null ? s.distM : p.distanceM(), p.timeS(), p.durationS(),
                    s != null ? s.speedMs : 0, s != null ? s.hr : -1, s != null ? s.cad : -1,
                    s != null ? s.power : -1, s != null ? s.eleM : Double.NaN,
                    p.speedSource(), p.heartRateSource(), p.cadenceSource(), p.powerSource(),
                    p.hasHeight());
        }
        if (uiListener != null) uiListener.onPlaybackChanged(lastPlayback);
    }

    private void notifyRoute(String text) {
        notifyRoute(text, false);
    }

    private void notifyRoute(String text, boolean error) {
        if (uiListener != null) uiListener.onRouteChanged(text, navigator != null, error);
    }

    private String summarize(GpxRoute r) {
        Locale locale = Locale.getDefault();
        StringBuilder b = new StringBuilder(getString(R.string.summary_head,
                r.name.isEmpty() ? getString(R.string.route_default_name) : r.name,
                String.format(locale, "%.1f", r.totalM / 1000)));
        if (r.hasElevation()) b.append(getString(R.string.summary_ascent, r.totalAscentM()));
        if (!r.waypoints.isEmpty()) {
            b.append(getResources().getQuantityString(R.plurals.summary_waypoints,
                    r.waypoints.size(), r.waypoints.size()));
        }
        RouteTimes times = RouteTimes.of(r);
        if (times != null) {
            // Dann kommt auch die Restzeit aus der Datei statt aus der aktuellen Geschwindigkeit.
            int minutes = (int) Math.round(times.remainingS(0) / 60);
            b.append(getString(R.string.summary_time, minutes / 60, minutes % 60));
        }
        int turns = r.steps.size() - 2;   // ohne Start und Ziel
        b.append(getResources().getQuantityString(r.hasRoutingInfo ? R.plurals.summary_maneuvers_file
                : R.plurals.summary_maneuvers_track, turns, turns));
        if (!r.hasElevation()) b.append(getString(R.string.summary_no_elevation));
        return b.toString();
    }

    private void saveRouteFile(byte[] data) throws IOException {
        File f = new File(getFilesDir(), ROUTE_FILE);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
    }

    /** Nach einem Neustart des Dienstes (START_STICKY) die letzte Route wieder laden. */
    private void restoreRoute() {
        File f = new File(getFilesDir(), ROUTE_FILE);
        if (!f.isFile()) return;
        boolean wasActive = getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_ROUTE_ACTIVE, false);
        worker.execute(() -> {
            try (FileInputStream in = new FileInputStream(f)) {
                GpxRoute parsed = GpxParser.parse(in, getString(R.string.route_default_name));
                String summary = summarize(parsed);
                RoutePlayer parsedPlayer = new RoutePlayer(parsed);
                mainHandler.post(() -> {
                    setRoute(parsed, parsedPlayer);
                    routeSummary = summary;
                    if (wasActive) {
                        startRoute();
                    } else {
                        publishRoute(summary);
                    }
                });
            } catch (GpxParser.GpxException | IOException e) {
                Log.w(TAG, "Saved route not readable", e);
            }
        });
    }

    private void followRoute(PositionState state) {
        RouteNavigator nav = navigator;
        if (nav == null) return;
        double speed = state.hasSpeed ? state.speedCms / 100.0 : -1;
        RouteNavigator.Result r = nav.onFix(state.latitudeE7 / 1e7, state.longitudeE7 / 1e7,
                speed, gattServer.maxPayload());
        if (r.overviewChanged) {
            // Erst die Uebersicht bereitstellen, dann der Nav-Frame mit ihrer Revision:
            // der BikeComputer liest, sobald er die neue Revision sieht.
            overviewRevision = overviewRevision % 255 + 1;
            byte[] overview = OverviewFrameEncoder.encode(nav.overview(), overviewRevision);
            gattServer.updateOverview(overview);
            Log.i(TAG, "Route overview revision " + overviewRevision + ": "
                    + nav.overview().waypointsAhead().size() + " waypoints, "
                    + nav.overview().climbsAhead().size() + " climbs ahead, " + overview.length + " bytes");
        }
        NavState navState = r.nav.withOverviewRevision(overviewRevision);
        lastNavState = navState;
        if (uiListener != null) uiListener.onNavState(navState);
        gattServer.update(navState);
        if (r.profile != null) {
            publishProfile(r.profile);
        } else if (r.clearProfile) {
            publishProfile(null);
        }
    }

    public void retrySubscribe() {
        osmAndLink.retrySubscribe();
    }

    // ---- OsmAndLink.Listener ----

    @Override
    public void onStatusChanged(String status, OsmAndLink.Status kind) {
        lastStatus = status;
        lastStatusKind = kind;
        if (uiListener != null) uiListener.onStatusChanged(status, kind);
    }

    @Override
    public void onNavState(NavState state) {
        lastOsmAndNav = state;
        if (navigator != null) {
            // Eine aktive GPX-Route hat Vorrang vor OsmAnd.
            return;
        }
        lastNavState = state;
        if (uiListener != null) uiListener.onNavState(state);
        gattServer.update(state);
    }

    // ---- GpsLink.Listener ----

    @Override
    public void onGpsStatusChanged(String status) {
        lastGpsStatus = status;
        if (uiListener != null) uiListener.onGpsStatusChanged(status);
    }

    @Override
    public void onPositionUpdate(PositionState state) {
        lastRealPosition = state;
        // Waehrend einer Testfahrt gilt die simulierte Position, nicht der echte Fix.
        if (playbackActive) return;
        lastPositionState = state;
        if (uiListener != null) uiListener.onPositionUpdate(state);
        gattServer.updatePosition(state);
        if (state.hasFix) followRoute(state);
    }

    // ---- BikeComputerGattServer.Listener ----

    @Override
    public void onSubscriberCountChanged(int count) {
        lastSubscriberCount = count;
        if (uiListener != null) uiListener.onSubscriberCountChanged(count);
    }

    @Override
    public void onError(String message) {
        lastBleError = message;
        if (uiListener != null) uiListener.onBleError(message);
    }

    // ---- Foreground-Notification ----

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, getString(R.string.notif_channel_name), NotificationManager.IMPORTANCE_LOW);
        channel.setDescription(getString(R.string.notif_channel_desc));
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(channel);
    }

    private void startForegroundNotification() {
        Intent stopIntent = new Intent(this, TrailBridgeService.class).setAction(ACTION_STOP);
        PendingIntent stopPendingIntent = PendingIntent.getService(
                this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_notification)
                .setOngoing(true)
                .addAction(0, getString(R.string.notif_stop), stopPendingIntent)
                .build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            // Claiming the "location" FGS type without already holding the
            // underlying dangerous permission throws SecurityException --
            // observed in practice on a START_STICKY restart (e.g. right
            // after adb install -r, or any OS-triggered restart) that races
            // ahead of MainActivity's permission dialog. Leave the type out
            // until it's actually granted; onStartCommand() re-runs this on
            // every TrailBridgeService.start() call (see MainActivity ->
            // startAndBindService() after the permission grant), so it
            // upgrades itself the moment the user grants it, no separate
            // wiring needed.
            if (hasLocationPermission()) {
                types |= ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            }
            startForeground(NOTIFICATION_ID, notification, types);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ---- Hilfsfunktion fuer MainActivity ----

    public static void start(Context context) {
        Intent intent = new Intent(context, TrailBridgeService.class);
        context.startForegroundService(intent);
    }
}
