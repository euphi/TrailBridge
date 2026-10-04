# BRouter / bikerouter.de: warum eine GPX keine Abbiegehinweise hat

Stand 2026-10-04, Testfahrt Fürth (`Fürth - 44.1 km, 225 hm.gpx`, Profil `Trekking-tracks`;
vorher gleiches Problem mit dem bikerouter-Profil „Gravel (schnell)"). **Offen:** der Profiltext
der beiden Profile -- ohne den lässt sich die Ursache nicht eingrenzen (siehe unten).

## Was die Datei enthält

`creator="OsmAndRouter"` (= Hinweis-Modus 3 angefordert), 7 `wpt` (from/via/to), ein `rte` mit
nur **zwei** `rtept` (`start`, `destination`), 1188 `trkpt`. Kein `<turn>`, keine Kommentare:
BRouter hat die Hinweisliste **leer** geliefert. TrailBridge (bis 2026-10-04) zählte das `rte`
trotzdem als Routing-Info und zeigte nur die Distanz; jetzt fällt der Parser auf die Geometrie
zurück (`GpxParser`, 66 Schritte für diese Datei). Die Hinweise sind dann aber geometrisch
(Winkel), nicht die des Routers (Straßennamen, Kreisverkehr-Ausfahrt) -- schlechter als nötig.

## Was sich nachstellen ließ (gegen brouter.de, 2026-10-04)

| Versuch | Ergebnis |
|---|---|
| Standardprofile `trekking`, `gravel` (auch `quaelnix-gravel`, `fastbike`, `mtb`) mit der Original-Route (7 Punkte), `turnInstructionMode=3` | 93 bzw. 136 `<turn>` |
| `profile=Trekking-tracks` | HTTP 500: **kein Serverprofil**, sondern ein eigenes (im Browser gespeichertes) Profil |
| eigenes Profil = unveränderte Kopie von `gravel.brf` (POST `/brouter/profile`) | 136 `<turn>`, also kein Problem des Uploads oder der Via-Punkte |
| eigenes Profil **ohne** `assign turnInstructionMode` oder mit `0` | **0** Hinweise, aber `creator="BRouter-..."` und **kein** `rte` -- sieht anders aus als die Fürth-Datei |
| eigenes Profil mit fest `turnInstructionMode 3`; ohne `priorityclassifier`; ohne `turncost`; `pass1/2coefficient 0`; `add_beeline`; `validForBikes 0`; `turnInstructionCatchingRange` riesig; Rundungs-Hinweise aus | weiterhin Hinweise |

BRouter-Quelltext (`RoutingContext`, `OsmTrack.processVoiceHints`): Das Profil-Variable
`turnInstructionMode` gewinnt gegen den Request-Parameter, außer sie ist `1`. Hinweise entstehen
nur, wenn beim Routing Umwege ("detours") am Leitstrecke-Track registriert wurden
(`detourMap != null`) -- das passiert nur bei `turnInstructionMode > 0`.

**Nicht reproduziert:** `OsmAndRouter` + `rte` mit nur start/destination. Das muss aus dem
Profiltext kommen. Weiter: In bikerouter das Profil öffnen (Profil bearbeiten → Text kopieren),
als `gravel_schnell.brf` ablegen und so prüfen:

```sh
ID=$(curl -s -X POST --data-binary @gravel_schnell.brf https://brouter.de/brouter/profile | python3 -c 'import sys,json;print(json.load(sys.stdin)["profileid"])')
curl -s "https://brouter.de/brouter?lonlats=10.981473,49.472323|10.9500,49.4500&profile=$ID&alternativeidx=0&format=gpx&turnInstructionMode=3" | grep -c '<turn>'
```

Dann Zeile für Zeile bisecten (Zeilen auf Standardwerte von `gravel.brf` zurücksetzen).
