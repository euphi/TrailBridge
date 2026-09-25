# Untersuchung: Spur-Anzahl-Diskrepanz an Fronmüllerstraße/Schwabacher Straße

Stand: 2026-09-20. Entstanden aus einer Firmware-seitigen Debugging-Sitzung
im TRGB-BikeComputer-Repo (Live-Analyse per `adb logcat` + Serial-Log während
zweier realer Testfahrten über dieselbe Kreuzung). Hier notiert, damit die
eigentliche Analyse in diesem Repo weitergeführt werden kann.

## Der eigentliche Verdachtsfall

An der Kreuzung Fronmüllerstraße → Schwabacher Straße (Linksabbiegung) zeigt
OsmAnd selbst **zwei Fahrspuren** an. Im BLE-Protokoll (LANES-Tag, siehe
PROTOCOL.md) kommt aber nur **eine** Spur an:

```
🧭 Lanes in 155 m: [TURN_LEFT*]  Next lanes in 439 m: [TURN_LEFT | STRAIGHT* | STRAIGHT/TURN_RIGHT*]
```

(`TURN_LEFT*` = eine Spur, primär TURN_LEFT, ACTIVE-Flag gesetzt. Auf zwei
getrennten Testfahrten identisch reproduziert.)

Offene Frage: **wo geht die zweite Spur verloren** - schon in OsmAnds eigenem
AIDL-Bundle (`next_turn_lanes`), oder erst beim Parsen in TrailBridge?

### Konkreter nächster Schritt

In `OsmAndLink.java`, Methode `parse(AppInfoParams info)`, Zeile ~250:

```java
List<Lane> lanes = parseLanes(turnInfo.getString("next_turn_lanes"));
```

Direkt davor testweise loggen:

```java
Log.i(TAG, "raw next_turn_lanes=" + turnInfo.getString("next_turn_lanes"));
```

Neu bauen, dieselbe Kreuzung nochmal abfahren. Der rohe
`Arrays.toString(int[])`-String zeigt sofort, ob OsmAnd selbst schon nur
einen Wert liefert (dann OsmAnd-seitiges Problem/Limitierung, evtl.
Upstream-Report nötig) oder ob `parseLanes()`/`Lane.fromOsmAndLaneValue()`
hier etwas verwirft (dann TrailBridge-seitiger Bug).

## Nebenbefund: dokumentiertes `adb logcat`-Kommando liefert nichts

`README.md` empfiehlt `adb logcat -s OsmAndLink -s BikeGatt` zur Diagnose.
Live während einer ~7-minütigen Testfahrt ausprobiert (App lief nachweislich,
`pid` aktiv, Debug-Build) - **keine einzige Zeile** erfasst, weder mit
`-s TAG`-Syntax noch mit der klassischen Filterspec-Form
(`'OsmAndLink:V' 'BikeGatt:V' '*:S'`). Die Tag-Namen selbst sind korrekt
(gegen den Quellcode verifiziert: `OsmAndLink.java:52`, `BikeGatt` in
`BikeComputerGattServer.java:49` - dazu noch ein drittes, nicht in der
README erwähntes Tag `GpsLink` in `GpsLink.java:22`).

Wahrscheinlichste Erklärung: `OsmAndLink.java`s einziger `Log.i(TAG, ...)`
-Aufruf sitzt in `setStatus()` (~Zeile 312) und feuert nur bei
Verbindungsstatus-Änderungen, nicht pro Nav-Update. Bei einer störungsfreien
Fahrt ohne Verbindungsabbruch gibt es auf diesem Level schlicht nichts zu
loggen. Für echte Update-für-Update-Diagnose fehlt aktuell ein Log-Punkt,
z.B. der geparste `NavState` in `parse()` oder die rohen
`next_turn_lanes`/`after_nextturn_lanes`-Strings selbst (siehe oben - deckt
sich ohnehin mit dem eigentlichen Untersuchungsschritt).

## Code-Review in diesem Repo (2026-09-20, ohne Rebuild -- Handy navigierte noch)

`parseLanes()`, `Lane.fromOsmAndLaneValue()`, `Maneuver.fromOsmAndLaneTurnType()`,
`NavFrameEncoder.writeLanes()` und `NavState` einmal komplett durchgesehen:
keine Stelle gefunden, die eine zweite Spur verwerfen würde (Split auf Komma
ist korrekt, keine Dedupe-Logik, `writeLanes()` schreibt `lanes.size()*4`
Bytes ohne Truncation). Der Verdacht bleibt also: OsmAnds AIDL-Bundle selbst
liefert an dieser Kreuzung nur einen Wert in `next_turn_lanes`.

Den vorgeschlagenen Log-Punkt in `OsmAndLink.java` (Methode `parse()`) direkt
eingebaut -- loggt jetzt `raw next_turn_lanes=...` und
`raw after_nextturn_lanes=...` vor dem Parsen. Noch nicht gebaut/installiert
(App lief zum Zeitpunkt der Änderung noch, kein Neustart gewünscht).

**Nächster Schritt beim nächsten Build:** `gradle assembleDebug`, installieren,
dieselbe Kreuzung (Fronmüllerstraße -> Schwabacher Straße) nochmal abfahren,
`adb logcat -s OsmAndLink` mitlaufen lassen (Hinweis: das dokumentierte
Logcat-Kommando lieferte beim letzten Test nichts -- siehe Nebenbefund unten,
ggf. `adb logcat` ohne Tag-Filter und selbst grep'en). Zeigt der rohe String
schon nur eine Zahl, ist es ein OsmAnd-seitiges Problem (Upstream-Report);
zeigt er zwei, liegt der Bug doch in TrailBridge und die obige Durchsicht hat
etwas übersehen.

## Bekannte, bereits dokumentierte Einschränkung (kein neuer Fund)

`OsmAndLink.java`, Kommentar bei `getLeftDistance()`/Distanz-Handling
(~Zeile 270-276): OsmAnds AIDL verwirft den eigentlichen Rückgabewert für
die Spur-Distanz upstream (verifiziert gegen OsmAnd master, Stand
September 2026) - deckt sich mit PROTOCOL.md's Hinweis, dass
`LANE_DISTANCE_M`/`NEXT_LANE_DISTANCE_M` aktuell denselben Wert wie die
Manöver-Distanz tragen. Firmware-seitig wurde das bereits berücksichtigt
(LANE_DISTANCE_M wird dort nicht für Anzeige-Entscheidungen verwendet).
Nur zur Vollständigkeit hier erwähnt, kein offener Punkt.
