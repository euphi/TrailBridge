# TrailBridge 0.5.0 -- Release Notes

Gegenüber 0.4.4. Vorabversionen: `v0.5.0-rc1`, `v0.5.0-rc2`. Die neuen Funktionen
sind bisher nur mit Testfahrt, Unit-Tests und dem Simulator-Build der Firmware
geprüft, noch nicht auf einer echten Fahrt.

## Neu

- **GPX-Routen mit eigener Navigation.** Eine GPX-Datei laden (Öffnen mit,
  Teilen oder Dateiauswahl) und TrailBridge navigiert selbst: Abbiegehinweise
  aus der Datei oder aus der Track-Geometrie, Position vom echten GPS. OsmAnd
  wird dafür nicht gebraucht.
- **Höhenprofil als dritter BLE-Service.** Der BikeComputer bekommt immer ein
  rollendes Profil der Strecke voraus; ein Anstieg darin wird mit ganzer
  Ausdehnung angesagt.
- **Streckenübersicht als vierter BLE-Service** (nur lesbar): Ziel, Wegpunkte
  der GPX und Anstiege voraus; die Restzeit kommt aus den Zeitstempeln der GPX,
  sonst aus der aktuellen Geschwindigkeit. Die Firmware-Seite fehlt noch.
- **Testfahrt.** Spielt die GPX als Fake-Position samt simulierten Sensorwerten
  (Geschwindigkeit, Puls, Trittfrequenz, Höhe, Leistung) ab, immer als
  "simuliert" gekennzeichnet. Dazu fünf optionale Tags im Positions-Service
  (PROTOCOL.md "Sensorwerte und Simulationsmodus").
- **Neues Aussehen** im Rim-&-Ridge-Design des BikeComputers, **Navi-Modus**
  (nur Navigation, Position und gesendetes Höhenprofil) und **Moduswahl**
  (OsmAnd, GPS/GPX-Navigation, GPX-Simulation, All-in-one).
- **Deutsch und Englisch.** Englisch ist die Standardsprache, Deutsch wird
  verwendet, wenn das Handy auf Deutsch steht. Auch die Texte, die auf dem
  BikeComputer erscheinen ("Ziel", "Abseits der Route"), folgen der Sprache.
- **Englische Protokolldokumentation** ([PROTOCOL.en.md](PROTOCOL.en.md)).

## Geändert

- **Berechtigungen:** Zum Start sind nur noch die Bluetooth-Berechtigungen
  nötig. Standort (nur für GPS und GPX-Navigation) und Benachrichtigungen sind
  optional; ohne sie läuft die OsmAnd-Weitergabe weiter. Der GPS-Teil startet,
  sobald der Standort erteilt ist. Sind Benachrichtigungen aus, weist die App
  darauf hin, dass der Dienst trotzdem im Hintergrund läuft.
- **UTC-Zeit:** `UTC_TIME_MS` wird nur gesendet, wenn die GPS-Zeit zur
  Handy-Uhr passt (±5 s), sonst geht die Handy-Zeit raus -- die ersten Fixes
  nach dem Einschalten des GPS-Chips können Stunden danebenliegen.
- **GPX-Import:** Eine `rte` nur mit Start, Via und Ziel gilt nicht als
  Routing-Info; ein namenloses "geradeaus" aus `rtept` wird nicht als Manöver
  übernommen.
- **Schriften:** unveränderte Upstream-TTFs, OFL-Lizenztexte liegen bei.

## Dokumentation

- [ROADMAP.md](ROADMAP.md): eigene Roadmap für TrailBridge.
- [BROUTER-VOICEHINTS.md](BROUTER-VOICEHINTS.md): Analyse, warum bikerouter-GPX
  teils keine Abbiegehinweise enthält (`turnInstructionMode` im GeoJSON-Export).

## Bekannte Lücken

- Noch keine echte Fahrt mit GPX-Navigation und Höhenprofil.
- Firmware-Seite der Streckenübersicht fehlt (Vorlage:
  [FIRMWARE-OVERVIEW.md](FIRMWARE-OVERVIEW.md)).
- Die BLE-Adresse von TrailBridge wird in der Firmware nicht fest gepinnt.

---

# TrailBridge 0.5.0 -- Release Notes (English)

Changes since 0.4.4. Pre-releases: `v0.5.0-rc1`, `v0.5.0-rc2`. The new features
have so far only been checked with the test ride, unit tests and the firmware's
simulator build, not on a real ride yet.

## New

- **GPX routes with own navigation.** Load a GPX file (open with, share or file
  picker) and TrailBridge navigates by itself: turn instructions from the file
  or from the track geometry, position from the real GPS. OsmAnd is not needed.
- **Elevation profile as a third BLE service.** The BikeComputer always gets a
  rolling profile of the route ahead; a climb in it is announced in full.
- **Route overview as a fourth BLE service** (read-only): destination, the
  GPX's waypoints and climbs ahead; the remaining time comes from the GPX time
  stamps, else from the current speed. The firmware side is still missing.
- **Test ride.** Plays the GPX back as a fake position with simulated sensor
  values (speed, heart rate, cadence, elevation, power), always flagged as
  simulated. Five optional tags were added to the position service
  (PROTOCOL.md "Sensorwerte und Simulationsmodus").
- **New look** in the BikeComputer's Rim & Ridge design, **navigation mode**
  (navigation, position and the transmitted elevation profile only) and **mode
  selection** (OsmAnd, GPS/GPX navigation, GPX simulation, all-in-one).
- **German and English.** English is the default, German is used when the phone
  is set to German. The texts shown on the BikeComputer ("Destination", "Off
  route") follow the language too.
- **English protocol documentation** ([PROTOCOL.en.md](PROTOCOL.en.md)).

## Changed

- **Permissions:** only the Bluetooth permissions are needed to start. Location
  (for GPS and GPX navigation only) and notifications are optional; without
  them the OsmAnd relay keeps running. The GPS part starts as soon as location
  is granted. With notifications off, the app points out that the service
  still runs in the background.
- **UTC time:** `UTC_TIME_MS` is only sent when the GPS time matches the phone
  clock (±5 s), else the phone's time goes out -- the first fixes after the GPS
  chip is switched on can be hours off.
- **GPX import:** an `rte` with only start, via and destination does not count
  as routing info; a nameless "straight on" from `rtept` is not taken over as a
  maneuver.
- **Fonts:** unmodified upstream TTFs, OFL licence texts included.

## Documentation

- [ROADMAP.md](ROADMAP.md): TrailBridge's own roadmap.
- [BROUTER-VOICEHINTS.md](BROUTER-VOICEHINTS.md): analysis of why bikerouter
  GPX sometimes lacks turn instructions (`turnInstructionMode` in the GeoJSON
  export).

## Known gaps

- No real ride with GPX navigation and elevation profile yet.
- Firmware side of the route overview is missing (template:
  [FIRMWARE-OVERVIEW.md](FIRMWARE-OVERVIEW.md)).
- TrailBridge's BLE address is not pinned in the firmware.
