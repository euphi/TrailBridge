# TrailBridge 0.5.1 -- Release Notes

*[English version below.](#trailbridge-051----release-notes-english)*

Gegenüber 0.5.0. Vorabversionen: `v0.5.1-rc1`, `v0.5.1-rc2`, `v0.5.1-rc3`, `v0.5.1-rc4`. Abruf, OsmAnd-Import und -Start sind am
Gerät ausprobiert; eine echte Fahrt mit den GPX-Funktionen der 0.5.0 steht weiter aus.

## Neu

- **Geplante Routen.** TrailBridge holt die Route, die im BikeLog-Dienst des
  BikeComputers für das Handy markiert ist, und lädt sie wie eine von Hand geöffnete GPX
  (nicht gestartet). Abgefragt wird beim Start des Dienstes und danach alle 15 Minuten --
  nur, solange keine Navigation und keine Testfahrt läuft, denn eine neue Route beendet
  beide. Eine Benachrichtigung meldet "Route ... geladen"; danach bestätigt TrailBridge dem
  Dienst den Empfang. Was nicht geladen oder bestätigt werden konnte, wird beim nächsten
  Intervall erneut versucht.
- **An OsmAnd senden.** Die geladene Route wird (abschaltbar) über OsmAnds AIDL-Schnittstelle
  in dessen Track-Liste gelegt und auf der Karte angezeigt.
- **"In OsmAnd starten".** Startet die geladene Route sofort als Navigation in OsmAnd, ohne
  Rückfrage; die ganze Spur vom Anfang, damit Rundstrecken nicht schon am Start als erreicht
  gelten. Eine eigene GPX-Navigation oder Testfahrt von TrailBridge endet dabei, damit die
  OsmAnd-Daten den BikeComputer erreichen.
- **Neue, einklappbare Karte "Geplante Routen"** im Hauptscreen: zugeklappt nur Status,
  "Prüfen" und "In OsmAnd starten"; aufgeklappt Adresse des Dienstes, Hilfe und der
  OsmAnd-Schalter.
- **GPX-Navigation zeigt "geradeaus" an** (rc2). Ein "geradeaus" aus der Datei wurde bisher
  verworfen; BRouter schreibt es aber nur dort, wo Geradeausfahren nicht offensichtlich ist
  (über eine Straße auf einen Weg, vor einer Abbiegung auf einen Parallelweg). Es bleibt jetzt
  erhalten, ein zweites innerhalb von 20 m (Fahrbahntrennung) fällt weg, und das "geradeaus" am
  Streckenanfang zählt nicht als Hinweis.
- **Energie sparen: TrailBridge beendet sich bei Leerlauf selbst** (rc3). Bisher forderte der
  Dienst das GPS jede Sekunde an, solange er lief, auch Stunden nach der Fahrt ohne BikeComputer.
  Jetzt läuft ein Timer, wenn nichts Sinnvolles passiert: kein BikeComputer verbunden; im
  Navi-Modus gilt echte Bewegung (GPS-Geschwindigkeit ab 1 m/s) als Aktivität, eine
  Testfahrt nur mit BikeComputer. Nach 30 Minuten (einstellbar: 15 Min., 1 Std., 2 Std. oder
  "Nie", Karte "Energie sparen") endet der Dienst: GPS aus, Benachrichtigung weg. Der Timer ist ein
  Alarm und läuft auch im Tiefschlaf des Handys (ungenau, einige Minuten Verzug sind möglich).
  Die App zu öffnen oder zu verlassen startet die Zeit neu; zum Weiterfahren die App öffnen.
- **Kürzere README** mit Verweis auf die Anleitung auf github.io; **englische Dokumentation:** README, BUILD, FDROID, ROADMAP, DESIGN, FIRMWARE-OVERVIEW,
  BROUTER-VOICEHINTS und LANE-COUNT-BUG gibt es jetzt auch als `*.en.md`.

## Behoben

- **Alter des GPS-Fixes beim Heartbeat** (rc4). PROTOCOL.md verspricht, `FIX_AGE_MS` werde bei
  jedem Senden neu berechnet; der Heartbeat schickte aber die Bytes des letzten Frames unverändert
  noch einmal. Fällt das GPS aus (Tunnel), sah der BikeComputer so einen ewig frischen Fix und
  bekam mit jedem Heartbeat eine falschere Zeit für seine Uhr angeboten. Jetzt wird der
  Positions-Frame bei Heartbeat, Abonnieren und Lesen aus dem letzten Fix neu codiert.
- **Fahrspur-Debug-Log** (rc4). `lane_debug.log` wuchs bei jeder Navigation um zwei Zeilen pro
  Sekunde, ohne Grenze, auch im Release-Build. Jetzt schreiben nur Debug-Builds, nur bei einer
  Änderung der Fahrspur-Daten, und die Datei beginnt bei 256 kB von vorn.
- **OsmAnd-Bindung** (rc4). Lieferte `bindService` zwar Erfolg, meldete sich OsmAnd aber nie,
  wurde die Bindung nie gelöst und "Erneut versuchen" band ein zweites Mal. Wird OsmAnd
  aktualisiert, bindet TrailBridge jetzt von selbst neu.
- **Leerlauf-Ende bei offener App** (rc4). Lief die Leerlaufzeit ab, während der Hauptscreen
  offen war, blieb die alte Position kommentarlos stehen, und "Route starten" lief ohne GPS. Jetzt
  steht "GPS: aus, lange nichts zu tun" im Status, und Route starten, Testfahrt oder ein
  verbundener BikeComputer holen GPS und Benachrichtigung zurück.

## Hinweise

- **Netz.** Das ist die erste Funktion, die das Netz nutzt (`INTERNET`). Sie ist aus, solange
  keine Adresse eingetragen ist; ohne sie verlässt nichts das Handy. Der Dienst gehört zum
  BikeComputer-Projekt und läuft beim Nutzer selbst.
- **`http://` im Klartext** ist erlaubt (`usesCleartextTraffic`), weil der Dienst typischerweise im
  eigenen Netz oder VPN steht. Im Internet bitte `https://`. Einen Token gibt es nicht; der
  Zugriff wird durch das Netz begrenzt. Antwortet der Dienst mit 401/403, meldet die App
  "Zugriff verweigert".
- Der Abruf prüft Größe (max. 20 MB) und SHA-256 der Datei und folgt keinen Weiterleitungen.
- Der 15-Minuten-Timer kann im Tiefschlaf des Handys stehen bleiben; beim nächsten Öffnen der
  App wird nachgeholt.
- Tastatur: Die Eingabefelder werden nicht mehr von der Tastatur verdeckt.

## Bekannte Lücken

- Noch keine echte Fahrt mit GPX-Navigation und Höhenprofil.
- Firmware-Seite der Streckenübersicht ist am Gerät ungetestet ([FIRMWARE-OVERVIEW.md](FIRMWARE-OVERVIEW.md)).
- Die BLE-Adresse von TrailBridge wird in der Firmware nicht fest gepinnt.

---

# TrailBridge 0.5.1 -- Release Notes (English)

Changes since 0.5.0. Pre-releases: `v0.5.1-rc1`, `v0.5.1-rc2`, `v0.5.1-rc3`, `v0.5.1-rc4`. Fetching, the OsmAnd import and start have
been tried on the device; a real ride with 0.5.0's GPX features is still to come.

## New

- **Planned routes.** TrailBridge fetches the route marked for the phone in the
  BikeComputer's BikeLog service and loads it like a GPX opened by hand (not started). It
  asks when the service starts and every 15 minutes after that -- only while no navigation
  and no test ride is running, because a new route ends both. A notification says "Route ...
  loaded"; then TrailBridge confirms receipt to the service. Whatever could not be loaded or
  confirmed is tried again at the next interval.
- **Send to OsmAnd.** The loaded route is (can be switched off) put into OsmAnd's track list
  through OsmAnd's AIDL interface and shown on the map.
- **"Start in OsmAnd".** Starts the loaded route right away as a navigation in OsmAnd,
  without asking; the whole track from its start, so that round trips do not count as
  reached at the start already. A GPX navigation or test ride of TrailBridge's own ends
  with it, so that the OsmAnd data reaches the BikeComputer.
- **New, collapsible "Planned routes" card** on the main screen: collapsed only status,
  "Check" and "Start in OsmAnd"; opened the service address, help and the OsmAnd switch.
- **GPX navigation shows "straight on"** (rc2). A "straight on" from the file used to be
  dropped; BRouter writes it only where going straight is not obvious (across a road onto a
  track, before a turn onto a parallel way). It is now kept, a second one within 20 m (dual
  carriageway) is dropped, and the "straight on" at the start of the route does not count as a
  hint.
- **Energy saving: TrailBridge ends itself when idle** (rc3). Until now the service asked for the
  GPS every second for as long as it ran, even hours after the ride with no BikeComputer
  connected. Now a timer runs when nothing useful happens: no BikeComputer connected; in navi
  mode real movement (GPS speed from 1 m/s) counts as activity, a test ride only with a
  BikeComputer. After 30 minutes (adjustable: 15 min, 1 h, 2 h or "Never", card "Energy
  saving") the service ends: GPS off, notification gone. The timer is an alarm and also runs
  in the phone's deep sleep (inexact, a few minutes' delay are possible). Opening or leaving
  the app starts the time over; open the app to carry on.
- **Shorter README** pointing to the manual on github.io; **English documentation:** README, BUILD, FDROID, ROADMAP, DESIGN, FIRMWARE-OVERVIEW,
  BROUTER-VOICEHINTS and LANE-COUNT-BUG are now also available as `*.en.md`.

## Fixed

- **Age of the GPS fix on the heartbeat** (rc4). PROTOCOL.md promises that `FIX_AGE_MS` is
  computed afresh on every send; the heartbeat, however, repeated the bytes of the last frame as
  they were. When the GPS dropped out (tunnel), the BikeComputer saw a fix that stayed fresh for
  ever and was offered an ever more wrong time for its clock with each heartbeat. The position
  frame is now re-encoded from the last fix on heartbeat, subscription and read.
- **Lane debug log** (rc4). `lane_debug.log` grew by two lines a second during every
  navigation, without limit, in release builds too. Now only debug builds write it, only when the
  lane data changes, and the file starts over at 256 kB.
- **OsmAnd binding** (rc4). When `bindService` reported success but OsmAnd never answered, the
  binding was never released and "Retry" bound a second time. When OsmAnd is updated, TrailBridge
  now rebinds by itself.
- **Idle stop with the app open** (rc4). When the idle time ran out while the main screen was
  open, the old position stayed on without a word, and "Start route" ran without GPS. Now the status
  reads "GPS: off, nothing to do for a while", and starting a route, a test ride or a connecting
  BikeComputer bring GPS and the notification back.

## Notes

- **Network.** This is the first function that uses the network (`INTERNET`). It is off as
  long as no address is entered; without one nothing leaves the phone. The service belongs to
  the BikeComputer project and is run by the user themselves.
- **Cleartext `http://`** is allowed (`usesCleartextTraffic`) because the service typically
  sits in your own network or VPN. On the internet please use `https://`. There is no token;
  access is limited by the network. If the service answers 401/403, the app reports "Access
  denied".
- Fetching checks the size (max. 20 MB) and SHA-256 of the file and follows no redirects.
- The 15-minute timer can stop while the phone is in deep sleep; it catches up the next time
  the app is opened.
- Keyboard: the input fields are no longer covered by the keyboard.

## Known gaps

- No real ride with GPX navigation and elevation profile yet.
- Firmware side of the route overview is untested on the device ([FIRMWARE-OVERVIEW.md](FIRMWARE-OVERVIEW.md)).
- TrailBridge's BLE address is not pinned in the firmware.
