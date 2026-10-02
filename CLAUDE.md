# TrailBridge -- Projektkontext für Claude Code

(App-Name/Package wurden von "BikeNavRelay"/`com.euphi.bikenavrelay` auf
"TrailBridge"/`com.euphi.trailbridge` umbenannt -- Grund: "OsmAnd" durfte laut
OsmAnds Markenrichtlinie nicht im Namen stehen, und "TrailBridge" trifft es
auch besser. Das lokale Ordner-Verzeichnis heißt weiterhin `BikeNavRelay`,
siehe Hinweis unten bei "Build".)

Android-Companion-App, die OsmAnds Turn-by-Turn-Navigation per BLE an den
[TRGB-BikeComputer](https://github.com/euphi/TRGB-BikeComputer) (ESP32)
weiterreicht -- als Ersatz für Komoots eingestellten BLE-Navigationsdienst.

**Vor jeder Änderung lesen:** [`README.md`](README.md) (Projektüberblick,
Testen), [`BUILD.md`](BUILD.md) (Setup/Build) und [`PROTOCOL.md`](PROTOCOL.md)
(das BLE-Wire-Format -- verbindlicher Vertrag zwischen App und Firmware,
nicht auf eigene Faust ändern).
[`PROTOCOL.en.md`](PROTOCOL.en.md) ist die englische Übersetzung -- **bei jeder
Änderung an `PROTOCOL.md` mitziehen**. Beide erscheinen auf der Doku-Seite
<https://euphi.github.io/TRGB-BikeComputer/trailbridge/PROTOCOL/> (wird im
BikeComputer-Repo gebaut, das die beiden Dateien beim Bauen von hier kopiert).
Für alles an der Oberfläche zusätzlich [`DESIGN.md`](DESIGN.md): die App
folgt dem Rim-&-Ridge-Designsystem des BikeComputers (Farb-Tokens `rr_*`,
Stile `TB.*`, drei Schriften) -- keine Hex-Werte oder Fremdschriften im Layout.

## Stand

- **Meilenstein 1 (fertig):** `OsmAndLink.java` -- Bindung an OsmAnds AIDL-API
  (`net.osmand.aidl.OsmandAidlServiceV2`), Polling von `getAppInfo()`.
- **Meilenstein 2 (fertig):** `BikeComputerGattServer.java` +
  `NavFrameEncoder.java` -- BLE-GATT-Server (Peripheral-Rolle), TLV-Protokoll
  nach PROTOCOL.md, Indicate statt Poll.
- **Meilenstein 3 (fertig, Stand 2026-09-16):** Firmware-Seite. Das
  TRGB-BikeComputer-Repo liegt NICHT in diesem Ordner -- separat unter
  `https://github.com/euphi/TRGB-BikeComputer` geklont. Dort ist
  `src/BLEDevices.cpp`/`.h` vom alten Komoot-Parser bereits auf einen
  TLV-Parser nach PROTOCOL.md umgestellt (Commits `faefff4`, `c6d6277`),
  inklusive Indicate-Subscribe und Icon-Tabelle für die Manöver-Codes, sowie
  Parsing/Logging des GPS-Positions-Service (Commit `cf64345`) -- lief
  parallel zur App-Arbeit hier, unabhängig entstanden. Bekannte Lücken laut
  TRGB-BikeComputer-README: BLE-Adresse von TrailBridge wird nicht
  fest gepinnt (Android-Peripherals rotieren die Adresse), kein manueller
  Wechsel der Nav-Anzeige.

- **Meilenstein 4 (Branch `feature/gpx-route`, nur Debug-Build, nicht
  released):** GPX-Import + eigene Navigation (`GpxParser`, `TurnDetector`,
  `RouteNavigator`) und Höhenprofil als dritter BLE-Service
  (`ElevationProfile`, `ProfileFrameEncoder`, PROTOCOL.md "Höhenprofil-
  Service"). Firmware-Seite dafür fehlt noch. JVM-Tests unter `app/src/test`.
  **Testfahrt** (`RoutePlayer`, `RouteProfileView`, Panel in `MainActivity`):
  spielt die GPX als Fake-GPS-Position samt simulierter Sensorwerte ab
  (Speed aus GPX-Zeit oder berechnet; Puls/Trittfrequenz aus der GPX oder
  emuliert; dazu Höhe als Barometer-Ersatz -- der BC rechnet die Steigung selbst --
  und Leistung). Dafür fünf neue optionale Tags im Positions-Service (0x09-0x0D:
  Puls, Trittfrequenz, `SIM_FLAGS`, Höhe in dm, Leistung; PROTOCOL.md "Sensorwerte und
  Simulationsmodus") -- die Simulation ist immer gekennzeichnet, ohne Flag ist
  alles echt (so bleibt Platz für später von TrailBridge weitergereichte
  echte Sensoren, noch nicht gebaut). Firmware-Seite: Parser +
  `SimSensors::feedFromTrailBridge()` im TRGB-BikeComputer-Repo (Branch
  `feature/ride-state-machine`, uncommittet), nur der BC_SIM-Build speist ein.
  Auf echtem Gerät noch nicht getestet.

## Verifizierte Fakten, die beim Weiterbauen wichtig sind

- Alle `net.osmand.aidlapi.*`-Dateien unter `app/src/main/{aidl,java}/` sind
  1:1 aus dem offiziellen `osmandapp/OsmAnd`-Repo (Verzeichnis `OsmAnd-api`)
  übernommen -- NICHT als "unser Code" behandeln, nicht ungefragt umschreiben.
  Eine Korrektur war nötig und ist absichtlich: fehlender
  `android.view.KeyEvent`-Import in `IOsmAndAidlCallback.aidl`.
- Die `turnInfo`-Bundle-Keys (`next_turn_type`, `after_nextturn_type` ohne
  Unterstrich, etc.) sind aus OsmAnds `ExternalApiHelper.java` verifiziert,
  siehe Kommentare in `OsmAndLink.java`. Nicht aus Vermutung ändern, sondern
  gegen die OsmAnd-Quelle prüfen (`osmandapp/OsmAnd`, Datei
  `OsmAnd/src/net/osmand/plus/helpers/ExternalApiHelper.java`).
- Die Anstiegs-Kriterien (Fuß 3 m auf 100 m; Gipfel bei > 30 m Gefälle oder
  2 km ohne 0,5 % Steigung) stehen doppelt: in `ElevationProfile.java` und als
  Standardwerte in `ClimbProfile.h` (`Climb::Config`) der Firmware, die Fuß und
  Gipfel im gesendeten Profil selbst sucht. Nur gemeinsam ändern (PROTOCOL.md
  "Was ein Anstieg ist").
- Rollenverteilung ist bewusst: Handy = BLE-Peripheral/-Server,
  BikeComputer = BLE-Central/-Client (wie bei Komoot). Nicht umdrehen, ohne
  vorher mit mir (dem Nutzer) Rücksprache zu halten -- betrifft auch die
  bestehende Verbindungslogik in `BLEDevices.cpp` für die anderen
  Sensoren (HR, CSC, Forumslader).

## Lizenz & F-Droid

Lizenz ist GPL-3.0-or-later (vorher MIT) -- zwingend, weil die
`net.osmand.aidlapi`-Dateien aus dem GPLv3-OsmAnd-Repo stammen. Die App soll
auf F-Droid: keine proprietären Abhängigkeiten, kein Tracking, keine
Binärdateien einchecken. Versionsschema und Release-Ablauf in
[`FDROID.md`](FDROID.md).

## Build

Kein Gradle-Wrapper -- System-Gradle nutzen. Kompletter Ablauf inkl.
Versions-Kompatibilität in BUILD.md. Kurzform, wenn die Umgebung schon
steht:

```bash
cd app  # falls du direkt im BikeNavRelay-Root bist, weglassen
cd ..
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Programmiersprachen-Präferenz

Java für Android (durch die Plattform vorgegeben -- BLE-Peripheral/GATT-Server
und AIDL-Binding gehen praktisch nur nativ). Für das ESP32-Firmware-Teil
(Meilenstein 3): C++/PlatformIO, wie im bestehenden TRGB-BikeComputer-Repo.
