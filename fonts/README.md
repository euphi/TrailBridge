# Schriften der App

Die App bündelt fünf Schriften unter `app/src/main/res/font/`. Alle stehen unter der
**SIL Open Font License 1.1** (frei im Sinne von FOSS; F-Droid-konform). Die
Lizenztexte liegen in diesem Ordner und müssen beim Weitergeben der Schriften
mitgeliefert werden:

| Datei in `res/font/` | Schrift | Copyright | Lizenztext |
|---|---|---|---|
| `big_shoulders_display_bold.ttf` | Big Shoulders Display Bold, v2.002 | © 2019 The Big Shoulders Project Authors | [OFL-BigShoulders.txt](OFL-BigShoulders.txt) |
| `ibm_plex_sans_medium.ttf`, `ibm_plex_sans_semibold.ttf` | IBM Plex Sans Medium / SemiBold, v3.005 | © 2018 IBM Corp. | [OFL-IBMPlex.txt](OFL-IBMPlex.txt) |
| `ibm_plex_mono_regular.ttf`, `ibm_plex_mono_medium.ttf` | IBM Plex Mono Regular / Medium, v2.005 | © 2017 IBM Corp. | [OFL-IBMPlex.txt](OFL-IBMPlex.txt) |

**Unverändert:** Die Dateien sind byte-identisch mit den Upstream-Releases, nicht
gekürzt oder umkonvertiert. Das ist Absicht: "Plex" ist ein *Reserved Font Name*
(siehe Kopfzeile in `OFL-IBMPlex.txt`), eine geänderte Version -- auch ein bloßes
Subset -- dürfte den Namen nicht mehr tragen.

Quellen:

- Big Shoulders: <https://github.com/xotypeco/big_shoulders>
  (`Big-Shoulders/fonts/ttf/BigShouldersDisplay-Bold.ttf`, Lizenz `OFL.txt`)
- IBM Plex: <https://github.com/IBM/plex>
  (`packages/plex-sans|plex-mono/fonts/complete/ttf/`, Lizenz `packages/plex-*/LICENSE.txt`;
  die Texte beider Pakete sind identisch)

SHA-256 der eingecheckten Dateien:

```
cbfe03f89e2bb3aba487644cdaf9abc9b3eb59946efaf894dda5cd05e1690c83  big_shoulders_display_bold.ttf
331c8639d7598b2cde62a911a71db195e30cb655cd6bdf2e324a7e984955f907  ibm_plex_sans_medium.ttf
a20caf8286023a6a7a85e40b1d2a4ae9fc3e3b1f9eda8f4c542dd4986af67bb1  ibm_plex_sans_semibold.ttf
7c6fbddca4b700be918f5f6183d9bd4464fa427fe435f0b480d77fe2bb8c5a43  ibm_plex_mono_regular.ttf
98fbd727aae340b236955879dabed4d991aac9e8e90b3b2a67ce4a59221cc97c  ibm_plex_mono_medium.ttf
```

Die Schriften sind kein Teil des GPL-Codes der App, sondern eigenständige Werke unter
ihrer eigenen Lizenz (bloße Zusammenstellung); die [LICENSE](../LICENSE) der App
bleibt GPL-3.0-or-later.
