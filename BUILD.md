# Build

## Setup unter Gentoo (Kommandozeile, kein Android Studio)

1. **JDK** (AGP 9.x will JDK 17, auch als Minimum für Gradle 9 selbst):
   ```
   emerge --ask dev-java/openjdk:17
   eselect java-vm set system openjdk-17
   ```
2. **Gradle** (nutzt hier das System-Gradle statt eines Wrappers, spart dir
   den Wrapper-JAR-Download):
   ```
   emerge --ask dev-build/gradle
   gradle -v
   ```
   Dieses Projekt nutzt bewusst **AGP 8.13.0** (neueste 8.x-Version, Stand
   September 2026, braucht **Gradle >= 8.13**) statt AGP 9.x -- damit reicht
   das stabile Gentoo-Paket `dev-java/gradle-bin` (8.14.2), ohne ein
   `~amd64`-Testing-Gradle freischalten zu müssen. Falls deine `gradle -v`
   etwas Älteres als 8.13 zeigt: Gentoo-Gradle-Paket aktualisieren, oder in
   `build.gradle` (root) eine noch ältere, dazu passende AGP-Version eintragen --
   [Kompatibilitätstabelle hier](https://developer.android.com/build/releases/gradle-plugin#updating-gradle).
3. **Android SDK cmdline-tools** (ohne Android Studio):
   ```
   mkdir -p ~/Android/cmdline-tools
   cd ~/Android/cmdline-tools
   # aktuelle URL von https://developer.android.com/studio#command-line-tools-only holen --
   # die Versionsnummer im Dateinamen ändert sich, deshalb hier nicht fest verdrahtet
   wget https://dl.google.com/android/repository/commandlinetools-linux-XXXXXXXX_latest.zip
   unzip commandlinetools-linux-*.zip
   mv cmdline-tools latest
   export ANDROID_HOME=~/Android
   export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
   ```
   (Die beiden `export`-Zeilen dauerhaft in `~/.zshrc` o.ä. eintragen.)
4. **Pakete installieren + Lizenzen akzeptieren:**
   ```
   sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   sdkmanager --licenses
   ```
5. **Bauen:**
   ```
   cd TrailBridge
   gradle assembleDebug
   ```
   APK liegt danach unter `app/build/outputs/apk/debug/app-debug.apk`.
6. **Auf's Handy:**
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Release

[`.github/workflows/release.yml`](.github/workflows/release.yml) baut eine
signierte Release-APK und veröffentlicht sie automatisch als GitHub Release,
sobald ein Tag nach dem Muster `v*` gepusht wird (z.B. `v0.4.0-beta1` --
enthält der Tag `-beta` oder `-rc`, wird der Release automatisch als
"Pre-release" markiert).

### Einmalig einrichten: Signing-Key

Ohne eigenen Signing-Key baut der Workflow trotzdem (unsignierte APK,
brauchbar zum Reinschauen, aber Android verweigert ein Update über eine
schon installierte Version hinweg). Für echte Beta-Releases, die sich später
per Update ersetzen lassen, einmalig ein Schlüsselpaar erzeugen:

```
keytool -genkeypair -v -keystore trailbridge-release.keystore \
  -alias trailbridge -keyalg RSA -keysize 2048 -validity 10000
```

**Wichtig:** Diese Datei (und die dabei vergebenen Passwörter) niemals ins
Repo committen oder verlieren -- ohne sie lassen sich später keine Updates
mehr signieren, die Android als "derselbe Absender" akzeptiert. Sicher
aufbewahren (Passwort-Manager, Backup).

Dann im GitHub-Repo unter **Settings > Secrets and variables > Actions >
New repository secret** vier Secrets anlegen:

| Secret | Wert |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 trailbridge-release.keystore` (Ausgabe reinkopieren) |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore-Passwort von oben |
| `ANDROID_KEY_ALIAS` | `trailbridge` (oder was du oben als `-alias` gewählt hast) |
| `ANDROID_KEY_PASSWORD` | Key-Passwort (bei `keytool` ohne `-keypass` gleich dem Keystore-Passwort) |

### Release auslösen

```
git tag v0.4.1
git push origin v0.4.1
```

Der Tag muss zu `versionName` in `app/build.gradle` passen (`v` + versionName),
sonst bricht der Workflow ab -- F-Droid baut aus denselben Tags, siehe
[FDROID.md](FDROID.md).

Oder ohne Tag zum Testen: im GitHub-Repo unter **Actions > Release APK > Run
workflow** manuell anstoßen (Versions-Tag als Eingabefeld).
