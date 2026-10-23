# TrailBridge -- Design (Rim & Ridge for the phone)

*English translation of [DESIGN.md](DESIGN.md). The German file is the authoritative version; change both together.*

TrailBridge carries the same visual identity as the
[TRGB-BikeComputer](https://github.com/euphi/TRGB-BikeComputer): **Rim & Ridge** --
anthracite, one accent tone (brass), thin lines, three typefaces by role. Authoritative is
its `doc/design/rim-ridge-design-system.md`; this document only describes how the system
was translated to a **rectangular phone display**. In case of conflict the original wins
(do not "improve" color values).

The model for the translation to rectangular displays is the BikeComputer's web dashboard
(`data/site/stylesheet.css`): the same tokens, cards with a brass frame, headers in Plex
Mono. From the device display comes the **ring with a gap** (speed or distance to the
maneuver).

## Colors

All as `rr_*` in [`colors.xml`](app/src/main/res/values/colors.xml), values 1:1 from the
design system §1.

| Token | Hex | Use on the phone |
|---|---|---|
| `rr_background` | `#161B1F` | window, launcher background |
| `rr_panel_bg` | `#1E252B` | cards, pill surface |
| `rr_tour_bg` | `#282019` | inset fields (elevation profile), chip, pressed pill |
| `rr_brass` | `#CBA36B` | accent: card headers, ring fill, primary action, icons |
| `rr_brass_dim` | `#CBA36B` @ 32 % | card frames (`--rr-brass-dim` of the dashboard) |
| `rr_parchment_bright` | `#F3ECDF` | hero numbers (speed, distance), street name, bridge in the logo |
| `rr_parchment` | `#E7E2D6` | body text, values |
| `rr_muted` | `#9BA097` | units, labels, disabled text |
| `rr_sage` | `#7FA08F` | "ridge": elevation profile, height icon |
| `rr_zone_green/yellow/red` | `#6FA98C` `#D7B463` `#C1604A` | **only** state dots (connected / waiting / error) |
| `rr_console_bg` | `#12161A` | raw-data fields (`.console` of the dashboard) |

Color carries information only where it has a real function -- otherwise it stays brass on
anthracite. There is deliberately only the one dark theme (the BikeComputer has no day
variant either); `Theme.TrailBridge` in
[`styles.xml`](app/src/main/res/values/styles.xml).

## Typefaces

| Role | Font | Style in `styles.xml` |
|---|---|---|
| Hero/secondary numbers, titles | Big Shoulders Display Bold | `TB.Number`, `TB.StatValue` |
| Body text, buttons | IBM Plex Sans Medium / SemiBold | `TB.Body`, `TB.Button` |
| Card headers, units, raw data | IBM Plex Mono Regular / Medium | `TB.Eyebrow`, `TB.Caption`, `TB.Console` |

Never mix by taste. The files are in [`res/font/`](app/src/main/res/font/) as TTF,
**unchanged** from the upstream releases (no subset: "Plex" is a Reserved Font Name of the
OFL). They cover umlauts, `ß`, `« » … ° ±`, **no arrows** (`→ ←`) -- use vector icons for
those.

License: SIL Open Font License 1.1. Origin, versions, copyright lines, checksums and the
license texts are in [`fonts/`](fonts/README.md); when exchanging or adding a typeface,
maintain them there.

## Components

| Component | Implementation | Origin |
|---|---|---|
| Card | `TB.Card` + `bg_card.xml`: `PANEL_BG`, frame brass 32 %, r=16 dp | `.container` of the dashboard |
| Card header | `TB.Eyebrow`: Plex Mono 11 sp, 0.16 em letter-spaced, capitals, brass | `.eyebrow` of the dashboard |
| Pill (standard) | `TB.Button` + `btn_ghost.xml`: `PANEL_BG`, frame brass ~90 %; pressed frame full + `TOUR_BG`; disabled frame ~40 %, text `MUTED` | design system §6 "Settings" |
| Pill (primary action) | `TB.Button.Primary` + `btn_primary.xml`: brass filled, dark text | filled mode chip / `.btn` of the dashboard |
| Ring with a gap | [`GappedArcView`](app/src/main/java/com/euphi/trailbridge/GappedArcView.java): 270°, gap at the bottom, start bottom left, round caps, thin bezel ring outside; proportions as on the 480 px display, ring a little stronger | design system §4 "Gapped Arc" |
| Stat group | `TB.StatRow`: line icon + Big Shoulders value + Plex Mono unit | design system §4 "Stat group" |
| State dot | `status_dot.xml` (8 dp), zone color through `backgroundTint` | log-level badges of the dashboard |
| Chip | `bg_chip.xml` (`TOUR_BG`, brass 35 %) -- shows "Simulated" | mode chip |
| Elevation profile | [`RouteProfileView`](app/src/main/java/com/euphi/trailbridge/RouteProfileView.java): terrain sage with gradient, maneuvers brass, rider parchment + brass dot | the "ridge" motif |
| Background | `bg_window.xml`: anthracite + `bg_ridge.xml` (ridge line and two hills at the bottom) | `photos/bg_ridge.svg` of the dashboard |

**Ring meaning** as on the device: Position card = speed (`SPEED_ARC_MAX_KMH` = 50 km/h =
full ring), Navigation card = distance to the maneuver, the ring fills as you approach
(from `NAV_ARC_MAX_M` = 500 m).

**Icons** (`ic_heart`, `ic_cadence`, `ic_height`, `ic_gps`, `ic_nav_arrow`) are the
constructions from design system §5, implemented as VectorDrawables with a fixed
brass/sage color. Minimum size **24 dp** (the "icon size" lesson of the design system:
details blur below that). The direction arrow of the Navigation card is a single arrow
pointing up, which the activity rotates per maneuver (`MainActivity.arrowAngle`) -- the
angled turn icons of the BikeComputer are deliberately not here; roundabout, start and
destination show only the text.

## Layout of the main screen

From top to bottom, in the order in which they are needed:

1. **Header** -- bridge mark, "TrailBridge" (Big Shoulders), subtitle.
2. **Connection** -- OsmAnd and BLE status with a state dot, "Try again".
3. **Route** -- load GPX / start route (primary action), at the top right the switch for
   the test ride.
4. **Test ride** (only when opened) -- elevation profile for scrubbing, play/stop,
   maneuver forward/back.
5. **Position** -- speed ring, next to it height, heart rate, cadence; at the top right GPS
   accuracy and possibly the chip "Simulated".
6. **Navigation** -- distance ring with direction arrow, maneuver, street, "then",
   remaining distance/time.
7. **Raw data** -- the former text output (`NavState`/`PositionState.toString()`), demoted
   to a console.

State dots: **BLE** green = BikeComputer subscribed, yellow = advertising without a
subscriber, red = error. **OsmAnd** is derived from the status text of `OsmAndLink`
(`MainActivity.osmandDotColor`) -- purely cosmetic, an unknown text leaves the dot gray. If
these texts change, follow up here.

## Navi mode

A second screen ([`NaviActivity`](app/src/main/java/com/euphi/trailbridge/NaviActivity.java),
entered through the brass button "Navi mode" in the header of the main screen, back through
"Close" or the back gesture): only what you need on the road. The screen stays on while it
is in front. Route or test ride are started beforehand on the main screen -- Navi mode has
no controls for that.

1. **Header** -- bridge mark, "Navi", BLE dot (the same as on the main screen: is the
   BikeComputer connected?), "Close".
2. **Navigation** and 3. **Position** -- exactly the same cards as on the main screen
   (`card_navigation.xml` / `card_position.xml`, bound through
   [`LiveCards`](app/src/main/java/com/euphi/trailbridge/LiveCards.java)). A change to one of
   these cards therefore applies to both screens.
4. **Elevation profile** -- only visible while one is on its way to the BikeComputer
   ([`ClimbProfileView`](app/src/main/java/com/euphi/trailbridge/ClimbProfileView.java)).

The profile is **exactly the `ProfileFrame` that is sent** (PROTOCOL.en.md "Elevation profile
service"), not the whole route: it appears 500 m before the foot of a climb, extends to its
summit and disappears there with `PROFILE_NONE`, in step with the device. The rider stands
in it at `START_REMAINING_DISTANCE_M − REMAINING_DISTANCE_M` (`ProfileFrame.riderOffsetM`,
like the firmware; ±1 grid step of tolerance, otherwise the profile counts as stale and the
card stays off). What has been ridden is dimmed, the stretch ahead is full sage; below the
end is the remaining distance. In the card header: average gradient (`ic_gradient`) and
elevation gain up to the end of the profile, i.e. the summit (`ic_height`) -- each counted
from the rider position.

Navigation and Position sit fixed at the top; the profile card hangs below and does not push
anything else around when it appears or disappears (nothing may jump while riding). Only
portrait is designed -- in landscape the screen scrolls.

Technically: the service reports the profile to the interface through
`UiListener.onProfileChanged` (`TrailBridgeService.publishProfile`, the only way to the GATT
server); since only one activity is registered as `UiListener` at a time,
`removeUiListener` only removes its own entry.

## Launcher icon

Like the device's speed ring: ring with a gap at the bottom (track + ~60 % brass fill)
around the bridge in `PARCHMENT_BRIGHT`. Source [`logo.svg`](logo.svg); Android vector in
`ic_launcher_foreground.xml` (including the single-color `ic_launcher_monochrome.xml` for
themed icons from Android 13), store icon
`fastlane/metadata/android/en-US/images/icon.png` (512 px, rendered from the same SVG).
Checked to be still legible at 48 px.

## Building new UI

- Colors/typefaces/spacings **only** through the `rr_*` tokens and `TB.*` styles, no hex
  values in the layout.
- A new card = `style="@style/TB.Card"` + `TB.Eyebrow` as the first child.
- Numbers in Big Shoulders, units in Plex Mono (`TB.Caption`), button text never in
  capitals.
- Touch targets ≥ 44 dp (exception: the small test-ride switch, 34 dp).
- The app runs edge to edge (`MainActivity.onCreate`): new scroll containers have to take
  the system-bar insets into account themselves.
