# TrailBridge BLE protocol v1

English translation of
[PROTOCOL.md](https://github.com/euphi/TrailBridge/blob/main/PROTOCOL.md). The German
file is the authoritative version; change both together.

## BLE roles

- **Companion app (phone) = peripheral / GATT server**
- **TRGB-BikeComputer (ESP32) = central / GATT client** (as before with Komoot -- the
  existing connection management in `BLEDevices.cpp` therefore keeps its basic structure,
  only the parser changes)

## UUIDs

- Service:        `f7ac2b76-986b-45fd-8e44-f116a61f319d`
- Characteristic: `7473da02-2de8-4f48-9e46-21b36380c176`
  Properties: `INDICATE`, `READ`

Freshly generated, no reuse of Komoot's old UUIDs -- new protocol, new identifier.

## Kind of transmission

- **Primary: indicate** (with confirmation) on the characteristic above. The ESP32
  enables it by writing `0x0200` to the standard CCCD descriptor
  (`00002902-0000-1000-8000-00805f9b34fb`).
- **Fallback: read.** Returns the frame sent last, e.g. directly after connecting,
  before the first indication arrives, or in case indicate misbehaves on an ESP32 BLE
  stack.
- **Heartbeat:** the companion app sends the frame computed last again every 5 s even
  without a change in content, so that the firmware can tell a dead connection from "no
  change because we are standing at a traffic light".

## Frame format

```
Byte 0:   protocol version (currently 1)
Byte 1:   message type
Byte 2..: TLV entries (only for NAV_UPDATE)
```

Message types:

| Value | Name | Meaning |
|---|---|---|
| 0x00 | HELLO | sign of life/version check, no further data |
| 0x01 | NAV_UPDATE | active navigation, followed by TLV entries |
| 0x02 | NAV_NONE | no active route, no further data |

TLV entry: `tag (1 byte) | length N (1 byte) | value (N bytes)`

| Tag | Name | Length | Format | Condition |
|---|---|---|---|---|
| 0x01 | MANEUVER | 1 | maneuver code (table below) | always |
| 0x02 | MANEUVER_DISTANCE_M | 4 | uint32 LE, metres | always |
| 0x03 | ROUNDABOUT_EXIT | 1 | exit number, 1-based | only if MANEUVER == ROUNDABOUT |
| 0x04 | STREET_NAME | ≤48 | UTF-8, no null terminator | always (may be empty) |
| 0x05 | NEXT_MANEUVER | 1 | maneuver code | only if the maneuver after next is known |
| 0x06 | NEXT_MANEUVER_DISTANCE_M | 4 | uint32 LE, metres (counted from the *next* maneuver) | as 0x05 |
| 0x07 | NEXT_STREET_NAME | ≤48 | UTF-8 | as 0x05 |
| 0x08 | REMAINING_DISTANCE_M | 4 | uint32 LE, metres to the destination | always |
| 0x09 | REMAINING_TIME_S | 4 | uint32 LE, seconds to the destination | always |
| 0x0A | LANES | N (multiple of 4) | list of lanes, see below | only if OsmAnd delivers lane data (`turn_lanes`, rare on pure cycle paths) |
| 0x0B | NEXT_LANES | N (multiple of 4) | as 0x0A | as 0x0A, but for the turn after next (as 0x05/0x06/0x07) |
| 0x0C | LANE_DISTANCE_M | 4 | uint32 LE, metres to the point where the LANES information applies | only if tag 0x0A is present |
| 0x0D | NEXT_LANE_DISTANCE_M | 4 | uint32 LE, metres | only if tag 0x0B is present |

**The firmware must skip unknown tags** (respect the length, ignore the value) -- this
leaves room for later extensions without older firmware breaking on them. That is the
whole point of TLV instead of a rigid byte layout as with Komoot.

## Maneuver codes

Own, fixed codes -- independent of Komoot's old 32-slot icon index and of OsmAnd's
internal `TurnType` constants. Reference implementation: `Maneuver.java`.

| Code | Name |
|---|---|
| 0 | NONE |
| 1 | DEPART |
| 2 | ARRIVE |
| 3 | STRAIGHT |
| 4 | TURN_SLIGHT_LEFT |
| 5 | TURN_LEFT |
| 6 | TURN_SHARP_LEFT |
| 7 | TURN_SLIGHT_RIGHT |
| 8 | TURN_RIGHT |
| 9 | TURN_SHARP_RIGHT |
| 10 | KEEP_LEFT |
| 11 | KEEP_RIGHT |
| 12 | UTURN_LEFT |
| 13 | UTURN_RIGHT |
| 14 | ROUNDABOUT (exit in tag 0x03) |
| 255 | UNKNOWN |

## Lane information (LANES / NEXT_LANES)

Maps OsmAnd's lane guidance (AIDL `turnInfo` bundle keys `next_turn_lanes` /
`after_nextturn_lanes`, fed from the OSM tag `turn:lanes`; verified against
`ExternalApiHelper#updateRouteDirectionInfo` and `net.osmand.router.TurnType` in the
OsmAnd source code, as of 2026-09-18). OsmAnd delivers it as an `int[]`, one bit-packed
int per lane (bit 0 = active/recommended, bits 1-4 = primary direction, further bits
secondary/tertiary). As with the maneuver codes we do not adopt this bit layout 1:1 but
translate every direction into our own maneuver code table (above) -- so the firmware
does not depend on OsmAnd's internal `TurnType` constants.

Value of tag 0x0A/0x0B: a list of lanes, from left to right (as in OSM tagging and in
OsmAnd's array order), 4 bytes each:

| Byte | Meaning |
|---|---|
| 0 | primary maneuver code -- main arrow direction of this lane |
| 1 | secondary maneuver code, `0` (NONE) = no second direction |
| 2 | tertiary maneuver code, `0` (NONE) = no third direction |
| 3 | flags -- bit 0 = `ACTIVE` (recommended by OsmAnd's router for the computed route), bits 1-7 reserved (currently `0`, to be ignored by the firmware) |

Byte 0 is never `0`: OsmAnd already treats a lane without an explicitly set primary
direction as "straight on" internally (see `TurnType.lanesToString()`), and the app maps
this fallback to STRAIGHT (code 3) before it builds the frame -- so the firmware does not
have to treat `0` in byte 0 specially.

Length N = number of lanes × 4 (the TLV length byte limits this to at most 63 lanes --
real roads rarely have more than 6-8).

The reserved flag bits leave room for a possible later extension by bicycle-specific lane
tagging or further lane properties, without having to change the byte width per lane
afterwards.

### Distance (LANE_DISTANCE_M / NEXT_LANE_DISTANCE_M)

The point at which a choice of lane becomes relevant (e.g. where a lane fans out) does
NOT have to coincide with the point of the actual turn instruction -- on multi-lane roads
it typically lies clearly before it. LANES/NEXT_LANES therefore carry a distance of their
own, independent of MANEUVER_DISTANCE_M/NEXT_MANEUVER_DISTANCE_M (tag 0x0C/0x0D) -- the
firmware must not equate the two distances.

**Current state on the app side (as of 2026-09-19):** OsmAnd's AIDL attaches
`next_turn_lanes`/`after_nextturn_lanes` to the same `RouteDirectionInfo`/
`NextDirectionInfo` as the corresponding turn distance -- through the AIDL API both
distances are therefore actually identical at the moment.
`ExternalApiHelper#getRouteDirectionsInfo` does have a second mechanism (bundle prefix
`no_speak_next_`) which by its name is meant exactly for an earlier, unannounced lane
point -- but the return value of the corresponding
`getNextRouteDirectionInfo(..., false)` call is discarded there, and the already filled
(stale) `ni` of `after_next` is reused instead:

```java
routingHelper.getNextRouteDirectionInfo(new NextDirectionInfo(), false);
if (ni.distanceTo > 0) {
    updateTurnInfo("no_speak_next_", bundle, ni);
}
```

`no_speak_next_*` is thus, in the current OsmAnd master (verified 2026-09-19,
`OsmAnd/src/net/osmand/plus/helpers/ExternalApiHelper.java`), in fact a duplicate of
`after_next*` instead of an earlier point of its own -- it is not usable like this. Until
that is fixed upstream (or another way is found), the app sends the same value for
LANE_DISTANCE_M/NEXT_LANE_DISTANCE_M as for MANEUVER_DISTANCE_M/
NEXT_MANEUVER_DISTANCE_M. The separate tags stay nevertheless, so that this can be added
later without breaking the wire contract.

### Example

Two lanes, 45 m ahead (the choice of lane already becomes relevant here, although
according to MANEUVER_DISTANCE_M the actual turn only comes in 300 m): left lane straight
on only, right lane straight-on-or-right and recommended by the router for the route:

```
0A 08                                              LANES, length 8 (2 lanes)
   03 00 00 00                                     lane 1: STRAIGHT, --, --, flags=0
   03 08 00 01                                     lane 2: STRAIGHT, TURN_RIGHT, --, flags=ACTIVE
0C 04 2D 00 00 00                                  LANE_DISTANCE_M = 45
```

## Example

"In 120 m turn left onto Bahnhofstraße, then keep right after 300 m, 4200 m / 600 s to
the destination":

```
01 01                                              version=1, type=NAV_UPDATE
01 01 05                                           MANEUVER = TURN_LEFT (5)
02 04 78 00 00 00                                  MANEUVER_DISTANCE_M = 120
04 0E 42 61 68 6E 68 6F 66 73 74 72 61 C3 9F 65     STREET_NAME = "Bahnhofstraße"
05 01 0B                                           NEXT_MANEUVER = KEEP_RIGHT (11)
06 04 2C 01 00 00                                  NEXT_MANEUVER_DISTANCE_M = 300
08 04 68 10 00 00                                  REMAINING_DISTANCE_M = 4200
09 04 58 02 00 00                                  REMAINING_TIME_S = 600
```

37 bytes in total -- fits with a large margin into the ATT MTU of 256 bytes negotiated by
`setMTU(256)` (253 bytes usable).

## GPS position service

A BLE service of its own, independent of the navigation service above. The position data
comes straight from the phone's GPS chip (`LocationManager.GPS_PROVIDER`), not from
OsmAnd -- so it also works when OsmAnd is not running or not installed at all.

### UUIDs

- Service:        `66b5835c-9be6-43d1-b24a-f9337c0fcb7f`
- Characteristic: `10c49e7b-4808-4d63-9b68-9ba6c385db0d`
  Properties: `INDICATE`, `READ`

Deliberately NOT advertised in the advertising packet (31-byte legacy limit -- see the
comment in `BikeComputerGattServer`, the navigation service alone already fills the main
packet). The bike computer finds it after connecting through normal GATT service
discovery, just as it finds e.g. the battery service of the other sensors without an
advertising entry.

### Kind of transmission

As for the navigation service: indicate primarily, read as a fallback, heartbeat every
5 s -- clocked independently of the navigation heartbeat and state.

**Important for a re-implementation of the firmware:** both services share one single
`BluetoothGattServer` process on the app side. Android (and BLE platforms in general)
allow only one indicate/notify "in flight" per connected device at a time, also across
characteristics and services -- `onNotificationSent()` does not say which characteristic
has just finished. `BikeComputerGattServer` therefore serialises navigation and position
frames through a common in-flight gate. For the ESP32 as central this does not matter
(it simply receives two independent indicate streams); it is only relevant if the app
side is ever rebuilt.

### Frame format

```
Byte 0:   protocol version (currently 1)
Byte 1:   message type
Byte 2..: TLV entries (only for POSITION_UPDATE)
```

Message types:

| Value | Name | Meaning |
|---|---|---|
| 0x00 | HELLO | sign of life/version check, no further data |
| 0x01 | POSITION_UPDATE | valid GPS fix, followed by TLV entries |
| 0x02 | POSITION_NONE | no fix (yet) -- GPS off, permission missing, or no satellite reception yet |

TLV entry: `tag (1 byte) | length N (1 byte) | value (N bytes)`

| Tag | Name | Length | Format | Condition |
|---|---|---|---|---|
| 0x01 | LATITUDE_E7 | 4 | int32 LE, latitude × 1e7 | always |
| 0x02 | LONGITUDE_E7 | 4 | int32 LE, longitude × 1e7 | always |
| 0x03 | ALTITUDE_M | 4 | int32 LE, metres above the ellipsoid | only if the fix delivers an altitude |
| 0x04 | SPEED_CMS | 4 | uint32 LE, cm/s | only if the fix delivers a speed |
| 0x05 | BEARING_DEG_X100 | 2 | uint16 LE, degrees × 100 (0..35999) | only if the fix delivers a bearing |
| 0x06 | ACCURACY_M_X10 | 2 | uint16 LE, metres × 10 (horizontal accuracy) | only if the fix delivers an accuracy |
| 0x07 | FIX_AGE_MS | 4 | uint32 LE, milliseconds since this fix | always |
| 0x08 | UTC_TIME_MS | 8 | uint64 LE, UTC time of the fix in ms since 1970-01-01 (`Location.getTime()`) | if the fix has a time (> 0) |
| 0x09 | HEART_RATE_BPM | 1 | uint8, beats/min | only if TrailBridge knows a heart rate, see "Sensor values and simulation mode" |
| 0x0A | CADENCE_RPM | 1 | uint8, revolutions/min, 0 = coasting | as 0x09, for the cadence |
| 0x0B | SIM_FLAGS | 1 | uint8 bit field, see below | only if something in this frame is simulated (otherwise the tag is missing) |
| 0x0C | BARO_HEIGHT_DM | 4 | int32 LE, altitude in decimetres above sea level | only if TrailBridge knows a barometric altitude, see below |
| 0x0D | POWER_W | 2 | uint16 LE, watts (0 = coasting) | only if TrailBridge knows a power value, see below |

E7 fixed point for latitude/longitude (instead of floating point) for the same reason as
everywhere else in the TLV format: a fixed, platform-independent byte layout, no trouble
with float endianness or NaN. ±90°/±180° × 1e7 both fit comfortably into a signed int32
(at most ±2.147 billion).

FIX_AGE_MS lets the firmware recognise whether the last fix is fresh or the heartbeat is
only repeating an old state (tunnel, loss of satellites and the like) -- it is recomputed
on every send from `SystemClock.elapsedRealtime() -
location.getElapsedRealtimeNanos()/1_000_000`, so it is also correct for heartbeat
resends and not frozen at the original time of the fix.

UTC_TIME_MS is the time source for the bike computer's clock when there is no WiFi
(NTP). With `GPS_PROVIDER` it is the satellite time, i.e. independent of the phone's
clock. The current time at sending is `UTC_TIME_MS + FIX_AGE_MS` -- both values come
from the same frame, so the receiver does not have to know when the fix was made. The
BLE latency (typically < 100 ms) is not taken into account. Added later without a new
version: firmware that does not know the tag skips it by its length; an app without the
tag simply does not set a clock.

### Example

Fix at 52.5163° N, 13.3777° E, altitude 34 m, 4.2 m/s, bearing 87.5°, accuracy ±5 m,
320 ms ago:

```
01 01                                              version=1, type=POSITION_UPDATE
01 04 F8 59 4D 1F                                  LATITUDE_E7 = 525163000
02 04 68 46 F9 07                                  LONGITUDE_E7 = 133777000
03 04 22 00 00 00                                  ALTITUDE_M = 34
04 04 A4 01 00 00                                  SPEED_CMS = 420
05 02 2E 22                                        BEARING_DEG_X100 = 8750
06 02 32 00                                         ACCURACY_M_X10 = 50
07 04 40 01 00 00                                  FIX_AGE_MS = 320
```

40 bytes in total (50 with UTC_TIME_MS) -- fits easily into the negotiated ATT MTU.

## Elevation profile service

A third, independent BLE service, only active while TrailBridge plays an imported GPX
route and the route has elevation data. The profile is sent **all the time** -- on flat
ground and downhill, too, it is always of interest (test ride 2026-10-04) -- as a
**rolling window of the road ahead**, starting at the rider. A **climb** in it is not
guessed from the points by the firmware but **announced** by TrailBridge with its whole
extent (tags 0x07..0x0A, see below): it never ends before the summit, and category and
length stay those of the foot, even when the rider is already in the middle and the foot
is no longer in the frame. The flag `ROLLING` (tag 0x06) marks such frames.

A new frame goes out

- at the start and after the profile was taken back (rider off the route),
- when the climb to announce changes: its foot comes within 500 m (the window then runs
  from the rider **to the summit**), or its summit is reached (the window without an
  announcement, the road beyond the summit),
- when the rider has passed the middle of a frame that does not reach as far as it should:
  the window moves on (window without a climb: 200 steps of 25 m = 5 km; a climb that does
  not fit in one frame even on the coarsest raster).

Nothing in between. **`PROFILE_NONE` only** when the route ends or the rider leaves it --
no longer at the summit: there the next frame simply lacks the announcement and the
firmware knows the climb is over. **No heartbeat** -- the frame sent last stays available
by read and goes to newly subscribing devices.

Older TrailBridge versions (without the flag) send only the profile of a climb, from the
position to the summit, and `PROFILE_NONE` at the summit; the firmware then finds the
climb in the profile itself (see below) and stays compatible.

### What a climb is

TrailBridge searches for the climbs when loading the route (25 m raster, smoothed over
about 125 m; `ElevationProfile.findClimbs`):

- **Foot:** the first point from which the route rises by at least 3 m over the next
  100 m. A gentle beginning therefore already belongs to the climb.
- **Summit:** the highest point before the route either **drops by more than 30 m** or
  **does not rise any further for 2 km** (on average less than 0.5 % from the highest
  point so far) -- or ends. A shorter dip or a shorter flat section belongs to the climb.
- Below 10 m of altitude difference between foot and summit it is not a climb.

Example Galibier: from the south (36 km over the Lautaret, with flat sections) it is one
climb. From the north it is two -- after the Col du Télégraphe the road descends for
almost 5 km and about 165 m to Valloire.

Only for frames **without** `ROLLING` (older TrailBridge versions) the firmware
(`ClimbProfile.cpp`) finds foot and summit in the profile sent with the same criteria; the
end of the profile counts as the summit for it. With `ROLLING` the announcement alone
counts; TrailBridge applies the criteria of this section.

### UUIDs

- Service:        `3c1f6a90-5b2e-4d7a-9c48-e0a1b7d25f63`
- Characteristic: `a84e0d17-6f3b-4c52-8e9d-1b70c2f4a596`
  Properties: `INDICATE`, `READ`

Like the position service not in the advertising, discovery after connecting. Shares the
in-flight gate with the other services (see above).

### Frame format

```
Byte 0:   protocol version (currently 1)
Byte 1:   message type
Byte 2..: TLV entries (only for PROFILE_UPDATE)
```

| Value | Name | Meaning |
|---|---|---|
| 0x00 | HELLO | state after connecting, no profile |
| 0x01 | PROFILE_UPDATE | profile, followed by TLV entries; replaces an earlier one |
| 0x02 | PROFILE_NONE | show no (climb) profile any more |

| Tag | Name | Length | Format |
|---|---|---|---|
| 0x01 | START_REMAINING_DISTANCE_M | 4 | uint32 LE, remaining distance of the route to the destination **at the first profile point** |
| 0x02 | STEP_M | 1 | distance between the profile points in metres: 25, 50, ... 250 (see "Raster") |
| 0x03 | BASE_ALT_DM | 2 | int16 LE, altitude of the first point in decimetres |
| 0x04 | DELTAS_DM | N | N × int8: change of altitude from point k to point k+1, in units of `DELTA_SCALE_DM` dm |
| 0x05 | DELTA_SCALE_DM | 1 | uint8, optional: unit of the deltas in decimetres. If the tag is missing, 1 applies |
| 0x06 | FLAGS | 1 | uint8, optional: bit 0 `ROLLING` = rolling window of the road ahead; a climb is only the one announced in 0x07..0x0A. If the tag is missing: frame of an older TrailBridge (profile up to the summit) |
| 0x07 | CLIMB_FOOT_REMAINING_M | 4 | uint32 LE, remaining distance of the route to the destination **at the foot** of the announced climb (may lie behind the first point, i.e. be larger than `START_REMAINING_DISTANCE_M`) |
| 0x08 | CLIMB_SUMMIT_REMAINING_M | 4 | uint32 LE, remaining distance **at the summit** (may lie beyond the last point) |
| 0x09 | CLIMB_FOOT_ALT_DM | 2 | int16 LE, altitude of the foot in decimetres |
| 0x0A | CLIMB_SUMMIT_ALT_DM | 2 | int16 LE, altitude of the summit in decimetres |

The four climb tags come **all together or not at all**; if they are missing with
`ROLLING` set, there is no climb (neither under the rider nor at most 500 m ahead). The
climb that is announced is the one the rider is on or whose foot is at most 500 m ahead,
until its summit is reached (to half a raster step). Repeated frames of the same climb
carry the **same** values -- the firmware recognises it by them and keeps category and
display stable. Category = length × mean gradient of the whole climb (foot to summit), not
of the rest.

Point k lies at a remaining distance of `START_REMAINING_DISTANCE_M - k × STEP_M`, its
altitude is `BASE_ALT_DM + DELTA_SCALE_DM × Σ DELTAS_DM[0..k-1]` (in dm). There are N+1
points. The altitudes are smoothed over about 125 m (GPX altitudes are noisy).

**Raster:** the whole rest of the climb should fit into one frame. Up to N × 25 m (5 km
with N = 200) `STEP_M` is 25; for longer climbs it becomes the smallest multiple of 25 m
with which it fits, at most 250 m (50 km with N = 200; a 36 km climb comes with 200 m).
The raster is laid backwards from the summit: **the last point is the summit**, the first
lies at the rider's position or less than one step behind it (only right at the start of
the route can it lie just in front of the rider).

On a coarse raster a steep step no longer fits into an int8 as dm (±12.7 m; at 200 m
that would be 6.35 %). TrailBridge then sends `DELTA_SCALE_DM` with the smallest value
with which all deltas fit (e.g. 2 = deltas in 0.2 m). With `STEP_M` = 25 the tag is
always missing; frames for climbs up to 5 km therefore look as before.

If a climb does not fit into one frame even with 250 m (small MTU, or more than 50 km),
the profile ends N steps before the summit. The next piece follows when the rider has
reached the middle of the piece sent -- on the same raster (same `STEP_M`, distance of
the start points a multiple of it), so that the firmware can join the pieces.

**Position in the profile without an odometer:** the firmware knows
`REMAINING_DISTANCE_M` from the navigation frame (0x08); the rider's position in the
profile is `START_REMAINING_DISTANCE_M - REMAINING_DISTANCE_M` metres from the first
point. This holds along the route, not as the crow flies, and survives reconnects. If the
value lies outside `0..N × STEP_M`, the profile is stale or not reached yet.

**Length and MTU:** N depends on the negotiated MTU (payload `ATT_MTU - 3`):
`N = min(200, payload - 20)` (with `ROLLING` another 23 bytes for the flag and the climb tags: `payload - 43`); below 8 points (200 m) TrailBridge sends no profile. The
app takes the minimum over all connected devices and, without information, assumes the
256 that the firmware requests.

The firmware skips unknown tags as everywhere (respect the length).

### Example

Profile from 4200 m of remaining distance, start at 34.5 m, three steps (+1.2 m, +1.3 m,
−0.2 m) -- older form, without `ROLLING`:

```
01 01                                              version=1, type=PROFILE_UPDATE
01 04 68 10 00 00                                  START_REMAINING_DISTANCE_M = 4200
02 01 19                                           STEP_M = 25
03 02 59 01                                        BASE_ALT_DM = 345
04 03 0C 0D FE                                     DELTAS_DM = +12, +13, -2
```

Rolling window with an announced climb: foot at 5000 m of remaining distance at 100.0 m,
summit at 3500 m at 136.0 m (the points in between as above):

```
01 01                                              version=1, type=PROFILE_UPDATE
01 04 68 10 00 00                                  START_REMAINING_DISTANCE_M = 4200
02 01 19                                           STEP_M = 25
03 02 59 01                                        BASE_ALT_DM = 345
06 01 01                                           FLAGS = ROLLING
07 04 88 13 00 00                                  CLIMB_FOOT_REMAINING_M = 5000 (behind the first point)
08 04 AC 0D 00 00                                  CLIMB_SUMMIT_REMAINING_M = 3500
09 02 E8 03                                        CLIMB_FOOT_ALT_DM = 1000
0A 02 50 05                                        CLIMB_SUMMIT_ALT_DM = 1360
04 03 0C 0D FE                                     DELTAS_DM = +12, +13, -2
```

Without a climb: the same frame without the four tags 0x07..0x0A.

A long climb on a coarse raster: from 36 000 m of remaining distance, start at 1200 m,
200 m steps, deltas in 0.2 m (+12.0 m, +13.8 m, −0.6 m):

```
01 01                                              version=1, type=PROFILE_UPDATE
01 04 A0 8C 00 00                                  START_REMAINING_DISTANCE_M = 36000
02 01 C8                                           STEP_M = 200
03 02 E0 2E                                        BASE_ALT_DM = 12000
05 01 02                                           DELTA_SCALE_DM = 2
04 03 3C 45 FD                                     DELTAS_DM = +60, +69, -3 (× 0.2 m)
```

## Navigation from a GPX route

When TrailBridge plays a GPX route itself, it sends perfectly normal `NAV_UPDATE` frames
in the navigation service (format above, unchanged), computed from the GPS position.
Differences from OsmAnd: no lanes (0x0A-0x0D), street names only if the GPX contains
them. Off the route (> 50 m) `MANEUVER = UNKNOWN (255)` is sent, `MANEUVER_DISTANCE_M` is
then the distance to the route and `STREET_NAME` "Abseits der Route" (off the route).

## Sensor values and simulation mode

Besides the position, the position frame can also carry **sensor values** that
TrailBridge knows: heart rate (0x09), cadence (0x0A), barometric altitude (0x0C) and
power (0x0D); the speed is in `SPEED_CMS` (0x04). Whether the values are real or made up
is said by a tag of its own, so that both are possible side by side:

**BARO_HEIGHT_DM** is the altitude the bike computer otherwise has from its barometer, in
decimetres (`ALTITUDE_M` only has whole metres -- too coarse to compute a gradient from
it over a few dozen metres of distance). **The gradient is not transmitted:** the bike
computer computes it itself as always from altitude and distance covered. **POWER_W** is
the pedalling power of a power meter.

**SIM_FLAGS (0x0B)**, uint8 bit field. **If the tag is missing (or 0), everything in the
frame is real.**

| Bit | Name | Meaning if set |
|---|---|---|
| 0 (0x01) | SIM_POSITION | latitude/longitude/altitude/bearing are made up, not from the GPS chip (e.g. GPX test ride) |
| 1 (0x02) | SIM_SENSORS | `SPEED_CMS` is the speed of a **simulated speed sensor**; heart rate, cadence, barometric altitude and power are simulated -- and the **absence** of 0x09/0x0A/0x0C/0x0D means "this sensor does not exist" |
| 2-7 | -- | reserved, `0`, to be ignored by the firmware |

Consequences for the firmware:

- **Simulated data must never be treated as a real measurement.** Ride data that arises
  with SIM_POSITION or SIM_SENSORS set is to be marked as simulated in the log
  (`LOG_SIMULATED`).
- **SIM_SENSORS:** only a simulator build (`BC_SIM`) feeds speed, heart rate, cadence,
  altitude and power into its sensor simulator (`SimSensors`, like
  `sim <km/h> <rpm> <bpm>`; the altitude replaces the barometer, the gradient results
  from it); the normal firmware ignores the values, so that no fake kilometres end up in
  odometer and statistics. End of the simulation = the next frame without SIM_SENSORS,
  `POSITION_NONE`, a stale fix (`FIX_AGE_MS` > 5 s) or the end of the connection ->
  "sensors off".
- **Without SIM_SENSORS** 0x09/0x0A/0x0C/0x0D are real sensor values passed on by
  TrailBridge (e.g. a heart-rate strap on the phone). **Not built yet** -- the app does
  not send anything like that so far, the firmware does not evaluate it. The tag and the
  semantics are already fixed here so that this can be added without breaking the
  contract.
- The time (`UTC_TIME_MS`) stays the phone's real time in the simulation as well -- the
  bike computer's clock is supposed to stay right.

### GPX test ride

TrailBridge can "ride" the loaded GPX route instead of sending the real GPS position:
once a second a fix along the route, with `SIM_FLAGS = 0x03` (SIM_POSITION |
SIM_SENSORS). `SPEED_CMS`, `HEART_RATE_BPM`, `CADENCE_RPM`, `POWER_W` come from the GPX
(timestamps, TrackPointExtension `hr`/`cad`, power); where they are missing, from the
calculation or -- on request -- from randomly varying values that fit the route (heart
rate follows the load, cadence drops on the climb, 0 when coasting/standing; power
results from speed, gradient and acceleration, 0 when coasting); without emulation the
tag is missing, the sensor "is not there". `BARO_HEIGHT_DM` is the smoothed altitude of
the route (missing if the GPX has no elevation data). If the test ride is paused, frames
keep coming: speed 0, cadence 0. Navigation and elevation profile service run from the
simulated position as on a real ride. The end of the test ride is the first frame without
SIM_FLAGS or `POSITION_NONE`.

Example, test ride at 21.6 km/h, heart rate 132, cadence 85 (excerpt, only the last tags
of the frame):

```
09 01 84                                           HEART_RATE_BPM = 132
0A 01 55                                           CADENCE_RPM = 85
0B 01 03                                           SIM_FLAGS = SIM_POSITION | SIM_SENSORS
0C 04 80 0D 00 00                                  BARO_HEIGHT_DM = 3456 (345.6 m)
0D 02 FA 00                                        POWER_W = 250
```

## Why indicate instead of notify

With indicate the receiver (ESP32) confirms every packet at ATT level, with notify it
does not. A missed turn instruction is more annoying than a few milliseconds of extra
latency -- hence indicate.

## Compatibility

Byte 0 (version number) allows the firmware to recognise a future protocol v2 and, if
necessary, fall back to a simpler rendering instead of displaying misinterpreted data.
