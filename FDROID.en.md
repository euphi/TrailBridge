# Publishing on F-Droid

*English translation of [FDROID.md](FDROID.md). The German file is the authoritative version; change both together.*

What is already prepared in the repo:

| What | Where |
|---|---|
| Store texts (de/en), icon, screenshots, changelogs | [`fastlane/metadata/android/`](fastlane/metadata/android/) -- F-Droid reads them straight from the repo |
| Build recipe for fdroiddata | [`fdroid/com.euphi.trailbridge.yml`](fdroid/com.euphi.trailbridge.yml) (checked with `fdroid lint`) |
| Gradle version for F-Droid's build server | `gradle/wrapper/gradle-wrapper.properties` (the version only, no wrapper JAR) |
| No Google dependency blob in the APK | `dependenciesInfo` in `app/build.gradle` |
| License GPL-3.0-or-later | [`LICENSE`](LICENSE) -- needed because the adopted `net.osmand.aidlapi` files come from the GPLv3 OsmAnd repo |
| Check tag == versionName | the release workflow aborts on a tag push if the two do not match |
| Reproducible build with our own key | `Binaries:` + `AllowedAPKSigningKeys` in the recipe, CI on JDK 21, see below |

## Signature: reproducible build with our own key

F-Droid rebuilds every version itself from the tag, downloads our signed APK from the
GitHub release (`Binaries:` in the recipe) and compares the two. If they are
**byte-identical** apart from the signature, F-Droid publishes **our** APK with our
signature. The F-Droid and GitHub APKs are then update-compatible with each other. If even
one byte differs, F-Droid does not publish that version (the old version stays).

- Allowed signing key (`AllowedAPKSigningKeys`, SHA-256 of the certificate
  "CN=Ian Hubbertz"):
  `2e850b640646af5c76600bd444bc8c80fd1e2f68e88d3cd20bca4432804e4c1f`
- The keystore (GitHub secrets, see [BUILD.en.md](BUILD.en.md)) must **never be lost** and
  never change -- without it no more updates, neither on F-Droid nor on GitHub.
- The release workflow aborts on tag pushes without a keystore.
- To keep the builds identical: CI uses JDK 21 (= F-Droid's build server, Debian trixie
  `default-jdk`) and Gradle 8.14.2 (in `gradle/wrapper/gradle-wrapper.properties`, which
  F-Droid uses as well). When upgrading JDK, Gradle or AGP always think of both sides.

Check the fingerprint yourself:
```
apksigner verify --print-certs TrailBridge-v0.4.2.apk | grep SHA-256
# or straight at the keystore (remove the colons, lower case):
keytool -list -v -keystore trailbridge-release.keystore -alias trailbridge | grep SHA256
```

Testing reproducibility locally (optional, needs `pip install apksigcopier`):
```
git checkout v0.4.2
gradle clean assembleRelease      # without ANDROID_KEYSTORE_PATH -> unsigned
apksigcopier compare TrailBridge-v0.4.2.apk \
  --unsigned app/build/outputs/apk/release/app-release-unsigned.apk && echo reproducible
```
What counts, though, is the test build in the CI of the fdroiddata fork: with `Binaries:` in
the recipe, `fdroid build` does exactly this comparison.

Typical causes if the comparison fails (the diff output in the CI log shows the differing
file):
- `classes.dex` differs -> different JDK major version.
- `assets/dexopt/baseline.prof(m)` differs -> done since 0.4.2: `app/build.gradle` no
  longer generates baseline profiles.
- R8 (active since 0.4.3) is deterministic; the rules are in `app/proguard-rules.pro`. New
  classes that are addressed by reflection or via Binder/Parcel class names need a `-keep`
  rule there -- otherwise they are missing in the release build (debug builds run without
  R8 and do not show the problem).
- `META-INF/version-control-info.textproto` contains the commit hash; it matches as long
  as F-Droid builds the same tag.

Every such correction needs a new version (tag); a release that has been published cannot be
changed afterwards.

## Version scheme

- `versionName` = git tag without `v` (tag `v0.4.2` -> `0.4.2`)
- `versionCode` = `MAJOR*10000 + MINOR*100 + PATCH` (0.4.2 -> 402, 1.2.3 -> 10203)
- Betas (`vX.Y.Z-betaN`): `versionName` = `X.Y.Z-betaN`, `versionCode` = code of the
  coming final version − 10 + N (0.5.0-beta1 -> 491). F-Droid ignores beta tags
  (`UpdateCheckMode` filters for `vX.Y.Z`).
- Release candidates for patch versions (`vX.Y.Z-rcN`): `versionName` = `X.Y.Z-rcN`,
  `versionCode` = code of the coming final version (0.4.4-rc1 -> 404). The beta formula
  would give 395 here and so be below 0.4.3 (403) -- it could not be installed over it. The
  final version keeps the same code; Android allows the update with an equal `versionCode`,
  F-Droid only sees the final tag.

## One time: first submission

1. **Set the release tag** (versionName/versionCode are already at 0.4.2/402). Earlier tags
   do not work as the first F-Droid version: `v0.4.0` still has versionCode 1 and the MIT
   license, `v0.4.1` was built with JDK 17 and is therefore not byte-identical to F-Droid's
   JDK 21 build.
   ```
   git tag v0.4.2
   git push origin v0.4.2
   ```
2. **On GitLab** fork `https://gitlab.com/fdroid/fdroiddata`, create a new branch
   `com.euphi.trailbridge`.
3. Copy `fdroid/com.euphi.trailbridge.yml` from this repo to
   `metadata/com.euphi.trailbridge.yml` in the fork. **Add no comments**: the fdroiddata CI
   checks that the file looks exactly as `fdroid rewritemeta` writes it, and that removes
   comments -- every `#` line makes the job fail. Only final tags (`vX.Y.Z`) count because
   of the regex at `UpdateCheckMode`. For `commit:` the F-Droid maintainers require the
   **full commit hash**, not the tag (`git rev-parse vX.Y.Z^{commit}`).
4. Push -- the CI in the fork runs `fdroid lint` and a test build. If green: open a merge
   request against `fdroid/fdroiddata`, pick the "App inclusion" template and fill in the
   checklist.

   Testing beforehand locally works too (needs the Android SDK):
   ```
   pip install fdroidserver
   cd fdroiddata
   fdroid readmeta && fdroid lint com.euphi.trailbridge
   fdroid build -v -l com.euphi.trailbridge
   ```
5. Wait for the review (typically days to a few weeks). After the merge it takes until the
   next index build (~1-2 days) before the app shows up in the client.

Possible questions from the reviewers, already answered:
- **Anti-features:** none -- no tracking, no proprietary dependencies (only
  `androidx.appcompat`). The network (`INTERNET`) is used only by the fetching of planned
  routes and only if the user enters the address of their own BikeLog service; without it
  nothing leaves the phone. The service belongs to the BikeComputer project and is run by
  the user themselves, not a third party. OsmAnd itself is on F-Droid (OsmAnd~, package
  `net.osmand.plus`, supported by TrailBridge).
- **Name/trademark:** "OsmAnd" is deliberately not in the app name.

## With every further release

1. Count up `versionName` and `versionCode` in `app/build.gradle` according to the scheme
   above.
2. Create a changelog:
   `fastlane/metadata/android/{en-US,de}/changelogs/<versionCode>.txt` (max. 500
   characters).
3. Commit, tag (`vX.Y.Z`), push.

That is all: F-Droid's checkupdates bot finds the new tag (`UpdateCheckMode: Tags`) and
creates the build entry itself (`AutoUpdateMode: Version`).
