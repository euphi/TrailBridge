# Veröffentlichung auf F-Droid

Was schon im Repo vorbereitet ist:

| Was | Wo |
|---|---|
| Store-Texte (de/en), Icon, Screenshots, Changelogs | [`fastlane/metadata/android/`](fastlane/metadata/android/) -- F-Droid liest die direkt aus dem Repo |
| Build-Rezept für fdroiddata | [`fdroid/com.euphi.trailbridge.yml`](fdroid/com.euphi.trailbridge.yml) (mit `fdroid lint` geprüft) |
| Gradle-Version für F-Droids Buildserver | `gradle/wrapper/gradle-wrapper.properties` (nur die Versionsangabe, kein Wrapper-JAR) |
| Kein Google-Abhängigkeits-Blob in der APK | `dependenciesInfo` in `app/build.gradle` |
| Lizenz GPL-3.0-or-later | [`LICENSE`](LICENSE) -- nötig, weil die übernommenen `net.osmand.aidlapi`-Dateien aus dem GPLv3-OsmAnd-Repo stammen |
| Check Tag == versionName | Release-Workflow bricht bei Tag-Push ab, wenn die beiden nicht passen |
| Reproducible Build mit eigenem Key | `Binaries:` + `AllowedAPKSigningKeys` im Rezept, CI auf JDK 21, siehe unten |

## Signatur: Reproducible Build mit eigenem Key

F-Droid baut jede Version selbst aus dem Tag nach, lädt unsere signierte
APK aus dem GitHub-Release (`Binaries:` im Rezept) und vergleicht beide.
Sind sie bis auf die Signatur **bytegleich**, veröffentlicht F-Droid
**unsere** APK mit unserer Signatur. F-Droid- und GitHub-APK sind damit
untereinander update-kompatibel. Weicht auch nur ein Byte ab, veröffentlicht
F-Droid diese Version nicht (alte Version bleibt stehen).

- Erlaubter Signaturschlüssel (`AllowedAPKSigningKeys`, SHA-256 des
  Zertifikats "CN=Ian Hubbertz"):
  `2e850b640646af5c76600bd444bc8c80fd1e2f68e88d3cd20bca4432804e4c1f`
- Der Keystore (GitHub-Secrets, siehe BUILD.md) darf **nie verloren gehen**
  und nie wechseln -- ohne ihn keine Updates mehr, weder auf F-Droid noch
  auf GitHub.
- Der Release-Workflow bricht bei Tag-Pushes ohne Keystore ab.
- Damit die Builds gleich bleiben: CI nutzt JDK 21 (= F-Droids Buildserver,
  Debian trixie `default-jdk`) und Gradle 8.14.2 (steht in
  `gradle/wrapper/gradle-wrapper.properties`, das nutzt auch F-Droid). Bei
  Upgrades von JDK, Gradle oder AGP immer beide Seiten mitdenken.

Fingerprint selbst prüfen:
```
apksigner verify --print-certs TrailBridge-v0.4.2.apk | grep SHA-256
# oder direkt am Keystore (Doppelpunkte entfernen, klein schreiben):
keytool -list -v -keystore trailbridge-release.keystore -alias trailbridge | grep SHA256
```

Reproduzierbarkeit lokal testen (optional, braucht `pip install apksigcopier`):
```
git checkout v0.4.2
gradle clean assembleRelease      # ohne ANDROID_KEYSTORE_PATH -> unsigniert
apksigcopier compare TrailBridge-v0.4.2.apk \
  --unsigned app/build/outputs/apk/release/app-release-unsigned.apk && echo reproduzierbar
```
Verbindlich ist aber der Testbuild in der CI des fdroiddata-Forks: Mit
`Binaries:` im Rezept macht `fdroid build` genau diesen Vergleich.

Typische Ursachen, falls der Vergleich scheitert (Diff-Ausgabe im CI-Log
zeigt die abweichende Datei):
- `classes.dex` weicht ab -> unterschiedliche JDK-Hauptversion.
- `assets/dexopt/baseline.prof(m)` weicht ab -> ist seit 0.4.2 schon
  erledigt: `app/build.gradle` erzeugt keine Baseline-Profile mehr.
- `META-INF/version-control-info.textproto` enthält den Commit-Hash; passt,
  solange F-Droid denselben Tag baut.

Jede solche Korrektur braucht eine neue Version (Tag), ein bereits
veröffentlichter Release lässt sich nicht nachträglich ändern.

## Versionsschema

- `versionName` = Git-Tag ohne `v` (Tag `v0.4.2` -> `0.4.2`)
- `versionCode` = `MAJOR*10000 + MINOR*100 + PATCH` (0.4.2 -> 402, 1.2.3 -> 10203)
- Betas (`vX.Y.Z-betaN`): `versionName` = `X.Y.Z-betaN`, `versionCode` =
  Code der kommenden finalen Version − 10 + N (0.5.0-beta1 -> 491).
  F-Droid ignoriert Beta-Tags (`UpdateCheckMode` filtert auf `vX.Y.Z`).

## Einmalig: Erstes Einreichen

1. **Release-Tag setzen** (versionName/versionCode stehen schon auf 0.4.2/402).
   Frühere Tags taugen nicht als erste F-Droid-Version: `v0.4.0` hat noch
   versionCode 1 und die MIT-Lizenz, `v0.4.2` wurde mit JDK 17 gebaut und
   ist deshalb nicht bytegleich zu F-Droids JDK-21-Build.
   ```
   git tag v0.4.2
   git push origin v0.4.2
   ```
2. **Auf GitLab** `https://gitlab.com/fdroid/fdroiddata` forken, neuen Branch
   `com.euphi.trailbridge` anlegen.
3. `fdroid/com.euphi.trailbridge.yml` aus diesem Repo als
   `metadata/com.euphi.trailbridge.yml` in den Fork kopieren. **Keine
   Kommentare** hinzufügen: die fdroiddata-CI prüft, dass die Datei exakt
   so aussieht, wie `fdroid rewritemeta` sie schreibt, und das entfernt
   Kommentare -- jede `#`-Zeile lässt den Job fehlschlagen. Nur finale Tags
   (`vX.Y.Z`) zählen wegen des Regex bei `UpdateCheckMode`.
4. Pushen -- die CI im Fork läuft `fdroid lint` und einen Testbuild. Wenn
   grün: Merge Request gegen `fdroid/fdroiddata` öffnen, Vorlage "App
   inclusion" auswählen und die Checkliste ausfüllen.

   Lokal vorab testen geht auch (braucht Android-SDK):
   ```
   pip install fdroidserver
   cd fdroiddata
   fdroid readmeta && fdroid lint com.euphi.trailbridge
   fdroid build -v -l com.euphi.trailbridge
   ```
5. Review abwarten (typisch Tage bis wenige Wochen). Nach dem Merge dauert
   es noch bis zum nächsten Index-Build (~1-2 Tage), bis die App im Client
   auftaucht.

Mögliche Rückfragen der Reviewer, schon beantwortet:
- **Anti-Features:** keine -- kein Tracking, keine Netzwerkzugriffe, keine
  proprietären Abhängigkeiten (nur `androidx.appcompat`). OsmAnd ist selbst
  in F-Droid (OsmAnd~, Paket `net.osmand.plus`, wird von TrailBridge
  unterstützt).
- **Name/Marke:** "OsmAnd" steht bewusst nicht im App-Namen.

## Bei jedem weiteren Release

1. In `app/build.gradle` `versionName` und `versionCode` nach obigem Schema
   hochzählen.
2. Changelog anlegen:
   `fastlane/metadata/android/{en-US,de}/changelogs/<versionCode>.txt`
   (max. 500 Zeichen).
3. Committen, taggen (`vX.Y.Z`), pushen.

Mehr ist nicht nötig: F-Droids Checkupdates-Bot findet den neuen Tag
(`UpdateCheckMode: Tags`) und legt den Build-Eintrag selbst an
(`AutoUpdateMode: Version`).
