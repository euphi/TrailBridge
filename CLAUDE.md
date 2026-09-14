# BikeNavRelay -- Projektkontext für Claude Code

Android-Companion-App, die OsmAnds Turn-by-Turn-Navigation per BLE an den
[TRGB-BikeComputer](https://github.com/euphi/TRGB-BikeComputer) (ESP32)
weiterreicht -- als Ersatz für Komoots eingestellten BLE-Navigationsdienst.

**Vor jeder Änderung lesen:** [`README.md`](README.md) (Setup/Build/Test) und
[`PROTOCOL.md`](PROTOCOL.md) (das BLE-Wire-Format -- verbindlicher Vertrag
zwischen App und Firmware, nicht auf eigene Faust ändern).

## Stand

- **Meilenstein 1 (fertig):** `OsmAndLink.java` -- Bindung an OsmAnds AIDL-API
  (`net.osmand.aidl.OsmandAidlServiceV2`), Polling von `getAppInfo()`.
- **Meilenstein 2 (fertig):** `BikeComputerGattServer.java` +
  `NavFrameEncoder.java` -- BLE-GATT-Server (Peripheral-Rolle), TLV-Protokoll
  nach PROTOCOL.md, Indicate statt Poll.
- **Meilenstein 3 (offen):** Firmware-Seite. Das TRGB-BikeComputer-Repo liegt
  NICHT in diesem Ordner -- separat unter
  `https://github.com/euphi/TRGB-BikeComputer` geklont. Dort muss
  `src/BLEDevices.cpp`/`.h` vom alten Komoot-Parser (`komootLoop()`, feste
  Byte-Offsets) auf einen TLV-Parser nach PROTOCOL.md umgestellt werden,
  inklusive Aktivieren von Indicate (Schreiben auf den CCCD-Descriptor --
  im Originalcode auskommentiert) und einer neuen Icon-Tabelle für die
  erweiterten Manöver-Codes aus `Maneuver.java`.

## Verifizierte Fakten, die beim Weiterbauen wichtig sind

- Alle `net.osmand.aidlapi.*`-Dateien unter `app/src/main/{aidl,java}/` sind
  1:1 aus dem offiziellen `osmandapp/OsmAnd`-Repo (Verzeichnis `OsmAnd-api`)
  übernommen -- NICHT als "unser Code" behandeln, nicht ungefragt umschreiben.
  Eine Korrektur war nötig und ist absichtlich: fehlender
  `android.view.KeyEvent`-Import in `IOsmAndAidlCallback.aidl`.
- Die `turnInfo`-Bundle-Keys (`next_turn_type`, `after_nextturn_type` ohne
  Unterstrich, etc.) sind aus OsmAnds `ExternalApiHelper.java` verifiziert,
  siehe Kommentare in `OsmAndLink.java`. Nicht aus Vermutung ändern, sondern
  gegen die OsmAnd-Quelle prüfen (`osmandapp/OsmAnd`, Datei
  `OsmAnd/src/net/osmand/plus/helpers/ExternalApiHelper.java`).
- Rollenverteilung ist bewusst: Handy = BLE-Peripheral/-Server,
  BikeComputer = BLE-Central/-Client (wie bei Komoot). Nicht umdrehen, ohne
  vorher mit mir (dem Nutzer) Rücksprache zu halten -- betrifft auch die
  bestehende Verbindungslogik in `BLEDevices.cpp` für die anderen
  Sensoren (HR, CSC, Forumslader).

## Build

Kein Gradle-Wrapper -- System-Gradle nutzen. Kompletter Ablauf inkl.
Versions-Kompatibilität in README.md. Kurzform, wenn die Umgebung schon
steht:

```bash
cd app  # falls du direkt im BikeNavRelay-Root bist, weglassen
cd ..
gradle assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Programmiersprachen-Präferenz

Java für Android (durch die Plattform vorgegeben -- BLE-Peripheral/GATT-Server
und AIDL-Binding gehen praktisch nur nativ). Für das ESP32-Firmware-Teil
(Meilenstein 3): C++/PlatformIO, wie im bestehenden TRGB-BikeComputer-Repo.
