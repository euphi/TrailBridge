# Streckenübersicht: die Seite des BikeComputers

Referenz für die Firmware-Seite der Streckenübersicht. Entstanden als Vorlage, inzwischen
ist sie umgesetzt: im Repo `TRGB-BikeComputer` auf `main` (Commits `43ecfa1` Code,
`2374ffe` Doku), noch in keinem Firmware-Release und am Gerät ungetestet. Die App-Seite ist in TrailBridge 0.5.0. Das Wire-Format
steht verbindlich in [PROTOCOL.md](PROTOCOL.md), Abschnitt "Streckenübersicht-Service"
-- hier steht, was daraus für die Firmware folgt und wie sie es macht. Was der Fahrer
davon sieht, beschreibt `doc/ROUTE.md` (deutsch `ROUTE.de.md`) im BikeComputer-Repo.

## Was ankommt

Solange TrailBridge eine GPX-Route abspielt (echt oder als Testfahrt):

- In jedem Nav-Frame ein neuer Tag `OVERVIEW_REVISION` (0x0E, 1 Byte). Eine
  Firmware ohne Streckenübersicht überspringt ihn als unbekannten Tag.
- Ein vierter Service mit einer Characteristic, die sich nur lesen lässt. Ihr Wert ist
  die Übersicht: das Ziel, die Wegpunkte voraus (Name, Restdistanz und Restzeit der
  Route am Wegpunkt) und die Anstiege voraus (Nummer, Höhenmeter, Länge, Restdistanz
  am Fuß).

| | UUID |
|---|---|
| Service | `7ee954a6-7a19-4b48-b052-f00d00e01e58` |
| Characteristic (`READ`) | `f031faa3-083d-4818-9eca-38420be835d0` |

Bei Navigation aus OsmAnd fehlt der Tag, und die Characteristic liefert `OVERVIEW_NONE`.

## Was die Firmware macht

1. **Characteristic merken.** Nach dem Connect sucht sie den Service, ohne sich
   anzumelden -- es gibt kein CCCD. Fehlt der Service, ist es eine ältere
   TrailBridge-Version.
2. **Revision aus dem Nav-Frame.** `handleNavData()` liest `OVERVIEW_REVISION` (0x0E).
   Fehlt der Tag in einem Nav-Frame, kommt `NAV_NONE` oder reißt die Verbindung ab,
   verwirft sie die Übersicht.
3. **Lesen, wenn die Revision eine andere ist** als die der gehaltenen Übersicht.
   `handleNavData()` läuft im Indicate-Callback, dort würde `readValue()` den BLE-Stack
   blockieren. Der Callback merkt den Read also nur vor, gelesen wird in einem eigenen
   Task (`BLEDevices::overviewReaderTask()`). Schlägt der Read fehl, folgt nach 4 s ein
   neuer Versuch.
4. **Frame parsen** (unten, `src/RouteOverview.*`) und die `REVISION` aus dem Frame
   halten, nicht die aus dem Nav-Frame. Passen die beiden beim nächsten Nav-Frame nicht
   zusammen, wird noch einmal gelesen.
5. **Entfernung und Zeit bei jedem Nav-Frame neu rechnen** (`src/RouteMonitor.*`).
   Zwischen zwei Frames führt der Radsensor die Entfernung weiter; die Zeit bleibt die
   des letzten Frames.
6. **Anzeigen** auf dem Screen `RimRidgeRoute` (vom Nav-Screen nach links wischen): das
   Ziel mit Ankunftszeit, darunter Wegpunkte (Name, Entfernung, Ankunft) und Anstiege
   (Anstieg n/N, Entfernung zum Fuß oder "noch x km" im Anstieg, Höhenmeter, Länge,
   mittlere Steigung), jeweils der nächste zuerst.

### Langer Wert

Die Übersicht ist bis 512 Byte lang, also länger als ein ATT-Paket. `readValue()` holt
sie trotzdem in einem Aufruf: Die BLE-Bibliothek von arduino-esp32 3.3 liest mit NimBLE
über `ble_gattc_read_long()`, mit Bluedroid hängt der Stack die Read-Blob-Antworten
selbst aneinander (bis 600 Byte). Geprüft im Quelltext, nicht am Gerät.

`setMTU(256)` reicht weiter aus. Mit der MTU 23 würde der Read nur länger dauern.

Ein abgeschnittenes letztes TLV muss der Parser aushalten (Länge über das Frame-Ende
hinaus: aufhören, das bis dahin Gelesene behalten).

### Parser

Wie die anderen: Byte 0 Version, Byte 1 Typ, dann TLVs; unbekannte Tags überspringen.

```cpp
// Skizze aus der Vorlage, nicht der Code der Firmware
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
jeweils der nächste zuerst. In 512 Byte passen höchstens rund 40 Einträge; so viele hält
die Firmware, 24 davon zeigt sie an.

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
Der Rest rückt mit den nächsten Revisionen nach. Die Firmware zeigt dann in der Fußzeile
"Liste gekürzt".

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
- Am Gerät noch offen: die ganze Firmware-Seite, vor allem ob der Long Read über die
  echte Verbindung so läuft wie im Quelltext gelesen.

## Noch offen

- Die noch zu fahrenden Höhenmeter -- braucht eine Erweiterung des Protokolls
  (siehe [ROADMAP.md](ROADMAP.md), "Anstiegsübersicht, der Rest").
- Ein Hinweis vor einem Wegpunkt (Pop-up) und der Zugang zur Übersicht vom Hauptscreen.

## Doku im BikeComputer-Repo

Nachgezogen (Commit `2374ffe`): `doc/ROUTE.md` und `ROUTE.de.md`, die
Roadmap und die TrailBridge-Seite. `doc/trailbridge/PROTOCOL*.md` kommen weiter über
`doc/fetch_trailbridge.sh` von hier.
