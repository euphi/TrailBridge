# Route overview: the BikeComputer's side

*English translation of [FIRMWARE-OVERVIEW.md](FIRMWARE-OVERVIEW.md). The German file is the authoritative version; change both together.*

Reference for the firmware side of the route overview. It began as a template and has since
been implemented: in the `TRGB-BikeComputer` repo on `main` (commits `43ecfa1` code,
`2374ffe` docs), in no firmware release yet and untested on the device. The app side is in
TrailBridge 0.5.0. The wire format is authoritatively in [PROTOCOL.en.md](PROTOCOL.en.md),
section "Route overview service" -- this document says what follows from it for the
firmware and how it does it. What the rider sees of it is described by `doc/ROUTE.md`
(German `ROUTE.de.md`) in the BikeComputer repo.

## What arrives

As long as TrailBridge plays a GPX route (real or as a test ride):

- In every nav frame a new tag `OVERVIEW_REVISION` (0x0E, 1 byte). A firmware without the
  route overview skips it as an unknown tag.
- A fourth service with one characteristic that can only be read. Its value is the overview:
  the destination, the waypoints ahead (name, remaining distance and remaining time of the
  route at the waypoint) and the climbs ahead (number, elevation gain, length, remaining
  distance at the foot).

| | UUID |
|---|---|
| Service | `7ee954a6-7a19-4b48-b052-f00d00e01e58` |
| Characteristic (`READ`) | `f031faa3-083d-4818-9eca-38420be835d0` |

With navigation from OsmAnd the tag is missing and the characteristic returns
`OVERVIEW_NONE`.

## What the firmware does

1. **Remember the characteristic.** After connecting it looks for the service without
   subscribing -- there is no CCCD. If the service is missing, it is an older TrailBridge
   version.
2. **Revision from the nav frame.** `handleNavData()` reads `OVERVIEW_REVISION` (0x0E). If
   the tag is missing from a nav frame, `NAV_NONE` arrives or the connection drops, it
   discards the overview.
3. **Read when the revision differs** from that of the overview held. `handleNavData()` runs
   in the indicate callback, where `readValue()` would block the BLE stack. So the callback
   only notes the read; the reading happens in a task of its own
   (`BLEDevices::overviewReaderTask()`). If the read fails, another attempt follows after 4 s.
4. **Parse the frame** (below, `src/RouteOverview.*`) and keep the `REVISION` from the frame,
   not the one from the nav frame. If the two do not match at the next nav frame, it reads
   once more.
5. **Recompute distance and time at every nav frame** (`src/RouteMonitor.*`). Between two
   frames the wheel sensor carries the distance on; the time stays that of the last frame.
6. **Show** on the `RimRidgeRoute` screen (swipe left from the nav screen): the destination
   with arrival time, below it waypoints (name, distance, arrival) and climbs (climb n/N,
   distance to the foot or "x km to go" within the climb, elevation gain, length, average
   gradient), the nearest first in each case.

### Long value

The overview is up to 512 bytes long, so longer than one ATT packet. `readValue()` fetches
it in one call anyway: the BLE library of arduino-esp32 3.3 reads with NimBLE through
`ble_gattc_read_long()`, with Bluedroid the stack chains the read-blob responses together
itself (up to 600 bytes). Checked in the source, not on the device.

`setMTU(256)` is still enough. With MTU 23 the read would only take longer.

The parser has to cope with a truncated last TLV (length beyond the end of the frame: stop,
keep what has been read so far).

### Parser

Like the others: byte 0 version, byte 1 type, then TLVs; skip unknown tags.

```cpp
// Sketch from the template, not the firmware's code
struct OverviewWaypoint { uint32_t remainingAtM, remainingTimeAtS; bool destination; char name[33]; };
struct OverviewClimb    { uint8_t number; uint16_t gainM; uint32_t lengthM, footRemainingAtM; };

case 0x01: revision = v[0]; break;                     // REVISION
case 0x02: waypointsAhead = v[0]; break;               // WAYPOINTS_AHEAD (without destination)
case 0x03: climbsTotal = v[0]; break;                  // CLIMBS_TOTAL
case 0x04: if (len >= 9) {                             // WAYPOINT
               wp.destination      = v[0] & 0x01;
               wp.remainingAtM     = le32(v + 1);
               wp.remainingTimeAtS = le32(v + 5);      // 0xFFFFFFFF: unknown
               copyName(wp.name, v + 9, len - 9);      // at most 32 bytes, UTF-8
           } break;
case 0x05: if (len >= 11) {                            // CLIMB, more than 11 bytes: ignore the rest
               cl.number = v[0]; cl.gainM = le16(v + 1); cl.lengthM = le32(v + 3);
               cl.footRemainingAtM = le32(v + 7);
           } break;
```

The first `WAYPOINT` is always the destination. After it waypoints and climbs are mixed, the
nearest first in each case. At most about 40 entries fit into 512 bytes; the firmware holds
that many and shows 24 of them.

### Distance and time

```cpp
int64_t  distM = (int64_t)navRemainingDistanceM - wp.remainingAtM;   // < 0: reached, do not show
uint32_t timeS;
if (wp.remainingTimeAtS != 0xFFFFFFFF)                               // times from the GPX
    timeS = navRemainingTimeS > wp.remainingTimeAtS ? navRemainingTimeS - wp.remainingTimeAtS : 0;
else                                                                 // otherwise proportional to the distance
    timeS = navRemainingDistanceM == 0 ? 0
          : (uint32_t)((uint64_t)navRemainingTimeS * distM / navRemainingDistanceM);

int64_t footM   = (int64_t)navRemainingDistanceM - cl.footRemainingAtM;   // <= 0: already in the climb
int64_t summitM = footM + cl.lengthM;                                      // to the summit
```

`navRemainingDistanceM` and `navRemainingTimeS` are `REMAINING_DISTANCE_M` (0x08) and
`REMAINING_TIME_S` (0x09) of the last nav frame. The arrival time is the time of day plus
`timeS`.

Which of the two time paths applies is decided by the GPX file: if it has time stamps, the
remaining time and the time anchors come from them (the router's estimate or a recorded
ride, both know the climbs). Without time stamps TrailBridge computes with the current
speed; before a long climb the time is then too optimistic. The firmware does not have to
tell them apart, only follow the anchor.

Climbs have no time anchor.

## When the overview changes

| Event | Consequence |
|---|---|
| Route started (also off the route) | first overview, new revision |
| Waypoint reached | it drops out, new revision |
| Summit reached | the climb drops out, new revision |
| Jump in the test ride | new revision, if something different lies ahead afterwards |
| Route ended | `OVERVIEW_NONE`, nav frames without a revision |

On a ride that is a few dozen reads. In between only the distances change, and they come
from the nav frame.

If the list is truncated (more than 512 bytes), `WAYPOINTS_AHEAD` indicates more waypoints
than are in the frame; for the climbs the last number is smaller than `CLIMBS_TOTAL`. The
rest moves up with the next revisions. The firmware then shows "List truncated" in the
footer.

## Testing

- **Without firmware:** load a GPX with `wpt` elements in TrailBridge (the summary names the
  number of recognized waypoints and, if the file has time stamps, the "riding time per
  file") and start the test ride. Read the characteristic in nRF Connect; `adb logcat -s
  TrailBridge` shows every new revision with the number of entries and the length.
- **Test vector:** the example in PROTOCOL.en.md (78 bytes). The same frame is in
  `OverviewFrameEncoderTest.encodesDocumentedLayout`.
- **Truncated list:** a GPX with more than 20 named waypoints.
- **Both time paths:** a GPX with and one without time stamps. BRouter writes them with
  `profile:showtime=1` in the request or `assign showtime = true` in the profile;
  `Tools/gpxenrich` leaves them out so far.
- Still open on the device: the whole firmware side, above all whether the long read over the
  real connection works as read in the source.

## Still open

- The elevation still to be climbed -- needs an extension of the protocol (see
  [ROADMAP.en.md](ROADMAP.en.md), "Climb overview, the rest").
- A notice before a waypoint (pop-up) and access to the overview from the main screen.

## Docs in the BikeComputer repo

Brought up to date (commit `2374ffe`): `doc/ROUTE.md` and `ROUTE.de.md`, the roadmap and the
TrailBridge page. `doc/trailbridge/PROTOCOL*.md` keep coming from here through
`doc/fetch_trailbridge.sh`.
