# TrailBridge -- Design (Rim & Ridge fürs Handy)

TrailBridge trägt dieselbe visuelle Identität wie der
[TRGB-BikeComputer](https://github.com/euphi/TRGB-BikeComputer): **Rim & Ridge**
-- Anthrazit, ein Akzentton (Messing), dünne Linien, drei Schriften nach Rolle.
Maßgeblich ist dessen `doc/design/rim-ridge-design-system.md`; dieses Dokument
beschreibt nur, wie das System auf ein **rechteckiges Handy-Display** übersetzt
wurde. Bei Widerspruch gilt das Original (Farbwerte nicht "verbessern").

Vorbild für die Übersetzung auf Rechteck-Displays ist das Web-Dashboard des
BikeComputers (`data/site/stylesheet.css`): gleiche Tokens, Karten mit
Messing-Rahmen, Kopfzeilen in Plex Mono. Vom Geräte-Display kommt der **Ring mit
Lücke** (Speed bzw. Distanz zum Manöver).

## Farben

Alle als `rr_*` in [`colors.xml`](app/src/main/res/values/colors.xml), Werte
1:1 aus dem Design-System §1.

| Token | Hex | Verwendung am Handy |
|---|---|---|
| `rr_background` | `#161B1F` | Fenster, Launcher-Hintergrund |
| `rr_panel_bg` | `#1E252B` | Karten, Pillen-Fläche |
| `rr_tour_bg` | `#282019` | eingelassene Felder (Höhenprofil), Chip, gedrückte Pille |
| `rr_brass` | `#CBA36B` | Akzent: Kartenköpfe, Ring-Füllung, Hauptaktion, Icons |
| `rr_brass_dim` | `#CBA36B` @ 32 % | Kartenrahmen (`--rr-brass-dim` des Dashboards) |
| `rr_parchment_bright` | `#F3ECDF` | Hero-Zahlen (Speed, Distanz), Straßenname, Brücke im Logo |
| `rr_parchment` | `#E7E2D6` | Fließtext, Werte |
| `rr_muted` | `#9BA097` | Einheiten, Beschriftungen, deaktivierte Texte |
| `rr_sage` | `#7FA08F` | "Ridge": Höhenprofil, Höhen-Icon |
| `rr_zone_green/yellow/red` | `#6FA98C` `#D7B463` `#C1604A` | **nur** Zustandspunkte (verbunden / wartet / Fehler) |
| `rr_console_bg` | `#12161A` | Rohdaten-Felder (`.console` des Dashboards) |

Farbe trägt nur dort Information, wo sie echte Funktion hat -- sonst bleibt es
bei Messing auf Anthrazit. Es gibt bewusst nur das eine dunkle Theme (der
BikeComputer hat auch keine Tag-Variante); `Theme.TrailBridge` in
[`styles.xml`](app/src/main/res/values/styles.xml).

## Schriften

| Rolle | Font | Stil in `styles.xml` |
|---|---|---|
| Hero-/Sekundärzahlen, Titel | Big Shoulders Display Bold | `TB.Number`, `TB.StatValue` |
| Fließtext, Tasten | IBM Plex Sans Medium / SemiBold | `TB.Body`, `TB.Button` |
| Kartenköpfe, Einheiten, Rohdaten | IBM Plex Mono Regular / Medium | `TB.Eyebrow`, `TB.Caption`, `TB.Console` |

Nie nach Geschmack mischen. Die Dateien liegen als TTF unter
[`res/font/`](app/src/main/res/font/): dieselben fünf Latin-Subsets, die schon
das Web-Dashboard des BikeComputers ausliefert (dort als WOFF2, hier mit
`fontTools` nach TTF gewandelt, da Android kein WOFF2 lädt). Sie decken
Umlaute, `ß`, `« » … ° ±` ab, **keine Pfeile** (`→ ←`) -- dafür Vektor-Icons nehmen.

Lizenz der Schriften: SIL Open Font License 1.1.

- Big Shoulders Display -- Copyright 2019 The Big Shoulders Project Authors
  (https://github.com/xotypeco/big_shoulders)
- IBM Plex Sans (2019) / IBM Plex Mono (2017) -- Copyright IBM Corp.
  (https://github.com/IBM/plex)

## Komponenten

| Komponente | Umsetzung | Herkunft |
|---|---|---|
| Karte | `TB.Card` + `bg_card.xml`: `PANEL_BG`, Rahmen Messing 32 %, r=16 dp | `.container` des Dashboards |
| Kartenkopf | `TB.Eyebrow`: Plex Mono 11 sp, 0,16 em gesperrt, Versalien, Messing | `.eyebrow` des Dashboards |
| Pille (Standard) | `TB.Button` + `btn_ghost.xml`: `PANEL_BG`, Rahmen Messing ~90 %; gedrückt Rahmen voll + `TOUR_BG`; deaktiviert Rahmen ~40 %, Text `MUTED` | Design-System §6 "Einstellungen" |
| Pille (Hauptaktion) | `TB.Button.Primary` + `btn_primary.xml`: Messing gefüllt, dunkle Schrift | gefüllter Modus-Chip / `.btn` des Dashboards |
| Ring mit Lücke | [`GappedArcView`](app/src/main/java/com/euphi/trailbridge/GappedArcView.java): 270°, Lücke unten, Start unten links, Rundkappen, dünner Bezel-Ring außen; Proportionen wie am 480-px-Display, Ring etwas kräftiger | Design-System §4 "Gapped Arc" |
| Stat-Gruppe | `TB.StatRow`: Linien-Icon + Big-Shoulders-Wert + Plex-Mono-Einheit | Design-System §4 "Stat-Gruppe" |
| Zustandspunkt | `status_dot.xml` (8 dp), per `backgroundTint` in Zonenfarbe | Log-Level-Badges des Dashboards |
| Chip | `bg_chip.xml` (`TOUR_BG`, Messing 35 %) -- zeigt "Simuliert" | Modus-Chip |
| Höhenprofil | [`RouteProfileView`](app/src/main/java/com/euphi/trailbridge/RouteProfileView.java): Gelände Sage mit Verlauf, Manöver Messing, Fahrer parchment + Messing-Punkt | das "Ridge"-Motiv |
| Hintergrund | `bg_window.xml`: Anthrazit + `bg_ridge.xml` (Ridge-Linie und zwei Hügel unten) | `photos/bg_ridge.svg` des Dashboards |

**Ring-Bedeutung** wie am Gerät: Position-Karte = Geschwindigkeit
(`SPEED_ARC_MAX_KMH` = 50 km/h = voller Ring), Navigations-Karte = Distanz zum
Manöver, der Ring füllt sich beim Näherkommen (ab `NAV_ARC_MAX_M` = 500 m).

**Icons** (`ic_heart`, `ic_cadence`, `ic_height`, `ic_gps`, `ic_nav_arrow`) sind
die Konstruktionen aus Design-System §5, umgesetzt als VectorDrawables mit fester
Messing-/Sage-Farbe. Mindestgröße **24 dp** (Lektion "Icon-Größe" des
Design-Systems: darunter verschwimmen Details). Der Richtungspfeil der
Navigations-Karte ist ein einziger Pfeil nach oben, den die Activity je Manöver
dreht (`MainActivity.arrowAngle`) -- die gewinkelten Abbiege-Icons des
BikeComputers gibt es hier bewusst nicht; Kreisverkehr, Start und Ziel zeigen nur
den Text.

## Aufbau des Hauptscreens

Von oben nach unten, in der Reihenfolge, in der man sie braucht:

1. **Kopfzeile** -- Brücken-Marke, "TrailBridge" (Big Shoulders), Untertitel.
2. **Verbindung** -- OsmAnd- und BLE-Status mit Zustandspunkt, "Erneut versuchen".
3. **Route** -- GPX laden / Route starten (Hauptaktion), oben rechts der Schalter
   für die Testfahrt.
4. **Testfahrt** (nur aufgeklappt) -- Höhenprofil zum Scrubben, Abspielen/Stopp,
   Manöver vor/zurück.
5. **Position** -- Speed-Ring, daneben Höhe, Puls, Trittfrequenz; oben rechts
   GPS-Genauigkeit und ggf. der Chip "Simuliert".
6. **Navigation** -- Distanz-Ring mit Richtungspfeil, Manöver, Straße, "danach",
   Restdistanz/-zeit.
7. **Rohdaten** -- die frühere Textausgabe (`NavState`/`PositionState.toString()`),
   als Konsole zurückgestuft.

Zustandspunkte: **BLE** grün = BikeComputer abonniert, gelb = Advertising ohne
Abonnent, rot = Fehler. **OsmAnd** wird aus dem Statustext von `OsmAndLink`
abgeleitet (`MainActivity.osmandDotColor`) -- rein kosmetisch, ein unbekannter
Text lässt den Punkt grau. Ändern sich diese Texte, hier mitziehen.

## Navi-Modus

Zweiter Screen ([`NaviActivity`](app/src/main/java/com/euphi/trailbridge/NaviActivity.java),
Einstieg über den Messing-Button "Navi-Modus" in der Kopfzeile des Hauptscreens, zurück
über "Beenden" oder die Zurück-Geste): nur das, was man unterwegs braucht. Der
Bildschirm bleibt an, solange er vorn ist. Route bzw. Testfahrt werden vorher im
Hauptscreen gestartet -- der Navi-Modus hat keine Bedienelemente dafür.

1. **Kopfzeile** -- Brücken-Marke, "Navi", BLE-Punkt (derselbe wie im Hauptscreen:
   ist der BikeComputer verbunden?), "Beenden".
2. **Navigation** und 3. **Position** -- exakt dieselben Karten wie im Hauptscreen
   (`card_navigation.xml` / `card_position.xml`, gebunden über
   [`LiveCards`](app/src/main/java/com/euphi/trailbridge/LiveCards.java)). Eine Änderung
   an einer dieser Karten gilt damit für beide Screens.
4. **Höhenprofil** -- nur sichtbar, solange eines an den BikeComputer unterwegs ist
   ([`ClimbProfileView`](app/src/main/java/com/euphi/trailbridge/ClimbProfileView.java)).

Das Profil ist **genau das gesendete `ProfileFrame`** (PROTOCOL.md "Höhenprofil-Service"),
nicht die ganze Route: Es erscheint, wenn voraus eine Steigung erkannt wird, und
verschwindet mit `PROFILE_NONE`, im Takt mit dem Gerät. Der Fahrer steht darin an
`START_REMAINING_DISTANCE_M − REMAINING_DISTANCE_M` (`ProfileFrame.riderOffsetM`, wie die
Firmware; ±1 Raster-Schritt Toleranz, sonst gilt das Profil als veraltet und die
Karte bleibt aus). Das bereits Gefahrene ist abgedunkelt, das Stück voraus voll in
Salbei; unter dem Ende steht die Restdistanz. In der Kopfzeile der Karte: mittlere
Steigung (`ic_gradient`) und Höhengewinn bis zum Profilende (`ic_height`) -- jeweils ab
der Fahrerposition gerechnet.

Navigation und Position sitzen oben fest; die Profilkarte hängt darunter und schiebt
nichts anderes herum, wenn sie erscheint oder verschwindet (beim Fahren darf nichts
springen). Nur Hochformat gestaltet -- im Querformat scrollt der Screen.

Technisch: Der Dienst meldet das Profil über `UiListener.onProfileChanged` an die
Oberfläche (`TrailBridgeService.publishProfile`, einziger Weg zum GATT-Server); da immer
nur eine Activity als `UiListener` registriert ist, löst `removeUiListener` nur den
eigenen Eintrag.

## Launcher-Icon

Wie der Speed-Ring des Geräts: Ring mit Lücke unten (Spur + ~60 % Messing-Füllung)
um die Brücke in `PARCHMENT_BRIGHT`. Quelle [`logo.svg`](logo.svg); Android-Vektor
in `ic_launcher_foreground.xml` (inkl. einfarbigem `ic_launcher_monochrome.xml` für
Themed Icons ab Android 13), Store-Icon
`fastlane/metadata/android/en-US/images/icon.png` (512 px, aus derselben SVG
gerendert). Bei 48 px noch lesbar geprüft.

## Neue Oberfläche bauen

- Farben/Schriften/Abstände **nur** über die `rr_*`-Tokens und `TB.*`-Stile, keine
  Hex-Werte im Layout.
- Eine neue Karte = `style="@style/TB.Card"` + `TB.Eyebrow` als erstes Kind.
- Zahlen in Big Shoulders, Einheiten in Plex Mono (`TB.Caption`), Tasten-Text nie
  in Versalien.
- Touch-Ziele ≥ 44 dp (Ausnahme: der kleine Testfahrt-Schalter, 34 dp).
- Die App läuft Edge-to-Edge (`MainActivity.onCreate`): neue Scroll-Container müssen
  die System-Leisten-Insets selbst berücksichtigen.
