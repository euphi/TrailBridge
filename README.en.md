<p align="center"><img src="logo.svg" width="120" alt="TrailBridge logo"></p>

# TrailBridge

*English translation of [README.md](README.md). The German file is the authoritative version; change both together.*

Android companion app for the
[TRGB-BikeComputer](https://github.com/euphi/TRGB-BikeComputer) (ESP32): passes
navigation from OsmAnd or a GPX route, the GPS position and the elevation profile of
the route ahead on to the bike computer over BLE.

> ## 📖 Manual, usage and screenshots: <https://euphi.github.io/TRGB-BikeComputer/trailbridge/>
>
> The detailed documentation is **not in this repo but on github.io**, together with that of
> the BikeComputer ([Deutsch](https://euphi.github.io/TRGB-BikeComputer/de/trailbridge/)).
> This README is only the overview for developers.

<p align="center">
  <img src="fastlane/metadata/android/de/images/phoneScreenshots/1_main.png" height="480" alt="TrailBridge: main screen (German UI)">
  <img src="fastlane/metadata/android/de/images/phoneScreenshots/2_navi.png" height="480" alt="TrailBridge: Navi mode with elevation profile (German UI)">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2_bikecomputer.jpg" height="480" alt="TRGB-BikeComputer showing the forwarded turn maneuver">
</p>

## What TrailBridge does

- **Forward OsmAnd navigation:** reads it through the AIDL API (maneuver, street names, the
  maneuver after next, lanes, remaining distance/time) and sends it to the BikeComputer over
  BLE as a compact TLV protocol -- [PROTOCOL.en.md](PROTOCOL.en.md)
  ([Deutsch](PROTOCOL.md)).
- **GPS position:** a BLE service of its own with the position straight from the GPS chip,
  also without OsmAnd; sets the BikeComputer's clock.
- **GPX routes:** open, share or load one, "Start route" -- TrailBridge navigates by itself,
  without OsmAnd. Plus the **elevation profile** of the route ahead (with climbs announced) and a
  **route overview** (destination, waypoints, climbs) as BLE services of their own.
- **Planned routes:** automatically fetches the route marked in your own BikeLog service
  (BikeComputer project), loads it, optionally puts it into OsmAnd's track list and starts it
  there directly with "Start in OsmAnd". The only function that uses the network
  (`INTERNET`); without an address it stays off.
- **Test ride:** plays the loaded GPX as a fake position with simulated sensor values (always
  flagged as "simulated") -- for testing without a ride.
- **Navi mode and mode selection:** a reduced screen for the road; the main screen shows
  only what the chosen mode needs.

## Status

OsmAnd navigation and the GPS position have been proven on real rides. What came with which
version and how far it has been tested is in the [roadmap](ROADMAP.en.md) and the
[release notes](RELEASE-NOTES-0.5.1.md); APKs under
[Releases](https://github.com/euphi/TrailBridge/releases).

## For developers

| | |
|---|---|
| Building and release | [BUILD.en.md](BUILD.en.md), F-Droid: [FDROID.en.md](FDROID.en.md) |
| BLE protocol (binding for app and firmware) | [PROTOCOL.en.md](PROTOCOL.en.md) |
| Firmware side of the route overview | [FIRMWARE-OVERVIEW.en.md](FIRMWARE-OVERVIEW.en.md) |
| Design (Rim & Ridge, like the BikeComputer) | [DESIGN.en.md](DESIGN.en.md) |
| Open and planned | [ROADMAP.en.md](ROADMAP.en.md) |
| Analyses | [BROUTER-VOICEHINTS.en.md](BROUTER-VOICEHINTS.en.md), [LANE-COUNT-BUG.en.md](LANE-COUNT-BUG.en.md) |

- **Unit tests:** `gradle testDebugUnitTest` (plain Java without Android dependencies;
  fetching planned routes runs against a small HTTP server in the test).
- **AIDL:** the files under `app/src/main/aidl/` and `app/src/main/java/net/osmand/aidlapi/`
  are taken over 1:1 from OsmAnd's [AIDL API](https://github.com/osmandapp/OsmAnd/tree/master/OsmAnd-api).
- **Without a BikeComputer** the BLE part can be checked with any BLE scanner app, e.g.
  [nRF Connect](https://www.nordicsemi.com/Products/Development-tools/nrf-connect-for-mobile);
  `adb logcat -s OsmAndLink -s BikeGatt` shows the data as a log.

## License

Copyright (C) 2026 Ian

TrailBridge is licensed under the [GNU General Public License v3.0 or later](LICENSE)
(GPL-3.0-or-later). The adopted OsmAnd AIDL files (`net.osmand.aidlapi.*`) come from the
GPLv3-licensed [OsmAnd repo](https://github.com/osmandapp/OsmAnd) (© OsmAnd BV).

The bundled fonts (Big Shoulders Display, IBM Plex Sans/Mono) are under the SIL Open Font
License 1.1, not under the GPL -- origin and license texts in [fonts/](fonts/README.md).
