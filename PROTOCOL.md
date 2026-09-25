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

40 Bytes gesamt -- passt locker in die ausgehandelte ATT-MTU.

## Warum Indicate statt Notify

Bei Indicate bestätigt der Empfänger (ESP32) jedes Paket auf ATT-Ebene, bei
Notify nicht. Eine verpasste Abbiegeanweisung ist ärgerlicher als ein paar
Millisekunden zusätzliche Latenz -- deshalb Indicate.

## Kompatibilität

Byte 0 (Versionsnummer) erlaubt es der Firmware, ein zukünftiges Protokoll
v2 zu erkennen und notfalls auf ein einfacheres Rendering zurückzufallen,
statt falsch interpretierte Daten anzuzeigen.
