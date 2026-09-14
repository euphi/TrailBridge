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

## Warum Indicate statt Notify

Bei Indicate bestätigt der Empfänger (ESP32) jedes Paket auf ATT-Ebene, bei
Notify nicht. Eine verpasste Abbiegeanweisung ist ärgerlicher als ein paar
Millisekunden zusätzliche Latenz -- deshalb Indicate.

## Kompatibilität

Byte 0 (Versionsnummer) erlaubt es der Firmware, ein zukünftiges Protokoll
v2 zu erkennen und notfalls auf ein einfacheres Rendering zurückzufallen,
statt falsch interpretierte Daten anzuzeigen.
