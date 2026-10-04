# TrailBridge BLE-Protokoll v1

## BLE-Rollen

- **Companion-App (Handy) = Peripheral / GATT-Server**
- **TRGB-BikeComputer (ESP32) = Central / GATT-Client** (wie bisher bei Komoot
  -- das bestehende Verbindungsmanagement in `BLEDevices.cpp` bleibt also in
  seiner Grundstruktur erhalten, nur der Parser ändert sich, siehe Meilenstein 3)

## UUIDs

- Service:        `f7ac2b76-986b-45fd-8e44-f116a61f319d`
- Characteristic: `7473da02-2de8-4f48-9e46-21b36380c176`
  Properties: `INDICATE`, `READ`

Frisch generiert, keine Wiederverwendung von Komoots alten UUIDs -- neues
Protokoll, neue Kennung.

## Übertragungsart

- **Primär: Indicate** (mit Bestätigung) auf obiger Characteristic. Der ESP32
  aktiviert das durch Schreiben von `0x0200` auf den Standard-CCCD-Descriptor
  (`00002902-0000-1000-8000-00805f9b34fb`) -- genau die Stelle, die im alten
  Code auskommentiert war (`pRemoteCharacteristic->getDescriptor(...)`).
- **Fallback: Read.** Liefert den zuletzt gesendeten Frame, z.B. direkt nach
  Verbindungsaufbau, bevor die erste Indicate eintrifft, oder falls Indicate
  auf einem ESP32-BLE-Stack mal zickt.
- **Heartbeat:** Die Companion-App sendet den zuletzt berechneten Frame auch
  ohne inhaltliche Änderung alle 5s erneut, damit die Firmware eine tote
  Verbindung von "keine Änderung, weil gerade an der Ampel" unterscheiden
  kann.

## Frame-Format

```
Byte 0:   Protokoll-Version (aktuell 1)
Byte 1:   Message-Type
Byte 2..: TLV-Einträge (nur bei NAV_UPDATE)
```

Message-Types:

| Wert | Name | Bedeutung |
|---|---|---|
| 0x00 | HELLO | Lebenszeichen/Versions-Check, keine weiteren Daten |
| 0x01 | NAV_UPDATE | aktive Navigation, gefolgt von TLV-Einträgen |
| 0x02 | NAV_NONE | keine aktive Route, keine weiteren Daten |

TLV-Eintrag: `Tag (1 Byte) | Länge N (1 Byte) | Wert (N Byte)`

| Tag | Name | Länge | Format | Bedingung |
|---|---|---|---|---|
| 0x01 | MANEUVER | 1 | Manöver-Code (Tabelle unten) | immer |
| 0x02 | MANEUVER_DISTANCE_M | 4 | uint32 LE, Meter | immer |
| 0x03 | ROUNDABOUT_EXIT | 1 | Ausfahrtsnummer, 1-basiert | nur wenn MANEUVER == ROUNDABOUT |
| 0x04 | STREET_NAME | ≤48 | UTF-8, kein Nullterminator | immer (kann leer sein) |
| 0x05 | NEXT_MANEUVER | 1 | Manöver-Code | nur wenn übernächstes Manöver bekannt |
| 0x06 | NEXT_MANEUVER_DISTANCE_M | 4 | uint32 LE, Meter (ab dem *nächsten* Manöver gerechnet) | wie 0x05 |
| 0x07 | NEXT_STREET_NAME | ≤48 | UTF-8 | wie 0x05 |
| 0x08 | REMAINING_DISTANCE_M | 4 | uint32 LE, Meter bis Ziel | immer |
| 0x09 | REMAINING_TIME_S | 4 | uint32 LE, Sekunden bis Ziel | immer |
| 0x0A | LANES | N (Vielfaches von 4) | Fahrspurliste, siehe unten | nur wenn OsmAnd Fahrspurdaten liefert (`turn_lanes`, selten auf reinen Radwegen) |
| 0x0B | NEXT_LANES | N (Vielfaches von 4) | wie 0x0A | wie 0x0A, aber für die übernächste Abbiegung (wie 0x05/0x06/0x07) |
| 0x0C | LANE_DISTANCE_M | 4 | uint32 LE, Meter bis zu dem Punkt, an dem die LANES-Angabe gilt | nur wenn Tag 0x0A vorhanden |
| 0x0D | NEXT_LANE_DISTANCE_M | 4 | uint32 LE, Meter | nur wenn Tag 0x0B vorhanden |
| 0x0E | OVERVIEW_REVISION | 1 | uint8, 1..255 | nur bei Navigation aus einer GPX-Route: Revision der Streckenübersicht, zu der dieser Frame gehört, siehe "Streckenübersicht-Service" |

**Unbekannte Tags muss die Firmware überspringen** (Länge respektieren, Wert
ignorieren) -- so bleibt Raum für spätere Erweiterungen, ohne dass ältere
Firmware daran zerbricht. Das ist der ganze Sinn von TLV statt eines starren
Byte-Layouts wie bei Komoot.

## Manöver-Codes

Eigene, feste Codes -- unabhängig von Komoots altem 32-Slot-Icon-Index und
von OsmAnds internen `TurnType`-Konstanten. Referenz-Implementierung:
`Maneuver.java`.

| Code | Name |
|---|---|
| 0 | NONE |
| 1 | DEPART |
| 2 | ARRIVE |
| 3 | STRAIGHT |
| 4 | TURN_SLIGHT_LEFT |
| 5 | TURN_LEFT |
| 6 | TURN_SHARP_LEFT |
| 7 | TURN_SLIGHT_RIGHT |
| 8 | TURN_RIGHT |
| 9 | TURN_SHARP_RIGHT |
| 10 | KEEP_LEFT |
| 11 | KEEP_RIGHT |
| 12 | UTURN_LEFT |
| 13 | UTURN_RIGHT |
| 14 | ROUNDABOUT (Ausfahrt in Tag 0x03) |
| 255 | UNKNOWN |

## Fahrspur-Informationen (LANES / NEXT_LANES)

Bildet OsmAnds Fahrspur-Führung ab (AIDL-`turnInfo`-Bundle-Keys
`next_turn_lanes` / `after_nextturn_lanes`, gespeist aus dem OSM-Tag
`turn:lanes`; verifiziert gegen `ExternalApiHelper#updateRouteDirectionInfo`
und `net.osmand.router.TurnType` im OsmAnd-Quellcode, Stand 2026-09-18).
OsmAnd liefert das als `int[]`, ein bit-gepacktes Int pro Fahrspur (Bit 0 =
aktiv/empfohlen, Bits 1-4 = primäre Richtung, weitere Bits sekundär/
tertiär). Wie bei den Manöver-Codes übernehmen wir dieses Bit-Layout nicht
1:1, sondern übersetzen jede Richtung in unsere eigene Manöver-Code-Tabelle
(oben) -- damit hängt die Firmware nicht an OsmAnds internen
`TurnType`-Konstanten.

Wert von Tag 0x0A/0x0B: eine Liste von Fahrspuren, von links nach rechts
(wie im OSM-Tagging und in OsmAnds Array-Reihenfolge), je 4 Byte:

| Byte | Bedeutung |
|---|---|
| 0 | primärer Manöver-Code -- Haupt-Pfeilrichtung dieser Spur |
| 1 | sekundärer Manöver-Code, `0` (NONE) = keine zweite Richtung |
| 2 | tertiärer Manöver-Code, `0` (NONE) = keine dritte Richtung |
| 3 | Flags -- Bit 0 = `ACTIVE` (von OsmAnds Router für die berechnete Route empfohlen), Bits 1-7 reserviert (aktuell `0`, von der Firmware zu ignorieren) |

Byte 0 ist nie `0`: Eine Spur ohne explizit gesetzte primäre Richtung
behandelt OsmAnd intern bereits als "geradeaus" (siehe
`TurnType.lanesToString()`), die App bildet diesen Fallback schon auf
STRAIGHT (Code 3) ab, bevor sie den Frame baut -- die Firmware muss `0` in
Byte 0 also nicht gesondert behandeln.

Länge N = Anzahl Fahrspuren × 4 (das TLV-Längenbyte begrenzt das auf maximal
63 Fahrspuren -- reale Straßen haben selten mehr als 6-8).

Die reservierten Flag-Bits lassen Raum für eine mögliche spätere Erweiterung
um fahrradspezifisches Spur-Tagging oder weitere Spureigenschaften, ohne die
Byte-Breite pro Spur nachträglich ändern zu müssen.

### Distanz (LANE_DISTANCE_M / NEXT_LANE_DISTANCE_M)

Der Punkt, an dem eine Fahrspurwahl relevant wird (z.B. wo sich eine Spur
auffächert), muss NICHT mit dem Punkt der eigentlichen Abbiege-Anweisung
zusammenfallen -- auf mehrspurigen Straßen liegt er typischerweise deutlich
davor. LANES/NEXT_LANES tragen deshalb eine eigene, von
MANEUVER_DISTANCE_M/NEXT_MANEUVER_DISTANCE_M unabhängige Distanz (Tag
0x0C/0x0D) -- die Firmware darf die beiden Distanzen nicht gleichsetzen.

**Aktueller Stand auf der App-Seite (Stand 2026-09-19):** OsmAnds AIDL
hängt `next_turn_lanes`/`after_nextturn_lanes` an dieselbe
`RouteDirectionInfo`/`NextDirectionInfo` wie die zugehörige
Abbiege-Distanz -- über die AIDL-API sind beide Distanzen also aktuell
tatsächlich identisch. `ExternalApiHelper#getRouteDirectionsInfo` hat
zwar einen zweiten Mechanismus (`no_speak_next_`-Bundle-Präfix), der dem
Namen nach genau für einen früheren, nicht angesagten Fahrspur-Punkt
gedacht ist -- der Rückgabewert des zugehörigen
`getNextRouteDirectionInfo(..., false)`-Aufrufs wird dort aber verworfen
und stattdessen das schon belegte (veraltete) `ni` von `after_next`
wiederverwendet:

```java
routingHelper.getNextRouteDirectionInfo(new NextDirectionInfo(), false);
if (ni.distanceTo > 0) {
    updateTurnInfo("no_speak_next_", bundle, ni);
}
```

`no_speak_next_*` ist damit im aktuellen OsmAnd-Master (verifiziert
2026-09-19, `OsmAnd/src/net/osmand/plus/helpers/ExternalApiHelper.java`)
faktisch ein Duplikat von `after_next*` statt eines eigenen früheren
Punkts -- nutzbar ist es so nicht. Bis das upstream behoben ist (oder ein
anderer Weg gefunden wird), sendet die App für
LANE_DISTANCE_M/NEXT_LANE_DISTANCE_M denselben Wert wie
MANEUVER_DISTANCE_M/NEXT_MANEUVER_DISTANCE_M. Die eigenständigen Tags
bleiben trotzdem bestehen, damit sich das später nachrüsten lässt, ohne
den Wire-Vertrag zu brechen.

### Beispiel

Zwei Fahrspuren, 45 m voraus (die Spurwahl wird hier schon relevant, obwohl
die eigentliche Abbiegung laut MANEUVER_DISTANCE_M erst in 300 m kommt):
linke Spur nur geradeaus, rechte Spur geradeaus-oder-rechts und vom Router
für die Route empfohlen:

```
0A 08                                              LANES, Länge 8 (2 Spuren)
   03 00 00 00                                     Spur 1: STRAIGHT, --, --, Flags=0
   03 08 00 01                                     Spur 2: STRAIGHT, TURN_RIGHT, --, Flags=ACTIVE
0C 04 2D 00 00 00                                  LANE_DISTANCE_M = 45
```

## Beispiel

"In 120 m links abbiegen auf die Bahnhofstraße, danach in 300 m rechts
halten, noch 4200 m / 600 s bis Ziel":

```
01 01                                              version=1, type=NAV_UPDATE
01 01 05                                           MANEUVER = TURN_LEFT (5)
02 04 78 00 00 00                                  MANEUVER_DISTANCE_M = 120
04 0E 42 61 68 6E 68 6F 66 73 74 72 61 C3 9F 65     STREET_NAME = "Bahnhofstraße"
05 01 0B                                           NEXT_MANEUVER = KEEP_RIGHT (11)
06 04 2C 01 00 00                                  NEXT_MANEUVER_DISTANCE_M = 300
08 04 68 10 00 00                                  REMAINING_DISTANCE_M = 4200
09 04 58 02 00 00                                  REMAINING_TIME_S = 600
```

37 Bytes gesamt -- passt mit großem Puffer in die per `setMTU(256)`
verhandelte ATT-MTU von 256 Byte (253 Byte nutzbar).

## GPS-Positions-Service

Eigener BLE-Service, unabhängig vom Navigations-Service oben. Die Positionsdaten
kommen direkt vom GPS-Chip des Handys (`LocationManager.GPS_PROVIDER`), nicht
von OsmAnd -- funktioniert also auch, wenn OsmAnd gar nicht läuft oder nicht
installiert ist.

### UUIDs

- Service:        `66b5835c-9be6-43d1-b24a-f9337c0fcb7f`
- Characteristic: `10c49e7b-4808-4d63-9b68-9ba6c385db0d`
  Properties: `INDICATE`, `READ`

Wird bewusst NICHT im Advertising-Paket beworben (31-Byte-Legacy-Limit --
siehe Kommentar in `BikeComputerGattServer`, der Nav-Service allein füllt das
Hauptpaket schon aus). Der BikeComputer findet ihn nach dem Verbindungsaufbau
über normale GATT-Service-Discovery, genau wie er auch ohne Advertising-Eintrag
z.B. das Battery-Service der anderen Sensoren findet.

### Übertragungsart

Wie beim Navigations-Service: Indicate primär, Read als Fallback,
Heartbeat alle 5s -- unabhängig vom Navigations-Heartbeat/-State getaktet.

**Wichtig für eine Firmware-Neuimplementierung:** Beide Services teilen sich
einen einzigen `BluetoothGattServer`-Prozess auf App-Seite. Die Android- (und
allgemein BLE-)Plattformvorgabe erlaubt pro verbundenem Gerät nur eine
Indicate/Notify gleichzeitig "in flight", auch über Characteristics/Services
hinweg -- `onNotificationSent()` sagt nicht, welche Characteristic gerade
fertig wurde. `BikeComputerGattServer` serialisiert Nav- und
Positions-Frames deshalb über ein gemeinsames In-Flight-Gate. Für den ESP32
als Central spielt das keine Rolle (der empfängt einfach zwei unabhängige
Indicate-Streams), nur relevant, falls die App-Seite mal neu gebaut wird.

### Frame-Format

```
Byte 0:   Protokoll-Version (aktuell 1)
Byte 1:   Message-Type
Byte 2..: TLV-Einträge (nur bei POSITION_UPDATE)
```

Message-Types:

| Wert | Name | Bedeutung |
|---|---|---|
| 0x00 | HELLO | Lebenszeichen/Versions-Check, keine weiteren Daten |
| 0x01 | POSITION_UPDATE | gültiger GPS-Fix, gefolgt von TLV-Einträgen |
| 0x02 | POSITION_NONE | (noch) kein Fix -- GPS aus, Berechtigung fehlt, oder noch kein Satellitenempfang |

TLV-Eintrag: `Tag (1 Byte) | Länge N (1 Byte) | Wert (N Byte)`

| Tag | Name | Länge | Format | Bedingung |
|---|---|---|---|---|
| 0x01 | LATITUDE_E7 | 4 | int32 LE, Breitengrad × 1e7 | immer |
| 0x02 | LONGITUDE_E7 | 4 | int32 LE, Längengrad × 1e7 | immer |
| 0x03 | ALTITUDE_M | 4 | int32 LE, Meter über Ellipsoid | nur wenn der Fix eine Höhe liefert |
| 0x04 | SPEED_CMS | 4 | uint32 LE, cm/s | nur wenn der Fix eine Geschwindigkeit liefert |
| 0x05 | BEARING_DEG_X100 | 2 | uint16 LE, Grad × 100 (0..35999) | nur wenn der Fix einen Kurs liefert |
| 0x06 | ACCURACY_M_X10 | 2 | uint16 LE, Meter × 10 (horizontale Genauigkeit) | nur wenn der Fix eine Genauigkeit liefert |
| 0x07 | FIX_AGE_MS | 4 | uint32 LE, Millisekunden seit diesem Fix | immer |
| 0x08 | UTC_TIME_MS | 8 | uint64 LE, UTC-Zeit des Fixes in ms seit 1970-01-01 (`Location.getTime()`) | wenn der Fix eine Zeit hat (> 0) |
| 0x09 | HEART_RATE_BPM | 1 | uint8, Schläge/min | nur wenn TrailBridge einen Puls kennt, siehe "Sensorwerte und Simulationsmodus" |
| 0x0A | CADENCE_RPM | 1 | uint8, Umdrehungen/min, 0 = rollen | wie 0x09, für die Trittfrequenz |
| 0x0B | SIM_FLAGS | 1 | uint8-Bitfeld, siehe unten | nur wenn etwas an diesem Frame simuliert ist (sonst fehlt der Tag) |
| 0x0C | BARO_HEIGHT_DM | 4 | int32 LE, Höhe in Dezimetern über NHN | nur wenn TrailBridge eine Barometer-Höhe kennt, siehe unten |
| 0x0D | POWER_W | 2 | uint16 LE, Watt (0 = rollen) | nur wenn TrailBridge eine Leistung kennt, siehe unten |
| 0x0E | MSL_ALTITUDE_DM | 4 | int32 LE, Höhe in Dezimetern über dem Meeresspiegel (Geoid, NHN) | nur bei einem echten Fix, dessen Höhe das Handy über NHN kennt (Android 14+), siehe unten |

E7-Fixpunkt für Lat/Lon (statt Gleitkomma) aus demselben Grund wie überall
sonst im TLV-Format: festes, plattformunabhängiges Byte-Layout, kein
Float-Endianness-/NaN-Ärger. ±90°/±180° × 1e7 passen beide bequem in ein
signed int32 (max. ±2.147 Mrd.).

FIX_AGE_MS erlaubt der Firmware zu erkennen, ob der letzte Fix frisch ist oder
nur der Heartbeat einen alten Stand wiederholt (Tunnel, Satellitenausfall
o.ä.) -- wird bei jedem Senden aus `SystemClock.elapsedRealtime() -
location.getElapsedRealtimeNanos()/1_000_000` neu berechnet, ist also auch
bei Heartbeat-Resends korrekt und nicht auf den ursprünglichen Fix-Zeitpunkt
eingefroren.

UTC_TIME_MS ist die Zeitquelle für die Uhr des BikeComputers, wenn kein WLAN
(NTP) da ist. Bei `GPS_PROVIDER` ist das die Satellitenzeit, also unabhängig
von der Handy-Uhr. Die aktuelle Zeit beim Senden ist
`UTC_TIME_MS + FIX_AGE_MS` -- beide Werte kommen aus demselben Frame, der
Empfänger muss also nicht wissen, wann der Fix entstand. Die BLE-Latenz
(typisch < 100 ms) bleibt unberücksichtigt. **TrailBridge sendet die GPS-Zeit nur, wenn sie
zur Uhr des Handys passt** (±5 s, `GpsTime`), sonst die **Handy-Zeit** (als Zeit des Fixes: Handy-Jetzt
minus `FIX_AGE_MS`): der GNSS-Chip kann gleich nach dem Einschalten eine Zeit liefern, die
Minuten bis Stunden danebenliegt (Testfahrt 2026-10-04: 14,8 h zurück, bei richtiger Position).
Die Firmware prüft das Ergebnis noch einmal gegen ihre eigene Uhr (`ClockSync`). Nachträglich ergänzt, ohne
Versionssprung: Firmware, die den Tag nicht kennt, überspringt ihn über die
Länge; eine App ohne den Tag stellt eben keine Uhr.

### Beispiel

Fix bei 52.5163° N, 13.3777° O, 34 m Höhe, 4,2 m/s, Kurs 87,5°, ±5 m
Genauigkeit, vor 320 ms:

```
01 01                                              version=1, type=POSITION_UPDATE
01 04 F8 59 4D 1F                                  LATITUDE_E7 = 525163000
02 04 68 46 F9 07                                  LONGITUDE_E7 = 133777000
03 04 22 00 00 00                                  ALTITUDE_M = 34
04 04 A4 01 00 00                                  SPEED_CMS = 420
05 02 2E 22                                        BEARING_DEG_X100 = 8750
06 02 32 00                                         ACCURACY_M_X10 = 50
07 04 40 01 00 00                                  FIX_AGE_MS = 320
```

40 Bytes gesamt (mit UTC_TIME_MS 50) -- passt locker in die ausgehandelte ATT-MTU.

## Höhenprofil-Service

Dritter, unabhängiger BLE-Service, nur aktiv, solange TrailBridge eine
importierte GPX-Route abspielt (siehe README) und die Route Höhendaten hat.
Das Profil wird **immer** gesendet -- auch auf flacher Strecke und bergab, es ist
immer von Interesse (Testfahrt 2026-10-04) --, als **rollendes Fenster der
Strecke voraus**, von der Position des Fahrers an. Ein **Anstieg** darin wird
nicht von der Firmware aus den Punkten erraten, sondern von TrailBridge
**angesagt**, mit seiner ganzen Ausdehnung (Tags 0x07..0x0A, siehe unten): so
endet er nie vor dem Gipfel, und Kategorie und Länge bleiben die vom Fuß,
auch wenn der Fahrer schon mittendrin ist und der Fuß nicht mehr im Frame liegt.
Das Flag `ROLLING` (Tag 0x06) kennzeichnet solche Frames.

Ein neuer Frame geht raus,

- zu Beginn und nachdem das Profil zurückgenommen wurde (Fahrer abseits der Route),
- wenn sich der anzusagende Anstieg ändert: sein Fuß kommt auf höchstens 500 m
  heran (das Fenster reicht dann vom Fahrer **bis zum Gipfel**), oder sein Gipfel
  ist erreicht (das Fenster ohne Ansage, die Strecke hinter dem Gipfel),
- wenn der Fahrer die Mitte eines Frames passiert hat, der nicht so weit reicht, wie er
  sollte: das Fenster wandert weiter (Fenster ohne Anstieg: 200 Schritte à 25 m = 5 km;
  ein Anstieg, der selbst auf dem gröbsten Raster nicht in einen Frame passt).

Dazwischen kommt nichts. **`PROFILE_NONE` nur noch**, wenn die Route endet oder der
Fahrer sie verlässt -- nicht mehr am Gipfel: dort fehlt einfach die Ansage im nächsten
Frame, und die Firmware weiß, dass der Anstieg vorbei ist. **Kein Heartbeat** -- der
zuletzt gesendete Frame bleibt per Read abrufbar und geht an neu abonnierende Geräte.

Ältere TrailBridge-Versionen (ohne Flag) senden nur das Profil eines Anstiegs, von der
Position bis zum Gipfel, und `PROFILE_NONE` am Gipfel; die Firmware findet den Anstieg
dann selbst im Profil (siehe unten) und bleibt damit kompatibel.

### Was ein Anstieg ist

TrailBridge sucht die Anstiege beim Laden der Route (25-m-Raster, über ca.
125 m geglättet; `ElevationProfile.findClimbs`):

- **Fuß:** der erste Punkt, ab dem die Route auf den nächsten 100 m um
  mindestens 3 m steigt. Ein sanfter Beginn gehört also schon zum Anstieg.
- **Gipfel:** der höchste Punkt, bevor die Route entweder um **mehr als 30 m
  abfällt** oder **über 2 km nicht weiter steigt** (im Mittel weniger als
  0,5 % ab dem bisher höchsten Punkt) -- oder endet. Eine kürzere Senke oder
  ein kürzeres Flachstück gehört zum Anstieg.
- Unter 10 m Höhenunterschied zwischen Fuß und Gipfel ist es kein Anstieg.

Beispiel Galibier: Von Süden (36 km über den Lautaret, mit Flachstücken) ist
es ein Anstieg. Von Norden sind es zwei -- nach dem Col du Télégraphe geht es
knapp 5 km und rund 165 m bergab nach Valloire.

Nur für Frames **ohne** `ROLLING` (ältere TrailBridge-Versionen) findet die Firmware
(`ClimbProfile.cpp`) Fuß und Gipfel im gesendeten Profil mit denselben Kriterien; das
Profilende gilt ihr dann als Gipfel. Bei `ROLLING` gilt allein die Ansage; die
Kriterien dieses Abschnitts wendet TrailBridge an.

### UUIDs

- Service:        `3c1f6a90-5b2e-4d7a-9c48-e0a1b7d25f63`
- Characteristic: `a84e0d17-6f3b-4c52-8e9d-1b70c2f4a596`
  Properties: `INDICATE`, `READ`

Wie der Position-Service nicht im Advertising, Discovery nach dem
Verbindungsaufbau. Teilt sich das In-Flight-Gate mit den anderen Services
(siehe oben).

### Frame-Format

```
Byte 0:   Protokoll-Version (aktuell 1)
Byte 1:   Message-Type
Byte 2..: TLV-Einträge (nur bei PROFILE_UPDATE)
```

| Wert | Name | Bedeutung |
|---|---|---|
| 0x00 | HELLO | Zustand nach Verbindungsaufbau, kein Profil |
| 0x01 | PROFILE_UPDATE | Profil, gefolgt von TLV-Einträgen; ersetzt ein früheres |
| 0x02 | PROFILE_NONE | kein (Steigungs-)Profil mehr anzeigen |

| Tag | Name | Länge | Format |
|---|---|---|---|
| 0x01 | START_REMAINING_DISTANCE_M | 4 | uint32 LE, Restdistanz der Route bis zum Ziel **am ersten Profilpunkt** |
| 0x02 | STEP_M | 1 | Abstand der Profilpunkte in Metern: 25, 50, ... 250 (siehe "Raster") |
| 0x03 | BASE_ALT_DM | 2 | int16 LE, Höhe des ersten Punkts in Dezimetern |
| 0x04 | DELTAS_DM | N | N × int8: Höhenänderung von Punkt k zu Punkt k+1, in Einheiten von `DELTA_SCALE_DM` dm |
| 0x05 | DELTA_SCALE_DM | 1 | uint8, optional: Einheit der Deltas in Dezimetern. Fehlt der Tag, gilt 1 |
| 0x06 | FLAGS | 1 | uint8, optional: Bit 0 `ROLLING` = rollendes Fenster der Strecke voraus; ein Anstieg ist nur der in 0x07..0x0A angesagte. Fehlt der Tag: Frame eines älteren TrailBridge (Profil bis zum Gipfel) |
| 0x07 | CLIMB_FOOT_REMAINING_M | 4 | uint32 LE, Restdistanz der Route bis zum Ziel **am Fuß** des angesagten Anstiegs (kann hinter dem ersten Punkt liegen, also größer als `START_REMAINING_DISTANCE_M`) |
| 0x08 | CLIMB_SUMMIT_REMAINING_M | 4 | uint32 LE, Restdistanz am **Gipfel** (kann hinter dem letzten Punkt liegen) |
| 0x09 | CLIMB_FOOT_ALT_DM | 2 | int16 LE, Höhe des Fußes in Dezimetern |
| 0x0A | CLIMB_SUMMIT_ALT_DM | 2 | int16 LE, Höhe des Gipfels in Dezimetern |

Die vier Anstiegs-Tags kommen **alle zusammen oder gar nicht**; fehlen sie bei gesetztem
`ROLLING`, liegt kein Anstieg an (weder unter dem Fahrer noch höchstens 500 m voraus).
Angesagt wird der Anstieg, auf dem der Fahrer ist oder dessen Fuß höchstens 500 m voraus
liegt, bis der Gipfel erreicht ist (auf eine halbe Rasterstufe genau). Wiederholte Frames
desselben Anstiegs tragen **dieselben** Werte -- die Firmware erkennt ihn daran wieder und
hält Kategorie und Anzeige stabil. Kategorie = Länge × mittlere Steigung des ganzen
Anstiegs (Fuß bis Gipfel), nicht des Rests.

Punkt k liegt bei `START_REMAINING_DISTANCE_M - k × STEP_M` Restdistanz,
seine Höhe ist `BASE_ALT_DM + DELTA_SCALE_DM × Σ DELTAS_DM[0..k-1]` (in dm).
Es gibt N+1 Punkte. Die Höhen sind über ca. 125 m geglättet (GPX-Höhen sind
verrauscht).

**Raster:** Der ganze Rest des Anstiegs soll in einen Frame passen. Bis
N × 25 m (5 km bei N = 200) ist `STEP_M` 25; für längere Anstiege wird es das
kleinste Vielfache von 25 m, mit dem es passt, höchstens 250 m (50 km bei
N = 200; ein 36-km-Anstieg kommt mit 200 m). Das Raster wird vom Gipfel aus
rückwärts gelegt: **Der letzte Punkt ist der Gipfel**, der erste liegt an der
Position des Fahrers oder weniger als einen Schritt dahinter (nur direkt am
Routenanfang kann er knapp vor dem Fahrer liegen).

Auf einem groben Raster passt ein steiler Schritt nicht mehr als dm in ein
int8 (±12,7 m; bei 200 m wären das 6,35 %). Dann sendet TrailBridge
`DELTA_SCALE_DM` mit dem kleinsten Wert, bei dem alle Deltas passen (z. B. 2 =
Deltas in 0,2 m). Bei `STEP_M` = 25 fehlt der Tag immer; Frames für Anstiege
bis 5 km sehen also aus wie bisher.

Passt ein Anstieg selbst mit 250 m nicht in einen Frame (kleine MTU, oder
über 50 km), endet das Profil nach N Schritten vor dem Gipfel. Das nächste
Stück folgt, wenn der Fahrer die Mitte des gesendeten erreicht hat -- auf
demselben Raster (gleiches `STEP_M`, Abstand der Startpunkte ein Vielfaches
davon), damit die Firmware die Stücke zusammenfügen kann.

**Position im Profil ohne Wegstreckenzähler:** Die Firmware kennt
`REMAINING_DISTANCE_M` aus dem Nav-Frame (0x08); die Position des Fahrers im
Profil ist `START_REMAINING_DISTANCE_M - REMAINING_DISTANCE_M` Meter ab dem
ersten Punkt. Das gilt entlang der Route, nicht der Luftlinie, und übersteht
Reconnects. Liegt der Wert außerhalb `0..N × STEP_M`, ist das Profil
veraltet bzw. noch nicht erreicht.

**Länge und MTU:** N richtet sich nach der ausgehandelten MTU (Payload
`ATT_MTU - 3`): `N = min(200, Payload - 20)` (bei `ROLLING` zusätzlich 23 Byte für Flag und Anstiegs-Tags: `Payload - 43`); unter 8 Punkten (200 m) sendet
TrailBridge kein Profil. Die App nimmt das Minimum über alle verbundenen
Geräte und geht ohne Angabe von den 256 aus, die die Firmware anfordert.

Unbekannte Tags überspringt die Firmware wie überall (Länge respektieren).

### Beispiel

Profil ab 4200 m Restdistanz, Start auf 34,5 m, drei Schritte (+1,2 m,
+1,3 m, −0,2 m) -- ältere Form, ohne `ROLLING`:

```
01 01                                              version=1, type=PROFILE_UPDATE
01 04 68 10 00 00                                  START_REMAINING_DISTANCE_M = 4200
02 01 19                                           STEP_M = 25
03 02 59 01                                        BASE_ALT_DM = 345
04 03 0C 0D FE                                     DELTAS_DM = +12, +13, -2
```

Rollendes Fenster mit angesagtem Anstieg: Fuß bei 5000 m Restdistanz auf 100,0 m,
Gipfel bei 3500 m auf 136,0 m (die Punkte dazwischen wie oben):

```
01 01                                              version=1, type=PROFILE_UPDATE
01 04 68 10 00 00                                  START_REMAINING_DISTANCE_M = 4200
02 01 19                                           STEP_M = 25
03 02 59 01                                        BASE_ALT_DM = 345
06 01 01                                           FLAGS = ROLLING
07 04 88 13 00 00                                  CLIMB_FOOT_REMAINING_M = 5000 (hinter dem ersten Punkt)
08 04 AC 0D 00 00                                  CLIMB_SUMMIT_REMAINING_M = 3500
09 02 E8 03                                        CLIMB_FOOT_ALT_DM = 1000
0A 02 50 05                                        CLIMB_SUMMIT_ALT_DM = 1360
04 03 0C 0D FE                                     DELTAS_DM = +12, +13, -2
```

Ohne Anstieg: derselbe Frame ohne die vier Tags 0x07..0x0A.

Langer Anstieg auf grobem Raster: ab 36 000 m Restdistanz, Start auf 1200 m,
200-m-Schritte, Deltas in 0,2 m (+12,0 m, +13,8 m, −0,6 m):

```
01 01                                              version=1, type=PROFILE_UPDATE
01 04 A0 8C 00 00                                  START_REMAINING_DISTANCE_M = 36000
02 01 C8                                           STEP_M = 200
03 02 E0 2E                                        BASE_ALT_DM = 12000
05 01 02                                           DELTA_SCALE_DM = 2
04 03 3C 45 FD                                     DELTAS_DM = +60, +69, -3 (× 0,2 m)
```

## Streckenübersicht-Service

Vierter BLE-Service, nur aktiv, solange TrailBridge eine importierte GPX-Route
abspielt. Er sagt, was auf der Route noch **vor dem Fahrer** liegt: das Ziel, die
Wegpunkte der GPX-Datei (`wpt`) und die Anstiege. Anders als die anderen Services
wird er **nur gelesen** -- keine Indicate, kein CCCD, kein Heartbeat.

### Warum Read statt Indicate

- **Der Inhalt ändert sich selten:** beim Start der Route und immer dann, wenn ein
  Wegpunkt oder ein Gipfel erreicht ist. Entfernung und Zeit zu jedem Eintrag rechnet
  die Firmware aus dem Nav-Frame und den Ankern der Übersicht (siehe "Entfernung und
  Zeit") -- dafür muss die Übersicht nicht neu übertragen werden.
- **Die Länge:** Eine Indicate fasst höchstens `ATT_MTU - 3` Byte (253). Ein gelesener
  Wert darf bis 512 Byte lang sein; der Client holt ihn in Stücken (Read Blob, "Long
  Read"). `readValue()` der ESP32-BLE-Bibliothek macht das von selbst, mit NimBLE wie
  mit Bluedroid. Zehn Wegpunkte mit Namen passen nicht in eine Indicate, aber in einen
  Read.
- **Das In-Flight-Gate:** Über alle Services ist immer nur eine Indicate unterwegs.
  Eine große Übersicht würde Abbiegehinweise aufhalten; ein Read läuft daran vorbei.

Einem Read fehlt das Signal "es gibt etwas Neues". Das liefert `OVERVIEW_REVISION`
(Tag 0x0E) im Nav-Frame, der ohnehin jede Sekunde bzw. alle 5 s kommt.

### UUIDs

- Service:        `7ee954a6-7a19-4b48-b052-f00d00e01e58`
- Characteristic: `f031faa3-083d-4818-9eca-38420be835d0`
  Properties: `READ`

Wie die anderen Zusatz-Services nicht im Advertising, Discovery nach dem
Verbindungsaufbau.

### Ablauf

1. Ein Nav-Frame trägt `OVERVIEW_REVISION`, und der Wert ist ein anderer als die
   `REVISION` der gespeicherten Übersicht (oder es ist noch keine gespeichert): die
   Characteristic lesen. **Nicht im Indicate-Callback** -- `readValue()` blockiert.
2. Den gelesenen Frame samt seiner `REVISION` speichern. Zeigt der nächste Nav-Frame
   eine andere Revision, noch einmal lesen.
3. Ein Nav-Frame ohne `OVERVIEW_REVISION` (Navigation aus OsmAnd), `NAV_NONE` oder das
   Ende der Verbindung: die Übersicht verwerfen.

TrailBridge stellt immer zuerst den neuen Wert bereit und sendet danach den Nav-Frame
mit der neuen Revision. Die Revision ist 1..255 und zählt mit jeder neuen Übersicht
weiter, auch über Routen hinweg; nach 255 folgt 1, 0 kommt nicht vor.

### Frame-Format

```
Byte 0:   Protokoll-Version (aktuell 1)
Byte 1:   Message-Type
Byte 2..: TLV-Einträge (nur bei OVERVIEW)
```

| Wert | Name | Bedeutung |
|---|---|---|
| 0x00 | HELLO | Zustand nach dem Start der App, keine Übersicht |
| 0x01 | OVERVIEW | Übersicht, gefolgt von TLV-Einträgen |
| 0x02 | OVERVIEW_NONE | keine Route aktiv, keine Übersicht |

| Tag | Name | Länge | Format |
|---|---|---|---|
| 0x01 | REVISION | 1 | uint8, 1..255 -- die `OVERVIEW_REVISION` der Nav-Frames, zu denen diese Übersicht gehört |
| 0x02 | WAYPOINTS_AHEAD | 1 | uint8, Zahl der Wegpunkte voraus, ohne das Ziel (255 = 255 oder mehr) |
| 0x03 | CLIMBS_TOTAL | 1 | uint8, Zahl der Anstiege der ganzen Route (0 = keine, oder die Route hat keine Höhendaten) |
| 0x04 | WAYPOINT | 9 + N | ein Wegpunkt oder das Ziel, siehe unten; kommt mehrfach vor |
| 0x05 | CLIMB | 11 | ein Anstieg, siehe unten; kommt mehrfach vor |

Wert von `WAYPOINT`:

| Byte | Bedeutung |
|---|---|
| 0 | Flags -- Bit 0 = `DESTINATION` (das Ziel der Route), Bits 1-7 reserviert (`0`, zu ignorieren) |
| 1-4 | `REMAINING_AT_M`, uint32 LE: Restdistanz der Route bis zum Ziel **an diesem Wegpunkt** (beim Ziel 0) |
| 5-8 | `REMAINING_TIME_AT_S`, uint32 LE: Restzeit bis zum Ziel **an diesem Wegpunkt**, Sekunden (beim Ziel 0); `0xFFFFFFFF` = unbekannt, die GPX hat keine Zeitstempel |
| 9.. | Name, UTF-8, höchstens 32 Byte, kein Nullterminator |

Wert von `CLIMB`:

| Byte | Bedeutung |
|---|---|
| 0 | Nummer des Anstiegs, 1-basiert in Fahrtrichtung über die ganze Route |
| 1-2 | Höhenmeter vom Fuß bis zum Gipfel, uint16 LE, Meter |
| 3-6 | Länge vom Fuß bis zum Gipfel, uint32 LE, Meter |
| 7-10 | `FOOT_REMAINING_AT_M`, uint32 LE: Restdistanz der Route bis zum Ziel **am Fuß des Anstiegs** |

Ist ein `CLIMB`-Wert länger als 11 Byte, ignoriert die Firmware den Rest; unbekannte
Tags überspringt sie wie überall.

**Reihenfolge und Länge:** Nach den drei Kopf-Tags steht das Ziel, danach Wegpunkte
und Anstiege gemischt in der Reihenfolge, in der der Fahrer sie erreicht (ein Anstieg
zählt an seinem Fuß). Jede der beiden Listen ist also "der nächste zuerst". Der Frame
ist höchstens 512 Byte lang. Was nicht passt, fehlt am fernen Ende und rückt nach,
sobald vorn etwas erreicht ist. `WAYPOINTS_AHEAD` und `CLIMBS_TOTAL` sagen, wie viele
es wirklich sind -- sind es mehr als im Frame stehen, ist die Liste gekürzt.

### Wegpunkte

- Quelle sind die `wpt`-Elemente der GPX-Datei. Ein Wegpunkt zählt, wenn er höchstens
  100 m neben der Route liegt; er sitzt dann an der nächstgelegenen Stelle der Route.
  Führt die Route zweimal an ihm vorbei, zählt das erste Mal.
- Ein Wegpunkt ohne Namen heißt "Wegpunkt N" -- N ist seine Nummer unter allen
  Wegpunkten der Route, in Fahrtrichtung ab 1. Das Ziel heißt "Ziel".
- Erreicht der Fahrer einen Wegpunkt, fällt er aus der Übersicht (neue Revision). Er
  kommt erst wieder, wenn der Fahrer mehr als 100 m vor ihn zurückfällt.

### Entfernung und Zeit

Die Übersicht trägt nur Anker. Entfernung und Zeit ergeben sich mit dem letzten
Nav-Frame -- wie die Position im Höhenprofil, ohne Wegstreckenzähler und über
Reconnects hinweg:

```
Entfernung = REMAINING_DISTANCE_M - REMAINING_AT_M
Zeit       = REMAINING_TIME_S - REMAINING_TIME_AT_S                 (Zeitanker bekannt)
Zeit       = REMAINING_TIME_S × Entfernung / REMAINING_DISTANCE_M   (Zeitanker 0xFFFFFFFF)
```

Für das Ziel sind das in beiden Fällen genau `REMAINING_DISTANCE_M` und
`REMAINING_TIME_S`. Eine negative Entfernung heißt: der Wegpunkt ist gerade erreicht,
die neue Übersicht ist unterwegs -- nicht mehr anzeigen. Eine negative Zeit ist 0. In
der dritten Zeile passt das Produkt nicht immer in 32 Bit -- in 64 Bit rechnen; bei
`REMAINING_DISTANCE_M` = 0 ist die Zeit 0.

**Woher die Zeit kommt:** Hat die GPX-Datei Zeitstempel an ihren Punkten, nimmt
TrailBridge die Restzeit daraus -- für `REMAINING_TIME_S` im Nav-Frame wie für die
Zeitanker. Das ist bei einer aufgezeichneten Fahrt deren Zeit (jeder Halt
zählt höchstens 30 s) und bei einer geplanten Route die Schätzung des Routers, sofern er sie
in die Datei schreibt; sie kennt die Anstiege. BRouter (bikerouter.de) schreibt sie
nur, wenn das Profil `assign showtime = true` setzt, sonst steht nur die Gesamtzeit in
einem Kommentar. Wie schnell der Fahrer wirklich ist, geht in diese Zeit nicht ein.

Ohne Zeitstempel ist `REMAINING_TIME_S` die Reststrecke durch die geglättete aktuelle
Geschwindigkeit, die Zeitanker sind `0xFFFFFFFF`, und die dritte Zeile verteilt die
Restzeit nach der Strecke. Diese Schätzung weiß nichts von den Anstiegen dazwischen.

Abseits der Route bleiben `REMAINING_DISTANCE_M` und `REMAINING_TIME_S` stehen,
Entfernungen und Zeiten also auch.

### Anstiege

Ein Anstieg ist, was "Was ein Anstieg ist" oben beschreibt; Höhenmeter und Länge
gelten für den ganzen Anstieg vom Fuß bis zum Gipfel, auch wenn der Fahrer schon
darin ist. Die Liste beginnt mit dem Anstieg, in dem der Fahrer gerade fährt, sonst
mit dem nächsten. Am Gipfel fällt der Anstieg aus der Übersicht (neue Revision). Aus
Nummer und `CLIMBS_TOTAL` ergibt sich "Anstieg 3 von 7".

Die Entfernung bis zum Fuß ist `REMAINING_DISTANCE_M - FOOT_REMAINING_AT_M`. Ist
sie 0 oder negativ, fährt der Fahrer schon im Anstieg; bis zum Gipfel sind es dann
noch `Länge + Entfernung` Meter (die Entfernung ist dort negativ). Einen Zeitanker
haben Anstiege nicht. Das Profil eines Anstiegs kommt wie bisher über den
Höhenprofil-Service, 500 m vor seinem Fuß.

### Beispiel

Revision 3, zwei Wegpunkte voraus, der zweite von zwei Anstiegen noch nicht gefahren,
die GPX hat Zeitstempel: Ziel, "Bäcker" 30,5 km und 6100 s vor dem Ziel, Anstieg 2
(450 Hm auf 8,2 km, Fuß 22 km vor dem Ziel), ein namenloser Wegpunkt 12 km und 2400 s
vor dem Ziel:

```
01 01                                              version=1, type=OVERVIEW
01 01 03                                           REVISION = 3
02 01 02                                           WAYPOINTS_AHEAD = 2
03 01 02                                           CLIMBS_TOTAL = 2
04 0D 01 00 00 00 00 00 00 00 00 5A 69 65 6C       WAYPOINT: DESTINATION, 0 m, 0 s, "Ziel"
04 10 00 24 77 00 00 D4 17 00 00                   WAYPOINT: 30500 m, 6100 s,
      42 C3 A4 63 6B 65 72                                   "Bäcker"
05 0B 02 C2 01 08 20 00 00 F0 55 00 00             CLIMB: Nr. 2, 450 Hm, 8200 m, Fuß bei 22000 m
04 13 00 E0 2E 00 00 60 09 00 00                   WAYPOINT: 12000 m, 2400 s,
      57 65 67 70 75 6E 6B 74 20 32                          "Wegpunkt 2"
```

78 Bytes. Meldet der Nav-Frame dazu `REMAINING_DISTANCE_M` = 36000 und
`REMAINING_TIME_S` = 7200, ist der Bäcker 5,5 km und 1100 s entfernt, der Fuß von
Anstieg 2 14 km, der namenlose Wegpunkt 24 km und 4800 s, das Ziel 36 km und 7200 s.
Ohne Zeitstempel in der GPX stünde in den drei Wegpunkten `FF FF FF FF` als Zeitanker.

## Navigation aus einer GPX-Route

Wenn TrailBridge eine GPX-Route selbst abspielt, sendet es ganz normale
`NAV_UPDATE`-Frames im Nav-Service (Format oben, unverändert), berechnet
aus der GPS-Position. Besonderheiten gegenüber OsmAnd: keine Fahrspuren
(0x0A-0x0D), Straßennamen nur, wenn die GPX sie enthält. Abseits der Route
(> 50 m) steht `MANEUVER = UNKNOWN (255)`, `MANEUVER_DISTANCE_M` ist dann die
Entfernung zur Route und `STREET_NAME` "Abseits der Route". Jeder Frame trägt
außerdem `OVERVIEW_REVISION` (0x0E), auch abseits der Route. `REMAINING_TIME_S` kommt
aus den Zeitstempeln der GPX, wenn sie welche hat, siehe "Entfernung und Zeit".

## Sensorwerte und Simulationsmodus

Der Positions-Frame kann neben der Position auch **Sensorwerte** tragen, die
TrailBridge kennt: Puls (0x09), Trittfrequenz (0x0A), Barometer-Höhe (0x0C) und
Leistung (0x0D); die Geschwindigkeit steckt in `SPEED_CMS` (0x04). Ob die Werte
echt oder erfunden sind, sagt ein eigener Tag, damit beides nebeneinander
möglich ist:

**BARO_HEIGHT_DM** ist die Höhe, die der BikeComputer sonst von seinem Barometer
hat, in Dezimetern (`ALTITUDE_M` hat nur ganze Meter -- zu grob, um daraus über
ein paar Dutzend Meter Strecke eine Steigung zu rechnen). **Die Steigung wird
nicht übertragen:** der BikeComputer rechnet sie wie immer selbst aus Höhe und
zurückgelegter Strecke. **POWER_W** ist die Tretleistung eines Leistungsmessers.

**MSL_ALTITUDE_DM** ist die Höhe eines **echten** GPS-Fixes über dem Meeresspiegel, in
Dezimetern (`Location.getMslAltitudeMeters()`, ab Android 14). `ALTITUDE_M` dagegen ist die
Höhe über dem Ellipsoid und liegt in Deutschland rund 45-50 m daneben. Der BikeComputer
kalibriert damit sein Barometer (Taste "GPS" bei der Höhenkalibrierung). Der Tag fehlt, wenn
das Handy die Höhe nicht über NHN kennt, und bei erfundenen Positionen (`SIM_POSITION`).
Eine Höhe von GPS ist nur auf etwa 10 m genau.

**SIM_FLAGS (0x0B)**, uint8-Bitfeld. **Fehlt der Tag (oder ist er 0), ist alles
im Frame echt.**

| Bit | Name | Bedeutung, wenn gesetzt |
|---|---|---|
| 0 (0x01) | SIM_POSITION | Lat/Lon/Höhe/Kurs sind erfunden, nicht vom GPS-Chip (z.B. GPX-Testfahrt) |
| 1 (0x02) | SIM_SENSORS | `SPEED_CMS` ist die Geschwindigkeit eines **simulierten Speed-Sensors**; Puls, Trittfrequenz, Barometer-Höhe und Leistung sind simuliert -- und das **Fehlen** von 0x09/0x0A/0x0C/0x0D heißt "diesen Sensor gibt es nicht" |
| 2-7 | -- | reserviert, `0`, von der Firmware zu ignorieren |

Konsequenzen für die Firmware:

- **Sim-Daten dürfen nie als echte Messung behandelt werden.** Fahrdaten, die
  mit gesetztem SIM_POSITION oder SIM_SENSORS entstehen, gehören im Log als
  simuliert markiert (`LOG_SIMULATED`).
- **SIM_SENSORS:** nur ein Simulator-Build (`BC_SIM`) speist Geschwindigkeit,
  Puls, Trittfrequenz, Höhe und Leistung in seinen Sensor-Simulator ein
  (`SimSensors`, wie `sim <km/h> <rpm> <bpm>`; die Höhe ersetzt das
  Barometer, daraus entsteht die Steigung); die normale Firmware ignoriert die
  Werte, damit keine Fake-Kilometer in Odometer und Statistik landen. Ende der Simulation =
  der nächste Frame ohne SIM_SENSORS, `POSITION_NONE`, ein veralteter Fix
  (`FIX_AGE_MS` > 5 s) oder das Ende der Verbindung -> "Sensoren aus".
- **Ohne SIM_SENSORS** sind 0x09/0x0A/0x0C/0x0D echte, von TrailBridge weitergereichte
  Sensorwerte (z.B. ein Pulsgurt am Handy). **Noch nicht gebaut** -- die App
  sendet so etwas bisher nicht, die Firmware wertet es nicht aus. Der Tag und
  die Semantik stehen hier schon fest, damit sich das ohne Vertragsbruch
  nachrüsten lässt.
- Die Zeit (`UTC_TIME_MS`) bleibt auch in der Simulation die echte Uhrzeit des
  Handys -- die Uhr des BikeComputers soll richtig bleiben.

### GPX-Testfahrt

TrailBridge kann die geladene GPX-Route "abfahren" statt der echten
GPS-Position zu senden: einmal pro Sekunde ein Fix entlang der Route, mit
`SIM_FLAGS = 0x03` (SIM_POSITION | SIM_SENSORS). `SPEED_CMS`, `HEART_RATE_BPM`,
`CADENCE_RPM`, `POWER_W` kommen aus der GPX (Zeitstempel, TrackPointExtension
`hr`/`cad`, Leistung), wo sie fehlen, aus der Berechnung bzw. -- auf Wunsch --
zufällig variierenden, zur Strecke passenden Werten (Puls folgt der Belastung,
Trittfrequenz sinkt am Berg, 0 beim Rollen/Stehen; Leistung ergibt sich aus
Geschwindigkeit, Steigung und Beschleunigung, 0 beim Rollen); ohne Emulation
fehlt der Tag, der Sensor "ist nicht da". `BARO_HEIGHT_DM` ist die geglättete
Höhe der Route (fehlt, wenn die GPX keine Höhendaten hat). Pausiert die Testfahrt, kommen weiter Frames: Geschwindigkeit 0,
Trittfrequenz 0. Nav- und Höhenprofil-Service laufen wie bei einer echten Fahrt
aus der simulierten Position. Das Ende der Testfahrt ist der erste Frame ohne
SIM_FLAGS bzw. `POSITION_NONE`.

Beispiel, Testfahrt bei 21,6 km/h, Puls 132, Trittfrequenz 85 (Auszug, nur die
hinteren Tags des Frames):

```
09 01 84                                           HEART_RATE_BPM = 132
0A 01 55                                           CADENCE_RPM = 85
0B 01 03                                           SIM_FLAGS = SIM_POSITION | SIM_SENSORS
0C 04 80 0D 00 00                                  BARO_HEIGHT_DM = 3456 (345,6 m)
0D 02 FA 00                                        POWER_W = 250
```

## Warum Indicate statt Notify

Bei Indicate bestätigt der Empfänger (ESP32) jedes Paket auf ATT-Ebene, bei
Notify nicht. Eine verpasste Abbiegeanweisung ist ärgerlicher als ein paar
Millisekunden zusätzliche Latenz -- deshalb Indicate.

## Kompatibilität

Byte 0 (Versionsnummer) erlaubt es der Firmware, ein zukünftiges Protokoll
v2 zu erkennen und notfalls auf ein einfacheres Rendering zurückzufallen,
statt falsch interpretierte Daten anzuzeigen.
