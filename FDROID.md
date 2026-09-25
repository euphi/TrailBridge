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

Signiert wird mit **F-Droids eigenem Key**. Folge: Die F-Droid-APK und die
APK aus den GitHub-Releases lassen sich **nicht** gegenseitig updaten --
wer wechseln will, muss erst deinstallieren. Am besten so in der
GitHub-Release-Beschreibung erwähnen.

## Versionsschema

- `versionName` = Git-Tag ohne `v` (Tag `v0.4.1` -> `0.4.1`)
- `versionCode` = `MAJOR*10000 + MINOR*100 + PATCH` (0.4.1 -> 401, 1.2.3 -> 10203)
- Betas (`vX.Y.Z-betaN`): `versionName` = `X.Y.Z-betaN`, `versionCode` =
  Code der kommenden finalen Version − 10 + N (0.5.0-beta1 -> 491).
  F-Droid ignoriert Beta-Tags (`UpdateCheckMode` filtert auf `vX.Y.Z`).

## Einmalig: Erstes Einreichen

1. **Release-Tag setzen** (versionName/versionCode stehen schon auf 0.4.1/401).
   `v0.4.0` taugt nicht als erste F-Droid-Version: dort steht
   noch versionCode 1, die MIT-Lizenz und kein `dependenciesInfo`-Block.
   ```
   git tag v0.4.1
   git push origin v0.4.1
   ```
2. **Auf GitLab** `https://gitlab.com/fdroid/fdroiddata` forken, neuen Branch
   `com.euphi.trailbridge` anlegen.
3. `fdroid/com.euphi.trailbridge.yml` aus diesem Repo als
   `metadata/com.euphi.trailbridge.yml` in den Fork kopieren (die
   Kommentarzeilen oben dürfen raus -- `fdroid rewritemeta` entfernt sie
   ohnehin).
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
