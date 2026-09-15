# TrailBridge

Ersetzt Komoots eingestellten BLE-Navigationsdienst für den
[TRGB-BikeComputer](https://github.com/euphi/TRGB-BikeComputer) durch OsmAnd.

**Meilenstein 1:** OsmAnd-Anbindung (AIDL, siehe unten) -- Manöver,
Straßennamen, übernächstes Manöver, Restdistanz/-zeit auf dem Bildschirm.

**Meilenstein 2 (dieser Stand):** BLE-GATT-Server (Peripheral-Rolle) nach
[PROTOCOL.md](PROTOCOL.md). Die App advertised den Nav-Service, nimmt
Indicate-Abonnements entgegen und schickt bei jeder Änderung sowie als
Heartbeat alle 5s einen TLV-Frame raus. Zusätzlich ein zweiter, unabhängiger
GATT-Service: rohe GPS-Position direkt vom Handy-GPS-Chip
(`LocationManager.GPS_PROVIDER`, siehe `GpsLink.java`), funktioniert also auch
ohne laufendes OsmAnd. **Der BikeComputer selbst spricht das Protokoll noch
nicht** -- das ist Meilenstein 3 (Firmware-Änderung in `BLEDevices.cpp`). Bis
dahin lässt sich der BLE-Teil mit jeder BLE-Scanner-App (z.B.
[nRF Connect](https://www.nordicsemi.com/Products/Development-tools/nrf-connect-for-mobile))
verifizieren.

## Woher die AIDL-Dateien kommen

`app/src/main/aidl/net/osmand/aidlapi/**` und
`app/src/main/java/net/osmand/aidlapi/**` sind 1:1 aus
[osmandapp/OsmAnd](https://github.com/osmandapp/OsmAnd),
Verzeichnis `OsmAnd-api/src/net/osmand/aidlapi/` (Stand: Commit
`58bc60e`, September 2026), übernommen -- das ist das offizielle,
öffentliche API-Modul, das OsmAnd genau für diesen Zweck bereitstellt
(siehe [osmand.net AIDL-API-Doku](https://osmand.net/docs/technical/osmand-api-sdk/):
"No licensing issue - can be used for all possible purposes"). Der
Haupt-App-Code bleibt GPLv3 von OsmAnd BV; diese Schnittstellen-Stubs sind der
vorgesehene Weg, sie in einer eigenen App zu nutzen -- genau wie
Header-Dateien einer C-Bibliothek.

**Eine Korrektur war nötig:** `IOsmAndAidlCallback.aidl` verwendet
`android.view.KeyEvent` in `onKeyEvent()`, importiert es aber im Original
nicht -- das lässt den AIDL-Compiler scheitern. Ich habe den fehlenden
Import ergänzt (`import android.view.KeyEvent;`), sonst nichts geändert.

Falls OsmAnd die API mal weiterentwickelt: einfach den `OsmAnd-api`-Ordner
aus deren aktuellem Repo erneut in dieselben zwei Verzeichnisse kopieren
und den Import-Fix erneut anwenden.

## Verifizierte API-Fakten (direkt aus dem OsmAnd-Quellcode, nicht geraten)

- Service-Action: `net.osmand.aidl.OsmandAidlServiceV2`
  (`OsmAnd/AndroidManifest.xml`)
- Paketnamen (Reihenfolge = Priorität): `net.osmand.plus` (frei),
  `net.osmand` (bezahlt), `net.osmand.dev` (nightly)
- OsmAnd **schaltet jede aufrufende App einzeln frei**
  (`OsmandAidlServiceV2.getApi()` prüft `api.isAppEnabled(packName)`).
  Beim allerersten Kontakt wird die App als *deaktiviert* registriert und
  `registerForNavigationUpdates` liefert einen negativen Wert zurück -- das
  ist der Normalfall beim ersten Start, kein Fehler. Erst nachdem du in
  OsmAnd unter **Menü > Plugins > TrailBridge > aktivieren** eingeschaltet
  hast, klappt die Anmeldung (auf "Erneut versuchen" tippen, kein Neustart
  von OsmAnd nötig).
- `registerForNavigationUpdates(...)` liefert per Push `ADirectionInfo`
  (Manöver-Typ als Int, Distanz, isLeftSide) bei jedem Manöverwechsel --
  wird hier nur als "jetzt pollen"-Trigger genutzt.
- Alles andere (Straßenname, übernächstes Manöver, Restdistanz/-zeit) kommt
  aus `getAppInfo().getTurnInfo()`, einem `Bundle`. Das ist ein **Pull**
  (synchroner Aufruf), kein Push -- daher das 1s-Polling in `OsmAndLink`.
  Verifizierte Bundle-Keys (aus `ExternalApiHelper.java`,
  `PARAM_NT_DIRECTION_*`-Konstanten):
  - `next_turn_type` (String, z.B. `"TL"`, `"RNDB2"`), `next_turn_name`,
    `next_turn_distance` (Int, Meter)
  - `after_nextturn_type`, `after_nextturn_name`, `after_nextturn_distance`
    -- **Achtung, kein Unterstrich** nach `after_next` (kein Tippfehler,
    steht so im OsmAnd-Quellcode)
  - `current_turn_type`, `current_turn_name` (das gerade *begonnene*
    Manöver -- aktuell ungenutzt, aber schon dokumentiert falls später
    gebraucht)
- Kreisverkehr-Ausfahrt: `TurnType.toXmlString()` hängt die Ausfahrtsnummer
  direkt an den Typ-String an (`"RNDB" + exitOut`), z.B. `"RNDB2"` für
  3. Ausfahrt eines Kreisverkehrs mit Rechtsverkehr. `Maneuver.roundaboutExit()`
  parst das.
- `getLeftDistance()` (Meter) / `getLeftTime()` (Sekunden) auf
  `AppInfoParams` selbst, nicht im Bundle.

## Setup unter Gentoo (Kommandozeile, kein Android Studio)

1. **JDK** (AGP 9.x will JDK 17, auch als Minimum für Gradle 9 selbst):
   ```
   emerge --ask dev-java/openjdk:17
   eselect java-vm set system openjdk-17
   ```
2. **Gradle** (nutzt hier das System-Gradle statt eines Wrappers, spart dir
   den Wrapper-JAR-Download):
   ```
   emerge --ask dev-build/gradle
   gradle -v
   ```
   Dieses Projekt nutzt bewusst **AGP 8.13.0** (neueste 8.x-Version, Stand
   September 2026, braucht **Gradle >= 8.13**) statt AGP 9.x -- damit reicht
   das stabile Gentoo-Paket `dev-java/gradle-bin` (8.14.2), ohne ein
   `~amd64`-Testing-Gradle freischalten zu müssen. Falls deine `gradle -v`
   etwas Älteres als 8.13 zeigt: Gentoo-Gradle-Paket aktualisieren, oder in
   `build.gradle` (root) eine noch ältere, dazu passende AGP-Version eintragen --
   [Kompatibilitätstabelle hier](https://developer.android.com/build/releases/gradle-plugin#updating-gradle).
3. **Android SDK cmdline-tools** (ohne Android Studio):
   ```
   mkdir -p ~/Android/cmdline-tools
   cd ~/Android/cmdline-tools
   # aktuelle URL von https://developer.android.com/studio#command-line-tools-only holen --
   # die Versionsnummer im Dateinamen ändert sich, deshalb hier nicht fest verdrahtet
   wget https://dl.google.com/android/repository/commandlinetools-linux-XXXXXXXX_latest.zip
   unzip commandlinetools-linux-*.zip
   mv cmdline-tools latest
   export ANDROID_HOME=~/Android
   export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
   ```
   (Die beiden `export`-Zeilen dauerhaft in `~/.zshrc` o.ä. eintragen.)
4. **Pakete installieren + Lizenzen akzeptieren:**
   ```
   sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   sdkmanager --licenses
   ```
5. **Bauen:**
   ```
   cd TrailBridge
   gradle assembleDebug
   ```
   APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.
6. **Auf's Handy:**
   ```

   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Testen

1. OsmAnd installieren, Offline-Karte für deine Gegend laden.
2. TrailBridge installieren und öffnen -> Status zeigt
   "NICHT FREIGESCHALTET -- in OsmAnd: Menü > Plugins > ...".
3. In OsmAnd: Menü > Plugins > TrailBridge > aktivieren.
4. Zurück zu TrailBridge, "Erneut versuchen" antippen -> Status wird
   "verbunden, warte auf Navigationsdaten".
5. In OsmAnd eine Route starten (Ziel wählen, "Los") -> die Textausgabe
   sollte sich binnen ~1s füllen: Manöver, Distanz, Straßenname,
   übernächstes Manöver, Restdistanz/-zeit.
6. `adb logcat -s OsmAndLink -s BikeGatt` zeigt dieselben Infos als Log,
   falls die Bildschirmausgabe mal hakt.

## BLE-Teil mit nRF Connect testen (ohne BikeComputer)

1. App starten, Bluetooth-Berechtigungen erlauben (Android 12+ fragt danach).
2. BLE-Status sollte "Advertising, 0 Abonnenten" zeigen. Falls stattdessen
   ein Fehler kommt: entweder ist Bluetooth aus, oder das Handy unterstützt
   keine BLE-Peripheral-Rolle (selten, aber es gibt ältere/günstige Geräte
   ohne das; dann bräuchten wir ein anderes Handy für die Companion-App).
3. Mit nRF Connect (2. Handy oder Tablet) nach `f7ac2b76-986b-45fd-8e44-f116a61f319d`
   scannen, verbinden, die Characteristic `7473da02-...` per Doppelpfeil-Icon
   auf Indicate abonnieren.
4. Sofort sollte ein erster Frame kommen (HELLO, falls OsmAnd noch keine
   Route hat, oder gleich NAV_UPDATE/NAV_NONE).
5. In OsmAnd eine Route starten -> bei jeder Manöveränderung ein neuer Frame,
   ansonsten spätestens alle 5s (Heartbeat). Byte-Layout siehe PROTOCOL.md,
   in nRF Connect als Hex anzeigen lassen.
6. Für den GPS-Positions-Service: in nRF Connect nach dem Verbinden auf
   "Services neu laden"/Discovery -- er wird nicht beworben (siehe
   PROTOCOL.md), taucht aber nach Verbindungsaufbau als eigener Service
   `66b5835c-...` mit Characteristic `10c49e7b-...` auf. Standort-Berechtigung
   muss dafür in TrailBridge erteilt sein (wird beim ersten Start abgefragt)
   und GPS am Handy aktiv/im Freien für einen echten Fix.

## Nächste Schritte (noch nicht in diesem Stand)

- **Meilenstein 3:** `BLEDevices.cpp`/`.h` im TRGB-BikeComputer: alten
  Komoot-Client-Code durch TLV-Parser + Indicate-Subscription (Schreiben auf
  den CCCD-Descriptor -- die Stelle, die im Original auskommentiert war)
  ersetzen; neue Icon-Tabelle für die erweiterten Manöver-Codes
  (Kreisverkehr mit Ausfahrt, übernächstes Manöver als kleines
  Vorschau-Icon, ETA-Anzeige). Zusätzlich: zweiten Indicate-Subscribe für
  den GPS-Positions-Service (eigene Characteristic, eigenes TLV-Format,
  siehe PROTOCOL.md).
