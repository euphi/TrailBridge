# TrailBridge 0.5.1 -- Release Notes

*[English version below.](#trailbridge-051----release-notes-english)*

Gegenüber 0.5.0. Vorabversionen: `v0.5.1-rc1`, `v0.5.1-rc2`. Abruf, OsmAnd-Import und -Start sind am
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
- **Kürzere README** mit Verweis auf die Anleitung auf github.io; **englische Dokumentation:** README, BUILD, FDROID, ROADMAP, DESIGN, FIRMWARE-OVERVIEW,
  BROUTER-VOICEHINTS und LANE-COUNT-BUG gibt es jetzt auch als `*.en.md`.

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

Changes since 0.5.0. Pre-releases: `v0.5.1-rc1`, `v0.5.1-rc2`. Fetching, the OsmAnd import and start have
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
- **Shorter README** pointing to the manual on github.io; **English documentation:** README, BUILD, FDROID, ROADMAP, DESIGN, FIRMWARE-OVERVIEW,
  BROUTER-VOICEHINTS and LANE-COUNT-BUG are now also available as `*.en.md`.

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
