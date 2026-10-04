# BRouter / bikerouter.de: warum eine GPX keine Abbiegehinweise hat

Stand 2026-10-04, Testfahrt Fürth (`Fürth - 44.1 km, 225 hm.gpx`; die Profil-ID `Trekking-tracks`
heißt in der bikerouter-Oberfläche „Gravel (schnell)"; vorher gleiches Problem mit anderen Profilen).

## Ursache (gefunden)

Es liegt **nicht am Profil**, sondern an einem Parameter der Routenberechnung. bikerouter.de baut
die GPX **im Browser** aus der bereits berechneten Route (GeoJSON, Klasse `Hs` in seinem Skript:
`creator="OsmAndRouter"`, `rte`/`rtept` aus `track.properties.voicehints`). Enthält diese Route
keine `voicehints`, bleiben vom `rte` nur `start` und `destination` übrig -- genau der Inhalt der
Fürth-Datei. Der Router rechnet die Hinweise nur, wenn **`turnInstructionMode` ≠ 0 und ≠ 1
(„auto") ist, als Profil-Parameter**: alle Profile deklarieren `assign turnInstructionMode = 1
# %turnInstructionMode% …`, und bei „auto" bekommt die GeoJSON-Anfrage der Oberfläche keine Hinweise.

Gemessen gegen `https://bikerouter.de/brouter-engine/brouter` (GeoJSON, Route Fürth, Profil
`Trekking-tracks`; ebenso `trekking`, `gravel`, `fastbike`, `quaelnix-gravel`, `m11n-gravel`, `MTB`):

| Anfrage | `voicehints` im GeoJSON |
|---|---|
| ohne Angabe / `turnInstructionMode=0` / `profile:turnInstructionMode=0` / `=1` | **fehlen** |
| `profile:turnInstructionMode=3` (OsmAnd) | **113** (Fürth), bei den kurzen Teststrecken 19-40 |

Dieselbe Route mit `format=gpx&turnInstructionMode=3` direkt vom Server hat 113 `<turn>`; die
Kopfzeile (44110 m, cost 103883, 2 h 14 m 52 s) ist identisch mit der der Fürth-Datei. Der Router
kann es also; die Datei wurde nur ohne Hinweise exportiert.

## Was zu tun ist

In bikerouter **vor dem Berechnen** (bzw. vor dem Export) beim Profil den Parameter
`turnInstructionMode` auf „osmand-style" (3) stellen -- in den Profil-Einstellungen, die aus den
`%…%`-Kommentaren der Profildatei entstehen --, die Route neu berechnen lassen und dann als GPX
exportieren. In der Adresszeile steht es dann als `profile:turnInstructionMode=3`. Prüfen:
Die exportierte Datei hat viele `<rtept>` mit `<turn>`.

Nicht bewiesen ist, wie die Oberfläche diese Einstellung nennt und wo sie sitzt (ich habe die
Seite nicht bedient, nur ihr Skript und den Server gelesen).

## Was TrailBridge dagegen tut

Fehlen die Hinweise (nur `start`/`destination` im `rte`, keine OsmAnd-Segmente), leitet der
Parser sie aus der Track-Geometrie ab (`GpxParser`; 66 Schritte für die Fürth-Datei). Das sind
Winkel-Hinweise ohne Straßennamen und Kreisverkehr-Ausfahrten -- brauchbar, aber schlechter als
die des Routers. TrailBridge zeigt beim Laden, ob die Hinweise „aus der Datei" oder „aus der
Track-Geometrie" stammen.

## Nachstellen

```sh
LL="10.981473,49.472323|10.763898,49.431686|10.735595,49.442334|10.829473,49.452626|10.844944,49.460391|10.850388,49.459014|10.981318,49.472368"
for x in "" "&profile:turnInstructionMode=3"; do
  curl -sL "https://bikerouter.de/brouter-engine/brouter?lonlats=$LL&profile=Trekking-tracks&alternativeidx=0&format=geojson$x" \
   | python3 -c 'import sys,json;p=json.load(sys.stdin)["features"][0]["properties"];print(len(p.get("voicehints",[])))'
done   # 0, dann 113
```
(Profiltexte: `https://bikerouter.de/profiles/<id>.brf`; `Trekking-tracks` hat außerdem `pass2coefficient 0`,
das hat mit den Hinweisen aber nichts zu tun -- mit `turnInstructionMode=3` kommen sie trotzdem.)
