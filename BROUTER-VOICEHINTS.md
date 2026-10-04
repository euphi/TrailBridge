# BRouter / bikerouter.de: warum eine GPX keine Abbiegehinweise hat

Stand 2026-10-04, Testfahrt Fürth (`Fürth - 44.1 km, 225 hm.gpx`; die Profil-ID `Trekking-tracks`
heißt in der bikerouter-Oberfläche „Gravel (schnell)" und beruht auf Poutniks Vorlage „Trekking in
Tracks mode"; vorher gleiches Problem mit anderen Profilen). **Ursache geklärt, korrigiertes
Profil:** [`brouter/gravel-schnell.brf`](brouter/gravel-schnell.brf).

## Kurzfassung

Die Route selbst ist in Ordnung, es fehlt nur der Hinweis-Modus. Das Profil hat
`assign turnInstructionMode = 1` (*auto-choose*). Mit 1 übernimmt der BRouter-Server den Modus
**nicht** aus dem Profil, sondern nur aus der Anfrage -- und die GeoJSON-Anfrage von bikerouter.de
bringt keinen mit. Der Server rechnet also mit Modus 0: keine Hinweise. Der GPX-Export entsteht
aber im Browser und baut trotzdem OsmAnd-Format -- mit leerer Hinweisliste.

Abhilfe: Modus 3 (osmand-style), entweder fest im Profiltext (`brouter/gravel-schnell.brf`) oder
vor dem Berechnen in den Profil-Optionen. Das „funktionierende" kopierte Profil unterschied sich
darum nicht nur in der Optionsliste (`4=comment-style, ...`), sondern im **Wert**: dort `= 3`, im
Original `= 1`. Die zusätzlichen Listeneinträge sind nur Auswahlpunkte im Formular.

## Gemessen gegen bikerouter.de

Gegen `https://bikerouter.de/brouter-engine/brouter` (GeoJSON, Route Fürth, Profil
`Trekking-tracks`; ebenso `trekking`, `gravel`, `fastbike`, `quaelnix-gravel`, `m11n-gravel`, `MTB`):

| Anfrage | `voicehints` im GeoJSON |
|---|---|
| ohne Angabe / `turnInstructionMode=0` / `profile:turnInstructionMode=0` / `=1` | **fehlen** |
| `profile:turnInstructionMode=3` (OsmAnd) | **113** (Fürth), bei den kurzen Teststrecken 19-40 |

Dieselbe Route mit `format=gpx&turnInstructionMode=3` direkt vom Server hat 113 `<turn>`; die
Kopfzeile (44110 m, cost 103883, 2 h 14 m 52 s) ist identisch mit der der Fürth-Datei. Der Router
kann es also; die Datei wurde nur ohne Hinweise exportiert.

## Die Kette im Quelltext (abrensch/brouter, nrenner/brouter-web)

1. **Profilwert:** `RoutingContext.readGlobalConfig()`
   ```java
   int tiMode = (int) expctxGlobal.getVariableValue("turnInstructionMode", 0.f);
   if (tiMode != 1) { // automatic selection from coordinate source
     turnInstructionMode = tiMode;
   }
   ```
   Bei 1 bleibt der Wert aus der Anfrage stehen (`RoutingParamCollector`: `turnInstructionMode`,
   `timode`, `turnInstructionFormat`), sonst der Feld-Default **0**. „Auto" ist für die BRouter-App
   gedacht, die den Modus vom aufrufenden Programm (OsmAnd, Locus) kennt.
2. **`profile:`-Parameter:** `profile:<name>=<wert>` in der Anfrage wird vor dem Profiltext als
   Zuweisung eingefügt und gewinnt gegen dessen erste `assign`-Zeile (`BExpression.parse`,
   `doNotChange`). So kommt `profile:turnInstructionMode=3` oben zu seinen Hinweisen. Die
   Oberfläche nutzt das für die Optionen **nicht** (siehe nächster Abschnitt).
3. **Routing:** Hinweise entstehen nur aus beim Routing registrierten Abzweigen (`RoutingEngine`:
   `if (routingContext.turnInstructionMode > 0) guideTrack.registerDetourForId(...)`);
   `OsmTrack.processVoiceHints()` bricht ohne sie ab, `FormatJson` lässt `voicehints` dann weg.
4. **Export im Browser:** brouter-web `Export._formatTrack()` → `BR.Gpx.format()` mit
   `OsmAndVoiceHints` (bei bikerouter minifiziert, Klasse `Hs`): `creator="OsmAndRouter"` und ein
   `rte`, das **immer** `start` und `destination` enthält, dazwischen die `voicehints` aus dem
   GeoJSON -- hier keine. Genau der Inhalt der Fürth-Datei.

**Warum die erste Nachstellung gegen brouter.de nichts fand:** Alle Versuche schickten
`&turnInstructionMode=3` mit; das überschreibt das `1` aus dem Profil -- genau der Weg, den
bikerouter.de nicht geht. Und die Server-GPX (`FormatGpx`) ist eine andere Datei als der
Browser-Export, daher dort ohne Modus auch kein `rte`.

## Warum das Verschieben eines Wegpunkts nicht hilft (aus dem Skript von bikerouter gelesen)

- Die Route besteht aus **Teilstrecken je Wegpunkt-Paar**, jede mit eigener Anfrage (`queue` →
  `getRoute(segment)`). Ein verschobener Wegpunkt rechnet nur die **zwei angrenzenden** Teilstrecken
  neu; der Export hängt alle Teilstrecken aneinander (`_concatTotalTrack`).
- Die Anfragen tragen **nur den Profilnamen** (bei eigenen Profilen den hochgeladenen
  `custom_…`-Namen) und `profile:correctMisplacedViaPointsDistance` -- **kein**
  `turnInstructionMode`. Der steckt nur im Profil selbst; Änderungen unter „Optionen" schreibt die
  Oberfläche beim Übernehmen in den Profiltext und lädt ihn als eigenes Profil hoch.
- Das **Exportformat** dagegen liest `turnInstructionMode` aus dem **Profil-Text im Editor** bzw.
  dem offenen Optionen-Formular (`getProfileVar`): Steht dort 3, schreibt der Export
  `creator="OsmAndRouter"` mit `rte` -- auch wenn die Strecken mit dem Serverprofil (Modus 1) ohne
  Hinweise berechnet wurden. Das ist genau die Fürth-Datei: Format „OsmAnd", Inhalt nur
  start/destination. Wahrscheinlichster Ablauf: Wert auf 3 gestellt, aber nicht als eigenes Profil
  übernommen; beim Verschieben blieb der Profilname der des Serverprofils.

## Sicherer Weg: Datei direkt vom Server holen

Nicht über die Oberfläche, sondern mit dem Link der Route (steht in der GPX unter `<link>`,
`lonlats=…`) und ausdrücklichem Parameter -- liefert die komplette Datei mit allen Hinweisen
(getestet für die Fürth-Route, 113 Hinweise, `creator="OsmAndRouter"`):

```
https://bikerouter.de/brouter-engine/brouter?lonlats=LON,LAT|LON,LAT|…&profile=Trekking-tracks&alternativeidx=0&format=gpx&turnInstructionMode=3&exportWaypoints=1
```

## Was zu tun ist

- **Dauerhaft:** in bikerouter.de „Profil personalisieren" → Text-Editor-Reiter (neben
  „Optionen") → Inhalt von [`brouter/gravel-schnell.brf`](brouter/gravel-schnell.brf) einfügen →
  übernehmen. Eigene Profile lädt die Oberfläche nur vorübergehend hoch (Hinweis „temporäres
  Profil") -- die Datei hier im Repo ist die Ablage.
- **Nur für eine Route:** unter „Optionen" `turnInstructionMode` auf „osmand-style" stellen und
  **übernehmen** (wird damit ebenfalls ein eigenes `custom_…`-Profil), dann die **ganze** Route neu
  berechnen lassen -- nur verschobene Wegpunkte reichen nicht, s. o.

Prüfen: die exportierte Datei hat viele `<rtept>` mit `<turn>` (`grep -c '<turn>' route.gpx`).

## Das korrigierte Profil `brouter/gravel-schnell.brf`

- `turnInstructionMode = 3`, Optionsliste 0-6, Warnung vor 1 im Beschreibungstext.
- **Alle Nutzer-Parameter** stehen jetzt unter „Optionen" (vorher nur die drei
  Abbiegehinweis-Parameter und `processUnusedTags`): `iswet`, `cycleroutes_pref`, `MTB_factor`,
  `smallpaved_avoidance`, `avoid_unsafe`, `hills` (Auswahl 0-5), `isbike_for_mainroads`,
  `path_preference`, `consider_elevation`, `consider_smoothness`, `allow_steps`, `allow_ferries`,
  `allow_traffic_penalty`, `StrictNOBicycleaccess`, `valley_nonflat_multiplier`,
  `allow_default_barrier_restriction`. Die internen Parameter (ab „Internal parameters") bleiben
  bewusst draußen.
- 0/1-Schalter sind jetzt `false`/`true` (Checkbox; das Formular liest nur `true` als an).
- `smallpaved_factor -0.75` → `smallpaved_avoidance 0.75`, intern
  `smallpaved_factor = sub 0 smallpaved_avoidance`. Grund: brouter-web liest Werte nur als
  `[\w.]*` -- **kein Minuszeichen**. Eine negative Zahl lässt den Parameter stillschweigend aus dem
  Formular verschwinden (getestet). Kleine Asphaltwege *bevorzugen* (positiver
  `smallpaved_factor`) geht damit nur noch im Profiltext -- für ein Gravel-Profil egal.

Regeln für Formular-Zeilen (brouter-web `Profile._buildParamsForm`/`_buildCustomProfile`, stehen
auch als Kommentar im Profil): `assign <name> = <wert> # %<name>% | <beschreibung> | <typ>`; Wert
nur Zahl oder `true`/`false`; Beschreibung ohne `%` und `|` (sie landet als HTML im Formular);
Auswahl-Texte ohne `,` und `=`; nur im globalen Kontext; und kein Kommentar darf die
Kontext-Markierung `---context:` enthalten -- brouter-web schneidet den Text daran auf (beim Testen
passiert: Formular leer).

**Geprüft** (BRouter-Quelltext, `misc/profiles2/lookups.dat` 11.2):
- Profil parst (`IntegrityCheckProfile`).
- Gegen das Original: `costfactor`, `uphill-/downhillcostfactor`, `turncost`, `initialcost`,
  `initialclassifier`, `nodeaccessgranted`, `priorityclassifier`, `classifiermask` (Weg) und
  `initialcost` (Knoten) für je 300 000 zufällige Tag-Kombinationen: **0 Abweichungen**; globale
  Werte gleich bis auf `turnInstructionMode` 1 → 3.
- Formularlogik von brouter-web (Stand Oktober 2024, nachgebaut in Node): 20 Parameter erkannt;
  unverändert übernehmen ergibt byte-identischen Text; geänderte Werte (u. a. `iswet`,
  `hills = 4`, `smallpaved_avoidance = 0`, `consider_elevation` aus) routen identisch zum
  entsprechend von Hand geänderten Original.
- **Nicht in der echten Oberfläche bedient** (bikerouter.de war aus dieser Umgebung nicht
  erreichbar). Laut dem anderen Analysestrang schreibt bikerouter die Optionen wie brouter-web in
  den Text und lädt ihn hoch; einmal eine der neuen Checkboxen umschalten, übernehmen und neu
  berechnen lassen bestätigt es.

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
(Profiltexte: `https://bikerouter.de/profiles/<id>.brf`; `Trekking-tracks` hat außerdem
`pass2coefficient 0`, das hat mit den Hinweisen aber nichts zu tun -- mit `turnInstructionMode=3`
kommen sie trotzdem.)
