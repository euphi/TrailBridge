# Roadmap

Was an TrailBridge offen ist, grob nach Priorität. Was die App schon kann, steht in
der [README](README.md). Vieles hier braucht auch eine Änderung am BikeComputer; dessen
offene Punkte stehen in der
[Roadmap des BikeComputers](https://euphi.github.io/TRGB-BikeComputer/de/ROADMAP/).

"Braucht Protokoll und Firmware" heißt: [PROTOCOL.md](PROTOCOL.md) wird erweitert, und
die Firmware muss den neuen Teil auswerten. Beides nur gemeinsam ändern.

## Stand

| Bereich | Stand |
|---|---|
| Navigation aus OsmAnd (Manöver, Straßennamen, Fahrspuren, Restdistanz und -zeit) | gefahren, in v0.4.4 |
| GPS-Position und Uhrzeit für den BikeComputer | in v0.4.4; das Stellen der Uhr wurde nicht einzeln geprüft |
| GPX-Navigation, Höhenprofil der Anstiege, Testfahrt, Navi-Modus, Moduswahl | in der Vorabversion v0.5.0-rc1; nur mit der Testfahrt und dem Simulator-Build der Firmware getestet, noch keine echte Fahrt |
| Höhenprofil der Strecke voraus als rollendes Fenster (auch flach und bergab), Anstieg darin mit ganzer Ausdehnung angesagt | auf `main`, noch in keinem Release |
| Höhe über dem Meeresspiegel im Positions-Frame (`MSL_ALTITUDE_DM`, Android 14+) | auf `main`, noch in keinem Release |
| Streckenübersicht: Ziel, Wegpunkte (`wpt`) und Anstiege der GPX-Route zum Lesen | App-Seite auf `main`, noch in keinem Release, nur mit Unit-Tests geprüft; die Firmware wertet sie noch nicht aus, siehe [FIRMWARE-OVERVIEW.md](FIRMWARE-OVERVIEW.md) |
| F-Droid | im Repo vorbereitet, siehe [FDROID.md](FDROID.md) |

## Bekannte Fehler

- **Zahl der Fahrspuren:** An einer Kreuzung zeigt OsmAnd zwei Spuren, über BLE kommt
  nur eine an. Die Analyse ist offen, siehe [LANE-COUNT-BUG.md](LANE-COUNT-BUG.md).
- **Entfernung zur Spurwahl:** `LANE_DISTANCE_M` ist immer gleich der Entfernung zur
  Abbiegung, weil OsmAnd den früheren Punkt nicht liefert (siehe "Meldungen an OsmAnd").

## Geplant

Noch nicht entworfen.

- **Kopplung mit dem BikeComputer:** Die BLE-Services lassen sich ohne Kopplung und
  Verschlüsselung lesen; jedes BLE-Gerät in Reichweite kann sich verbinden und die
  Position mitlesen. Mit Bonding wäre die Verbindung verschlüsselt, und der
  BikeComputer könnte sein Handy trotz der wechselnden BLE-Adresse wiedererkennen.
  Braucht Protokoll und Firmware.
- **Anstiegsübersicht, der Rest:** Die Streckenübersicht nennt je Anstieg Nummer,
  Höhenmeter, Länge und die Stelle des Fußes. Offen sind die noch zu fahrenden
  Höhenmeter. Braucht Protokoll und Firmware.
- **Restzeit an den Fahrer anpassen:** Hat die GPX Zeitstempel, kommt die Restzeit zu
  Ziel und Wegpunkten daraus -- so schnell, wie der Router oder die Aufzeichnung es
  vorgibt, nicht wie der Fahrer wirklich fährt. Ohne Zeitstempel rechnet TrailBridge mit
  der momentanen Geschwindigkeit; vor einem langen Anstieg ist das zu optimistisch.
- **Zeitstempel aus BRouter:** BRouter schreibt seine Zeitschätzung nur mit
  `showtime` im Profil in die GPX, im normalen Export fehlt sie. `Tools/gpxenrich` im
  BikeComputer-Repo lässt Zeitstempel weg.
- **Streckenübersicht in der App anzeigen:** Heute sieht man sie nur am BikeComputer
  (und im Log).
- **Richtung zurück zur Route:** Abseits der Route sendet TrailBridge nur die
  Entfernung zu ihr. Gewünscht: auch die Peilung, damit der BikeComputer einen Pfeil
  zeigen kann. Braucht Protokoll und Firmware.
- **Englische Übersetzung:** Die Texte der App gibt es nur auf Deutsch, auch "Abseits
  der Route", das als Straßenname an den BikeComputer geht.
- **Zeitraffer für die Testfahrt:** Sie läuft nur in Echtzeit; einen langen Anstieg zu
  testen dauert deshalb Stunden.

## Später

- **Streckenlinie:** den Verlauf der GPX-Route um die aktuelle Position senden, damit
  der BikeComputer Abbiegungen und den Weg zurück zeichnen kann. Braucht einen neuen
  BLE-Service für die Geometrie.
- **Rückkanal vom BikeComputer:** Heute gibt es keine schreibbare Characteristic.
  Damit gingen Tasten am BikeComputer für die App, Fahrtdaten aufs Handy und
  WLAN-Einstellungen vom Handy.
- **Echte Sensorwerte weiterreichen** (z. B. ein Pulsgurt am Handy). Die Tags sind im
  Protokoll reserviert; der BikeComputer koppelt seine Sensoren aber selbst, der Nutzen
  ist deshalb klein.

## Meldungen an OsmAnd

Noch einzureichen, bei [osmandapp/OsmAnd](https://github.com/osmandapp/OsmAnd/issues).

- **Fehler:** Die `no_speak_next_`-Daten der AIDL-Schnittstelle wiederholen die
  übernächste Abbiegung statt des früheren Punkts für die Spurwahl. Details in
  PROTOCOL.md, "Fahrspur-Informationen".
- **Wunsch:** nächster Wegpunkt mit Name, Entfernung und Ankunftszeit.
- **Wunsch:** Höhenprofil der Route (heute nur Summenwerte).
- **Wunsch:** Spur-Informationen auch für Fahrradspuren.

## Auf der langen Bank

- **Höhenprofil und Streckenlinie bei OsmAnd-Navigation** (heute nur mit einer
  GPX-Route). Die AIDL-Schnittstelle liefert weder die Routengeometrie noch Höhen; das
  braucht eine Erweiterung in OsmAnd selbst und in der Schnittstelle.
- **TrailBridge beim Start des Handys automatisch starten.** Im Moment kein Bedarf.
