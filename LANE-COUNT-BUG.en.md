# Investigation: lane-count discrepancy at Fronmüllerstraße/Schwabacher Straße

*English translation of [LANE-COUNT-BUG.md](LANE-COUNT-BUG.md). The German file is the authoritative version; change both together.*

As of 2026-09-20. Arose from a firmware-side debugging session in the TRGB-BikeComputer
repo (live analysis via `adb logcat` + serial log during two real test rides over the same
junction). Noted here so that the actual analysis can be continued in this repo.

## The actual suspected case

At the junction Fronmüllerstraße → Schwabacher Straße (left turn) OsmAnd itself shows **two
lanes**. In the BLE protocol (LANES tag, see PROTOCOL.en.md) only **one** lane arrives,
though:

```
🧭 Lanes in 155 m: [TURN_LEFT*]  Next lanes in 439 m: [TURN_LEFT | STRAIGHT* | STRAIGHT/TURN_RIGHT*]
```

(`TURN_LEFT*` = one lane, primary TURN_LEFT, ACTIVE flag set. Reproduced identically on two
separate test rides.)

Open question: **where does the second lane get lost** - already in OsmAnd's own AIDL bundle
(`next_turn_lanes`), or only when parsing in TrailBridge?

### Concrete next step

In `OsmAndLink.java`, method `parse(AppInfoParams info)`, line ~250:

```java
List<Lane> lanes = parseLanes(turnInfo.getString("next_turn_lanes"));
```

Log temporarily right before it:

```java
Log.i(TAG, "raw next_turn_lanes=" + turnInfo.getString("next_turn_lanes"));
```

Rebuild, ride the same junction again. The raw `Arrays.toString(int[])` string shows at once
whether OsmAnd itself already supplies only one value (then an OsmAnd-side problem/limitation,
possibly an upstream report needed) or whether `parseLanes()`/`Lane.fromOsmAndLaneValue()`
discards something here (then a TrailBridge-side bug).

## Side finding: the documented `adb logcat` command returns nothing

`README.md` recommends `adb logcat -s OsmAndLink -s BikeGatt` for diagnosis. Tried live during
a ~7-minute test ride (app demonstrably running, `pid` active, debug build) - **not a single
line** captured, neither with the `-s TAG` syntax nor with the classic filter-spec form
(`'OsmAndLink:V' 'BikeGatt:V' '*:S'`). The tag names themselves are correct (verified against
the source code: `OsmAndLink.java:52`, `BikeGatt` in `BikeComputerGattServer.java:49` - plus a
third tag not mentioned in the README, `GpsLink` in `GpsLink.java:22`).

Most likely explanation: the only `Log.i(TAG, ...)` call of `OsmAndLink.java` sits in
`setStatus()` (~line 312) and fires only on connection-status changes, not per nav update. On
a ride without disturbance and without dropped connection there is simply nothing to log at
this level. For real update-by-update diagnosis a log point is currently missing, e.g. the
parsed `NavState` in `parse()` or the raw `next_turn_lanes`/`after_nextturn_lanes` strings
themselves (see above - coincides with the actual investigation step anyway).

## Code review in this repo (2026-09-20, without a rebuild -- the phone was still navigating)

Went through `parseLanes()`, `Lane.fromOsmAndLaneValue()`,
`Maneuver.fromOsmAndLaneTurnType()`, `NavFrameEncoder.writeLanes()` and `NavState` once in
full: found no place that would discard a second lane (the split on comma is correct, no
dedupe logic, `writeLanes()` writes `lanes.size()*4` bytes without truncation). So the
suspicion remains: OsmAnd's AIDL bundle itself supplies only one value in `next_turn_lanes` at
this junction.

Built the proposed log point into `OsmAndLink.java` (method `parse()`) directly -- it now
logs `raw next_turn_lanes=...` and `raw after_nextturn_lanes=...` before parsing. Not yet
built/installed (the app was still running at the time of the change, no restart wanted).

**Next step at the next build:** `gradle assembleDebug`, install, ride the same junction
(Fronmüllerstraße -> Schwabacher Straße) again, let `adb logcat -s OsmAndLink` run along
(note: the documented logcat command returned nothing in the last test -- see the side
finding below, if need be `adb logcat` without a tag filter and grep it yourself). If the raw
string already shows only one number, it is an OsmAnd-side problem (upstream report); if it
shows two, the bug is in TrailBridge after all and the review above missed something.

## Known, already documented limitation (not a new finding)

`OsmAndLink.java`, comment at `getLeftDistance()`/distance handling (~lines 270-276): OsmAnd's
AIDL discards the actual return value for the lane distance upstream (verified against
OsmAnd master, as of September 2026) - coincides with PROTOCOL.en.md's note that
`LANE_DISTANCE_M`/`NEXT_LANE_DISTANCE_M` currently carry the same value as the maneuver
distance. The firmware side has already taken this into account (LANE_DISTANCE_M is not used
there for display decisions). Mentioned here only for completeness, not an open point.
