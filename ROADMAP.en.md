# Roadmap

*English translation of [ROADMAP.md](ROADMAP.md). The German file is the authoritative version; change both together.*

What is open on TrailBridge, roughly by priority. What the app can already do is in the
[README](README.en.md). Much of this also needs a change on the BikeComputer; its open
points are in the
[BikeComputer roadmap](https://euphi.github.io/TRGB-BikeComputer/ROADMAP/).

"Needs protocol and firmware" means: [PROTOCOL.en.md](PROTOCOL.en.md) is extended and the
firmware has to evaluate the new part. Change both only together.

## Status

| Area | Status |
|---|---|
| Navigation from OsmAnd (maneuvers, street names, lanes, remaining distance and time) | ridden, in v0.4.4 |
| GPS position and time of day for the BikeComputer | in v0.4.4; setting the clock was not checked separately |
| GPX navigation, elevation profile of the route ahead (rolling window, climb in it announced over its whole extent), test ride, Navi mode, mode selection | in v0.5.0; only tested with the test ride and the simulator build of the firmware, no real ride yet |
| Height above sea level in the position frame (`MSL_ALTITUDE_DM`, Android 14+) | in v0.5.0 |
| Route overview: destination, waypoints (`wpt`) and climbs of the GPX route for reading | in v0.5.0, app side only checked with unit tests; the firmware evaluates it and shows it on a screen of its own (on `main` of the BikeComputer repo, untested on the device), see [FIRMWARE-OVERVIEW.en.md](FIRMWARE-OVERVIEW.en.md) |
| English interface (default) and German, depending on the phone's language; also the texts to the BikeComputer | in v0.5.0 |
| Planned routes: auto-download from the BikeLog service, import into OsmAnd, "Start in OsmAnd" | in v0.5.1-rc1; fetching, OsmAnd import and start tried on the device, round trips only briefly |
| F-Droid | v0.5.0 submitted as a PR to fdroiddata, see [FDROID.en.md](FDROID.en.md) |

## Known bugs

- **Number of lanes:** at a junction OsmAnd shows two lanes, only one arrives over BLE. The
  analysis is open, see [LANE-COUNT-BUG.en.md](LANE-COUNT-BUG.en.md).
- **Distance to the lane choice:** `LANE_DISTANCE_M` is always equal to the distance to the
  turn, because OsmAnd does not supply the earlier point (see "Reports to OsmAnd").

## Planned

Not designed yet.

- **Pairing with the BikeComputer:** the BLE services can be read without pairing and
  encryption; any BLE device in range can connect and read along the position. With bonding
  the connection would be encrypted, and the BikeComputer could recognize its phone despite
  the changing BLE address. Needs protocol and firmware.
- **Climb overview, the rest:** the route overview gives each climb its number, elevation
  gain, length and the position of the foot. Open is the elevation still to be climbed; the
  firmware shows what is in the protocol. Needs protocol and firmware.
- **Adapt the remaining time to the rider:** if the GPX has time stamps, the remaining time
  to the destination and waypoints comes from them -- as fast as the router or the recording
  gives it, not how the rider really rides. Without time stamps TrailBridge computes with
  the current speed; before a long climb that is too optimistic.
- **Time stamps from BRouter:** BRouter writes its time estimate into the GPX only with
  `showtime` in the profile, the normal export lacks it. `Tools/gpxenrich` in the
  BikeComputer repo leaves time stamps out.
- **Show the route overview in the app:** today it can only be seen on the BikeComputer
  (and in the log).
- **Direction back to the route:** off the route TrailBridge only sends the distance to it.
  Wanted: the bearing too, so the BikeComputer can show an arrow. Needs protocol and
  firmware.
- **Time lapse for the test ride:** it only runs in real time; testing a long climb
  therefore takes hours.

## Later

- **Route line:** send the course of the GPX route around the current position so the
  BikeComputer can draw turns and the way back. Needs a new BLE service for the geometry.
- **Back channel from the BikeComputer:** today there is no writable characteristic. It
  would allow keys on the BikeComputer for the app, ride data to the phone and Wi-Fi
  settings from the phone.
- **Pass on real sensor values** (e.g. a heart-rate strap on the phone). The tags are
  reserved in the protocol; but the BikeComputer pairs its sensors itself, so the benefit is
  small.

## Reports to OsmAnd

Still to be submitted, at [osmandapp/OsmAnd](https://github.com/osmandapp/OsmAnd/issues).

- **Bug:** the `no_speak_next_` data of the AIDL interface repeats the maneuver after next
  instead of the earlier point for the lane choice. Details in PROTOCOL.en.md, "Lane
  information".
- **Wish:** next waypoint with name, distance and arrival time.
- **Wish:** elevation profile of the route (today only totals).
- **Wish:** lane information for bike lanes too.

## On the back burner

- **Elevation profile and route line with OsmAnd navigation** (today only with a GPX
  route). The AIDL interface supplies neither the route geometry nor heights; that needs an
  extension in OsmAnd itself and in the interface.
- **Start TrailBridge automatically when the phone starts.** No need at the moment.
