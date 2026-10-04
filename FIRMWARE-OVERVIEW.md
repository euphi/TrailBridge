# Streckenübersicht: was der BikeComputer dafür braucht

Vorlage für die Firmware-Seite der Streckenübersicht. Die App-Seite ist fertig (auf
`main`, noch in keinem Release); an der Firmware ist nichts geändert. Das Wire-Format
steht verbindlich in [PROTOCOL.md](PROTOCOL.md), Abschnitt "Streckenübersicht-Service"
-- hier steht, was daraus für `TRGB-BikeComputer` folgt.

## Was ankommt

Solange TrailBridge eine GPX-Route abspielt (echt oder als Testfahrt):

- In jedem Nav-Frame ein neuer Tag `OVERVIEW_REVISION` (0x0E, 1 Byte). Die heutige
  Firmware überspringt ihn als unbekannten Tag.
- Ein vierter Service mit einer Characteristic, die sich nur lesen lässt. Ihr Wert ist
  die Übersicht: das Ziel, die Wegpunkte voraus (Name, Restdistanz und Restzeit der
  Route am Wegpunkt) und die Anstiege voraus (Nummer, Höhenmeter, Länge, Restdistanz
  am Fuß).

| | UUID |
|---|---|
| Service | `7ee954a6-7a19-4b48-b052-f00d00e01e58` |
| Characteristic (`READ`) | `f031faa3-083d-4818-9eca-38420be835d0` |

Bei Navigation aus OsmAnd fehlt der Tag, und die Characteristic liefert `OVERVIEW_NONE`.

## Was zu bauen ist

1. **Characteristic merken.** In `connectToServer()` nach `subscribeProfile()` den
   Service über `getService()` suchen, wie dort -- aber ohne `registerForNotify()`, es
   gibt kein CCCD. Fehlt der Service, ist es eine ältere TrailBridge-Version: melden und
   weiter.
2. **Revision aus dem Nav-Frame.** In `handleNavData()` einen Fall `NAV_TAG_OVERVIEW_REVISION`
   (0x0E) ergänzen. Fehlt der Tag in einem `NAV_UPDATE`, oder kommt `NAV_NONE`: Übersicht
   verwerfen. Ebenso in `onDisconnect()`.
3. **Lesen, wenn die Revision eine andere ist** als die der gespeicherten Übersicht.
   `handleNavData()` läuft im Indicate-Callback, dort blockiert `readValue()` den
   BLE-Stack. Im Callback also nur vormerken und aus einem eigenen Task lesen, z. B. aus
   `scanAndConnectTask()`, der auch `connectToServer()` ausführt.
4. **Frame parsen** (unten) und die `REVISION` aus dem Frame speichern, nicht die aus dem
   Nav-Frame. Passen die beiden beim nächsten Nav-Frame nicht zusammen, noch einmal lesen.
5. **Entfernung und Zeit bei jedem Nav-Frame neu rechnen** und anzeigen.

### Langer Wert

Die Übersicht ist bis 512 Byte lang, also länger als ein ATT-Paket. `readValue()` holt
sie trotzdem in einem Aufruf: Die BLE-Bibliothek von arduino-esp32 3.3 liest mit NimBLE
über `ble_gattc_read_long()`, mit Bluedroid hängt der Stack die Read-Blob-Antworten
selbst aneinander (bis 600 Byte). Geprüft im Quelltext, nicht am Gerät.

`setMTU(256)` reicht weiter aus. Mit der MTU 23 würde der Read nur länger dauern.

Der Parser sollte ein abgeschnittenes letztes TLV trotzdem aushalten (Länge über das
Frame-Ende hinaus: aufhören, das bis dahin Gelesene behalten).

### Parser

Wie die anderen: Byte 0 Version, Byte 1 Typ, dann TLVs; unbekannte Tags überspringen.

```cpp
// Skizze, nicht aus der Firmware
struct OverviewWaypoint { uint32_t remainingAtM, remainingTimeAtS; bool destination; char name[33]; };
struct OverviewClimb    { uint8_t number; uint16_t gainM; uint32_t lengthM, footRemainingAtM; };

case 0x01: revision = v[0]; break;                     // REVISION
case 0x02: waypointsAhead = v[0]; break;               // WAYPOINTS_AHEAD (ohne Ziel)
case 0x03: climbsTotal = v[0]; break;                  // CLIMBS_TOTAL
case 0x04: if (len >= 9) {                             // WAYPOINT
               wp.destination      = v[0] & 0x01;
               wp.remainingAtM     = le32(v + 1);
               wp.remainingTimeAtS = le32(v + 5);      // 0xFFFFFFFF: unbekannt
               copyName(wp.name, v + 9, len - 9);      // höchstens 32 Byte, UTF-8
           } break;
case 0x05: if (len >= 11) {                            // CLIMB, mehr als 11 Byte: Rest ignorieren
               cl.number = v[0]; cl.gainM = le16(v + 1); cl.lengthM = le32(v + 3);
               cl.footRemainingAtM = le32(v + 7);
           } break;
```

Der erste `WAYPOINT` ist immer das Ziel. Danach stehen Wegpunkte und Anstiege gemischt,
jeweils der nächste zuerst. In 512 Byte passen höchstens rund 40 Einträge; feste Felder
für 24 Wegpunkte und 24 Anstiege (zusammen gut 1 kB) reichen für die Anzeige, was darüber
hinausgeht, kann der Parser fallen lassen.

### Entfernung und Zeit

```cpp
int64_t  distM = (int64_t)navRemainingDistanceM - wp.remainingAtM;   // < 0: erreicht, nicht zeigen
uint32_t timeS;
if (wp.remainingTimeAtS != 0xFFFFFFFF)                               // Zeiten aus der GPX
    timeS = navRemainingTimeS > wp.remainingTimeAtS ? navRemainingTimeS - wp.remainingTimeAtS : 0;
else                                                                 // sonst anteilig nach Strecke
    timeS = navRemainingDistanceM == 0 ? 0
          : (uint32_t)((uint64_t)navRemainingTimeS * distM / navRemainingDistanceM);

int64_t footM   = (int64_t)navRemainingDistanceM - cl.footRemainingAtM;   // <= 0: schon im Anstieg
int64_t summitM = footM + cl.lengthM;                                      // bis zum Gipfel
```

`navRemainingDistanceM` und `navRemainingTimeS` sind `REMAINING_DISTANCE_M` (0x08) und
`REMAINING_TIME_S` (0x09) des letzten Nav-Frames. Die Ankunftszeit ist die Uhrzeit plus
`timeS`.

Welcher der beiden Zeit-Wege gilt, entscheidet die GPX-Datei: Hat sie Zeitstempel, kommen
Restzeit und Zeitanker daraus (die Schätzung des Routers oder eine aufgezeichnete Fahrt,
beide kennen die Anstiege). Ohne Zeitstempel rechnet TrailBridge mit der momentanen
Geschwindigkeit; vor einem langen Anstieg ist die Zeit dann zu optimistisch. Die Firmware
muss das nicht unterscheiden, nur dem Anker folgen.

Anstiege haben keinen Zeitanker.

## Wann sich die Übersicht ändert

| Ereignis | Folge |
|---|---|
| Route gestartet (auch abseits der Route) | erste Übersicht, neue Revision |
| Wegpunkt erreicht | er fällt weg, neue Revision |
| Gipfel erreicht | der Anstieg fällt weg, neue Revision |
| Sprung in der Testfahrt | neue Revision, falls danach etwas anderes voraus liegt |
| Route beendet | `OVERVIEW_NONE`, Nav-Frames ohne Revision |

Das sind auf einer Fahrt ein paar Dutzend Reads. Dazwischen ändern sich nur die
Entfernungen, und die kommen aus dem Nav-Frame.

Ist die Liste gekürzt (mehr als 512 Byte), zeigt `WAYPOINTS_AHEAD` mehr Wegpunkte an,
als im Frame stehen; bei den Anstiegen ist die letzte Nummer kleiner als `CLIMBS_TOTAL`.
Der Rest rückt mit den nächsten Revisionen nach.

## Testen

- **Ohne Firmware:** In TrailBridge eine GPX mit `wpt`-Elementen laden (die
  Zusammenfassung nennt die Zahl der erkannten Wegpunkte und, wenn die Datei Zeitstempel
  hat, die "Fahrzeit laut Datei") und die Testfahrt starten. In
  nRF Connect die Characteristic lesen; `adb logcat -s TrailBridge` zeigt jede neue
  Revision mit der Zahl der Einträge und der Länge.
- **Testvektor:** das Beispiel in PROTOCOL.md (78 Byte). Derselbe Frame steht in
  `OverviewFrameEncoderTest.encodesDocumentedLayout`.
- **Gekürzte Liste:** eine GPX mit mehr als 20 benannten Wegpunkten.
- **Beide Zeit-Wege:** eine GPX mit und eine ohne Zeitstempel. BRouter schreibt sie mit
  `profile:showtime=1` in der Anfrage bzw. `assign showtime = true` im Profil;
  `Tools/gpxenrich` lässt sie bisher weg.
- Am Gerät noch offen: ob der Long Read über die echte Verbindung so läuft wie im
  Quelltext gelesen.

## Doku im BikeComputer-Repo

Wenn die Firmware-Seite steht, stimmen dort diese Stellen nicht mehr:

- `doc/ROADMAP.md` und `doc/ROADMAP.de.md`: "Wegpunkte in der Navigation" und
  "Anstiegsübersicht für die ganze Route" nennen als Hindernis, dass TrailBridge keine
  `wpt` liest und das Protokoll keinen Tag hat. Beides gibt es jetzt. Von der
  Anstiegsübersicht fehlen weiter die noch zu fahrenden Höhenmeter.
- `doc/trailbridge/index.md` und `index.de.md`: die Liste "Was die App macht".
- `doc/trailbridge/PROTOCOL*.md` kommen über `doc/fetch_trailbridge.sh` von hier.
