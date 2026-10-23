# BRouter / bikerouter.de: why a GPX has no turn instructions

*English translation of [BROUTER-VOICEHINTS.md](BROUTER-VOICEHINTS.md). The German file is the authoritative version; change both together.*

As of 2026-10-04, test ride Fürth (`Fürth - 44.1 km, 225 hm.gpx`; the profile ID
`Trekking-tracks` is called "Gravel (schnell)" in the bikerouter interface and is based on
Poutnik's template "Trekking in Tracks mode"; the same problem occurred earlier with other
profiles). **Cause found, corrected profile:**
[`brouter/gravel-schnell.brf`](brouter/gravel-schnell.brf).

## Short version

The route itself is fine, only the instruction mode is missing. The profile has
`assign turnInstructionMode = 1` (*auto-choose*). With 1 the BRouter server does **not** take
the mode from the profile but only from the request -- and the GeoJSON request of
bikerouter.de brings none. So the server computes with mode 0: no instructions. The GPX
export, however, is produced in the browser and builds OsmAnd format anyway -- with an empty
list of instructions.

Remedy: mode 3 (osmand-style), either fixed in the profile text
(`brouter/gravel-schnell.brf`) or in the profile options before computing. The "working"
copied profile therefore differed not only in the option list (`4=comment-style, ...`) but
in the **value**: `= 3` there, `= 1` in the original. The additional list entries are only
choices in the form.

## Measured against bikerouter.de

Against `https://bikerouter.de/brouter-engine/brouter` (GeoJSON, route Fürth, profile
`Trekking-tracks`; likewise `trekking`, `gravel`, `fastbike`, `quaelnix-gravel`,
`m11n-gravel`, `MTB`):

| Request | `voicehints` in the GeoJSON |
|---|---|
| no parameter / `turnInstructionMode=0` / `profile:turnInstructionMode=0` / `=1` | **missing** |
| `profile:turnInstructionMode=3` (OsmAnd) | **113** (Fürth), 19-40 for the short test routes |

The same route with `format=gpx&turnInstructionMode=3` straight from the server has 113
`<turn>`; the header line (44110 m, cost 103883, 2 h 14 m 52 s) is identical to that of the
Fürth file. So the router can do it; the file was only exported without instructions.

## The chain in the source (abrensch/brouter, nrenner/brouter-web)

1. **Profile value:** `RoutingContext.readGlobalConfig()`
   ```java
   int tiMode = (int) expctxGlobal.getVariableValue("turnInstructionMode", 0.f);
   if (tiMode != 1) { // automatic selection from coordinate source
     turnInstructionMode = tiMode;
   }
   ```
   With 1 the value from the request stays (`RoutingParamCollector`: `turnInstructionMode`,
   `timode`, `turnInstructionFormat`), otherwise the field default **0**. "Auto" is meant for
   the BRouter app, which knows the mode from the calling program (OsmAnd, Locus).
2. **`profile:` parameter:** `profile:<name>=<value>` in the request is inserted before the
   profile text as an assignment and wins against its first `assign` line
   (`BExpression.parse`, `doNotChange`). That is how `profile:turnInstructionMode=3` gets its
   instructions above. The interface does **not** use this for the options (see next section).
3. **Routing:** instructions arise only from detours registered during routing
   (`RoutingEngine`: `if (routingContext.turnInstructionMode > 0)
   guideTrack.registerDetourForId(...)`); `OsmTrack.processVoiceHints()` aborts without them,
   `FormatJson` then leaves `voicehints` out.
4. **Export in the browser:** brouter-web `Export._formatTrack()` → `BR.Gpx.format()` with
   `OsmAndVoiceHints` (minified at bikerouter, class `Hs`): `creator="OsmAndRouter"` and a
   `rte` that **always** contains `start` and `destination`, in between the `voicehints` from
   the GeoJSON -- none here. Exactly the content of the Fürth file.

**Why the first reproduction against brouter.de found nothing:** all attempts sent
`&turnInstructionMode=3` along; that overrides the `1` from the profile -- exactly the path
that bikerouter.de does not take. And the server GPX (`FormatGpx`) is a different file from
the browser export, hence no `rte` there without a mode either.

## Why moving a waypoint does not help (read from bikerouter's script)

- The route consists of **segments per pair of waypoints**, each with its own request
  (`queue` → `getRoute(segment)`). A moved waypoint recomputes only the **two adjacent**
  segments; the export chains all segments together (`_concatTotalTrack`).
- The requests carry **only the profile name** (for custom profiles the uploaded `custom_…`
  name) and `profile:correctMisplacedViaPointsDistance` -- **no** `turnInstructionMode`. That
  is only in the profile itself; changes under "Options" are written into the profile text by
  the interface when applied, and uploaded as a profile of its own.
- The **export format**, on the other hand, reads `turnInstructionMode` from the **profile
  text in the editor** or the open options form (`getProfileVar`): if it says 3 there, the
  export writes `creator="OsmAndRouter"` with `rte` -- even if the segments were computed
  with the server profile (mode 1) without instructions. That is exactly the Fürth file:
  format "OsmAnd", content only start/destination. Most likely sequence: value set to 3 but
  not applied as a profile of its own; when moving, the profile name stayed that of the
  server profile.

## Safe way: fetch the file straight from the server

Not through the interface, but with the link of the route (it is in the GPX under `<link>`,
`lonlats=…`) and an explicit parameter -- returns the complete file with all instructions
(tested for the Fürth route, 113 instructions, `creator="OsmAndRouter"`):

```
https://bikerouter.de/brouter-engine/brouter?lonlats=LON,LAT|LON,LAT|…&profile=Trekking-tracks&alternativeidx=0&format=gpx&turnInstructionMode=3&exportWaypoints=1
```

## What to do

- **Permanently:** in bikerouter.de "Personalize profile" → text editor tab (next to
  "Options") → paste the content of [`brouter/gravel-schnell.brf`](brouter/gravel-schnell.brf)
  → apply. The interface uploads custom profiles only temporarily (note "temporary profile")
  -- the file here in the repo is the storage.
- **For one route only:** under "Options" set `turnInstructionMode` to "osmand-style" and
  **apply** (which also makes it a `custom_…` profile of its own), then have the **whole**
  route recomputed -- moved waypoints alone are not enough, see above.

Check: the exported file has many `<rtept>` with `<turn>` (`grep -c '<turn>' route.gpx`).

## The corrected profile `brouter/gravel-schnell.brf`

- `turnInstructionMode = 3`, option list 0-6, warning about 1 in the description text.
- **All user parameters** are now under "Options" (before, only the three turn-instruction
  parameters and `processUnusedTags`): `iswet`, `cycleroutes_pref`, `MTB_factor`,
  `smallpaved_avoidance`, `avoid_unsafe`, `hills` (choice 0-5), `isbike_for_mainroads`,
  `path_preference`, `consider_elevation`, `consider_smoothness`, `allow_steps`,
  `allow_ferries`, `allow_traffic_penalty`, `StrictNOBicycleaccess`,
  `valley_nonflat_multiplier`, `allow_default_barrier_restriction`. The internal parameters
  (from "Internal parameters") are deliberately left out.
- 0/1 switches are now `false`/`true` (checkbox; the form reads only `true` as on).
- `smallpaved_factor -0.75` → `smallpaved_avoidance 0.75`, internally
  `smallpaved_factor = sub 0 smallpaved_avoidance`. Reason: brouter-web reads values only as
  `[\w.]*` -- **no minus sign**. A negative number makes the parameter silently vanish from
  the form (tested). *Preferring* small paved ways (positive `smallpaved_factor`) now only
  works in the profile text -- irrelevant for a gravel profile.

Rules for form lines (brouter-web `Profile._buildParamsForm`/`_buildCustomProfile`, also in
the profile as a comment): `assign <name> = <value> # %<name>% | <description> | <type>`;
value only a number or `true`/`false`; description without `%` and `|` (it ends up as HTML in
the form); choice texts without `,` and `=`; only in the global context; and no comment may
contain the context marker `---context:` -- brouter-web cuts the text at it (happened while
testing: form empty).

**Checked** (BRouter source, `misc/profiles2/lookups.dat` 11.2):
- The profile parses (`IntegrityCheckProfile`).
- Against the original: `costfactor`, `uphill-/downhillcostfactor`, `turncost`,
  `initialcost`, `initialclassifier`, `nodeaccessgranted`, `priorityclassifier`,
  `classifiermask` (way) and `initialcost` (node) for 300 000 random tag combinations each:
  **0 deviations**; global values equal except `turnInstructionMode` 1 → 3.
- Form logic of brouter-web (as of October 2024, rebuilt in Node): 20 parameters recognized;
  applying unchanged yields byte-identical text; changed values (among others `iswet`,
  `hills = 4`, `smallpaved_avoidance = 0`, `consider_elevation` off) route identically to the
  original changed by hand accordingly.
- **Not operated in the real interface** (bikerouter.de was not reachable from this
  environment). According to the other line of analysis bikerouter writes the options into
  the text like brouter-web and uploads it; toggling one of the new checkboxes once, applying
  and having it recomputed confirms it.

## What TrailBridge does about it

If the instructions are missing (only `start`/`destination` in the `rte`, no OsmAnd
segments), the parser derives them from the track geometry (`GpxParser`; 66 steps for the
Fürth file). Those are angle instructions without street names and roundabout exits --
usable, but worse than the router's. On loading, TrailBridge shows whether the instructions
come "from the file" or "from the track geometry".

## Reproducing

```sh
LL="10.981473,49.472323|10.763898,49.431686|10.735595,49.442334|10.829473,49.452626|10.844944,49.460391|10.850388,49.459014|10.981318,49.472368"
for x in "" "&profile:turnInstructionMode=3"; do
  curl -sL "https://bikerouter.de/brouter-engine/brouter?lonlats=$LL&profile=Trekking-tracks&alternativeidx=0&format=geojson$x" \
   | python3 -c 'import sys,json;p=json.load(sys.stdin)["features"][0]["properties"];print(len(p.get("voicehints",[])))'
done   # 0, then 113
```
(Profile texts: `https://bikerouter.de/profiles/<id>.brf`; `Trekking-tracks` also has
`pass2coefficient 0`, which has nothing to do with the instructions, though -- with
`turnInstructionMode=3` they come anyway.)
