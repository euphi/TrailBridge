# Build

*English translation of [BUILD.md](BUILD.md). The German file is the authoritative version; change both together.*

## Setup on Gentoo (command line, no Android Studio)

1. **JDK 21** (the same major version as the release workflow and F-Droid's build server
   -- only then is a local build byte-identical to the release, see
   [FDROID.en.md](FDROID.en.md); JDK 17 is enough for plain debug builds):
   ```
   emerge --ask dev-java/openjdk:21
   eselect java-vm set system openjdk-21
   ```
2. **Gradle** (this uses the system Gradle instead of a wrapper, which saves you the
   wrapper JAR download):
   ```
   emerge --ask dev-build/gradle
   gradle -v
   ```
   This project deliberately uses **AGP 8.13.0** (newest 8.x version as of September 2026,
   needs **Gradle >= 8.13**) instead of AGP 9.x -- so that the stable Gentoo package
   `dev-java/gradle-bin` (8.14.2) is enough, without having to unmask a `~amd64` testing
   Gradle. If your `gradle -v` shows something older than 8.13: update the Gentoo Gradle
   package, or enter an even older, matching AGP version in the root `build.gradle` --
   [compatibility table here](https://developer.android.com/build/releases/gradle-plugin#updating-gradle).
3. **Android SDK cmdline-tools** (without Android Studio):
   ```
   mkdir -p ~/Android/cmdline-tools
   cd ~/Android/cmdline-tools
   # get the current URL from https://developer.android.com/studio#command-line-tools-only --
   # the version number in the file name changes, so it is not hard-wired here
   wget https://dl.google.com/android/repository/commandlinetools-linux-XXXXXXXX_latest.zip
   unzip commandlinetools-linux-*.zip
   mv cmdline-tools latest
   export ANDROID_HOME=~/Android
   export PATH="$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
   ```
   (Put the two `export` lines permanently into `~/.zshrc` or similar.)
4. **Install packages + accept licenses:**
   ```
   sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
   sdkmanager --licenses
   ```
5. **Build:**
   ```
   cd TrailBridge
   gradle assembleDebug
   ```
   The APK is then at `app/build/outputs/apk/debug/app-debug.apk`.
6. **Onto the phone:**
   ```
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

## Release

[`.github/workflows/release.yml`](.github/workflows/release.yml) builds a signed release
APK and publishes it automatically as a GitHub release as soon as a tag matching `v*` is
pushed (e.g. `v0.4.0-beta1` -- if the tag contains `-beta` or `-rc`, the release is
automatically marked as "pre-release").

### One-time setup: signing key

Without a signing key of your own the workflow still builds (an unsigned APK, good for a
look, but Android refuses an update over an already installed version). For real beta
releases that can later be replaced by an update, create a key pair once:

```
keytool -genkeypair -v -keystore trailbridge-release.keystore \
  -alias trailbridge -keyalg RSA -keysize 2048 -validity 10000
```

**Important:** never commit this file (and the passwords chosen for it) to the repo and
never lose it -- without it no updates can be signed later that Android accepts as "the same
sender". Keep it safe (password manager, backup).

Then create four secrets in the GitHub repo under **Settings > Secrets and variables >
Actions > New repository secret**:

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 trailbridge-release.keystore` (paste the output) |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password from above |
| `ANDROID_KEY_ALIAS` | `trailbridge` (or whatever you chose as `-alias` above) |
| `ANDROID_KEY_PASSWORD` | key password (the same as the keystore password if `keytool` ran without `-keypass`) |

### Triggering a release

```
git tag v0.4.1
git push origin v0.4.1
```

The tag must match `versionName` in `app/build.gradle` (`v` + versionName), otherwise the
workflow aborts -- F-Droid builds from the same tags, see [FDROID.en.md](FDROID.en.md).

Or, to test without a tag: start it by hand in the GitHub repo under **Actions > Release
APK > Run workflow** (version tag as the input field).
