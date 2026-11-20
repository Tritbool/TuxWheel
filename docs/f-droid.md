# F-Droid preparation and submission

TuxWheel is being prepared for F-Droid; it is **not yet listed**.
This follows the English/French Fastlane layout used in
[EUC SoH](https://github.com/Tritbool/euc_soh_kotlin).
See the [submission guide](https://f-droid.org/fr/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
and [metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/).

F-Droid and other independently signed builds must be built with
`-PenableGithubUpdates=false` (see [privacy.md](privacy.md) for details).

## Store listing

`fastlane/metadata/android/en-US/` is the fallback listing; `fr-FR/` contains
the French translation. Each has a title, short description, HTML full
description and `changelogs/1.txt`, matching the current `versionCode 1`.
The English listing reuses the existing app icon at `images/icon.png`;
other locales can fall back to it. Fastlane itself is not needed.

Keep titles within 50 characters, summaries within 80, full descriptions
within 4,000, and release notes within 500. Add
`changelogs/<versionCode>.txt` in each locale for every release.

## Build and licensing checks before submission

- Run the development tasks from [development.md](development.md) from a clean
  checkout with no local Maven artifacts, then test BLE connection and foreground
  logging on a real device.
- `:app:assembleRelease` produces
  `app/build/outputs/apk/release/app-release-unsigned.apk` without signing
  credentials. F-Droid can build and sign it independently; the GitHub release
  workflow's signing secrets and the shared debug keystore are not required.
- Build timestamps have been removed from generated code and version labels.
  This removes one source of nondeterminism, but does **not** establish
  byte-for-byte reproducibility. Do not configure upstream binary verification
  until it has been tested with the same toolchain and build inputs.
- The unused Samsung Accessory SDK JARs have been removed. Do not reintroduce
  proprietary SDKs or prebuilt libraries without corresponding freely licensed
  source. The Gradle wrapper JAR is build tooling, not an app dependency.
- Review the complete release dependency graph and retain required license
  notices. The GPL-3.0 project license does not replace third-party licenses.
  Packaging now merges bundled `META-INF/LICENSE*`, `META-INF/NOTICE*` and
  `META-INF/ASL2.0` resources instead of discarding them; inspect the release APK
  and complete the dependency inventory as part of the license review.
  The verified licenses and asset sources are listed below.

## Verified licenses and F-Droid policy

The [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
requires freely licensed source and dependencies, a free build toolchain and
a working build. It does **not** require removing functional FOSS libraries,
BLE permissions, storage access or foreground services. It explicitly permits
freely licensed binaries from trusted repositories including JitPack.

- **Inherited WheelLog assets:** the maintainer confirms that the inherited
  fonts/theme artwork come from GPL-3.0 WheelLog Android. Its
  [GPL license](https://github.com/Wheellog/Wheellog.Android/blob/f44e39e5388e500a5d2f315d1dcf32f167607bf8/LICENSE)
  covers the project except components with their own license.
  The bundled font matches
  [WheelLog's font files](https://github.com/Wheellog/Wheellog.Android/tree/f44e39e5388e500a5d2f315d1dcf32f167607bf8/app/src/main/res/font)
  byte-for-byte (Git blob IDs are recorded in the packaged notice).
  `prime_regular.otf` is the inherited Wheellog font, not an identified
  proprietary dependency.
- **EUC_BLE_LIBRARY 0.1.0:** the maintainer's own open-source library, with a
  [GPL-3.0 license at the pinned tag](https://github.com/Tritbool/euc_ble_library/blob/0.1.0/LICENSE).
  Its [publication configuration](https://github.com/Tritbool/euc_ble_library/blob/0.1.0/euc-ble-core/build.gradle.kts)
  still labels the POM license as MIT. Both licenses are FOSS, but that upstream
  metadata should be aligned with the intended license; this app retains the
  tagged GPL notice rather than assuming that the POM relicenses the source.
- **MPAndroidChart v3.1.0:** the
  [tagged Apache-2.0 license](https://github.com/PhilJay/MPAndroidChart/blob/v3.1.0/LICENSE)
  permits its use here. Obtaining it from JitPack is not by itself an
  F-Droid incompatibility.
- **AndroidX legacy-support/Media:** FOSS dependencies, not prohibited SDKs.
  The umbrella dependency currently supplies the Media classes used by
  hardware-volume-key controls. Keeping it is compliant; replacing it with a
  direct Media dependency is optional dependency cleanup, not submission work.

Attribution and source references are bundled in
`app/src/main/assets/licenses/NOTICE.txt`. GPL text is in the repository's
`LICENSE` and bundled as `app/src/main/assets/licenses/GPL-3.0.txt`.
No font, chart or BLE replacement is needed on the basis of these licenses.

The location permission is limited to Android 6–11 for BLE discovery, not GPS
recording. Storage permissions support CSV logging on older Android, and the
foreground service/notification and battery-optimization option support
background logging. None is an F-Droid incompatibility; they remain in place.

The earlier cleanup removed unused proprietary Samsung SDK binaries, unrelated
WheelLog consent, unsolicited debug HTML files and dead feature remnants.
No runtime analytics SDK, account service or automatic uploader was found.
Automatic app backup is disabled. F-Droid builds must disable GitHub updates
and Internet permission with the distribution property above.

The remaining verification is operational: a clean release build and F-Droid's
dependency/scanner checks on the tagged source. The local AGP resolution failure
prevents checking the resolved transitive graph and final APK here; it is not
evidence that the libraries or permissions above are non-free.
Byte-for-byte matching to an upstream APK is needed if opting into upstream
binary verification, not a prerequisite for ordinary F-Droid-built/signed APKs.

## Release and external submission

1. Verify the release build and F-Droid scanner checks, and add real screenshots
   below for the listing. Metadata alone is not F-Droid approval.
2. Publish a **new** release after these changes are merged. The existing
   `0.0.1` tag predates this preparation and will not expose this metadata.
   For every release, increment `versionCode` and `versionName` together, add the
   matching localized changelogs, and tag that exact commit with `versionName`
   (an optional `v` prefix is supported). Use numeric stable versions such as
   `1.2.3` and attach the upstream-signed APK to the GitHub release. Drafts,
   prereleases and nonnumeric versions are not offered by the updater.
   Do not move an existing tag.
3. In a separate fork of [fdroiddata](https://gitlab.com/fdroid/fdroiddata),
   prepare `metadata/io.github.tuxwheel.yml`: use this repository as `Repo`,
   `RepoType: git`, the confirmed GPL-3.0 license identifier, `subdir: app`,
   `gradle: [yes]`, and the new release tag as the build `commit`. Match the
   release's version name/code and select a build environment supporting the
   checked-in Gradle/JDK/Android SDK requirements. Configure tag-based update
   checks to match the project's release-tag convention. Ensure every F-Droid
   build passes `-PenableGithubUpdates=false`, including sideloadable APKs.
4. In that fdroiddata checkout, run `fdroid readmeta`, `fdroid rewritemeta
   io.github.tuxwheel`, `fdroid lint io.github.tuxwheel`, and
   `fdroid build --verbose io.github.tuxwheel`. Resolve scanner/build findings
   before opening the metadata merge request. Include the source, issue tracker
   and changelog links, and explicitly state that you are the upstream author.
5. Submit the merge request to fdroiddata, or open a
   [Request for Packaging](https://gitlab.com/fdroid/rfp/-/issues) with the
   source/release links if you need help with packaging.

F-Droid-signed and upstream-signed APKs are not normally interchangeable
updates. Users may need to uninstall one before installing the other;
export any logs/settings they need first.

## Screenshots to provide

Add fresh TuxWheel captures using sanitized/demo data under
`fastlane/metadata/android/en-US/images/phoneScreenshots/1.png`, `2.png`, etc.
Use the equivalent `fr-FR/images/phoneScreenshots/` directory for French captures.
No screenshots have been fabricated or copied from EUC SoH.
Suggested captures:

- `DASHBOARD`: customized blocks and gauge on the Original theme.
- `SCAN`: discovery dialog showing devices and manual MAC entry.
- `PROTOCOL_PICKER`: long-press device menu with Automatic and protocol choices.
- `SMART_BMS`: supported wheel with per-pack measurements and cell details.
- `TRIPS`: recorded ride statistics and share/delete actions.
- `SETTINGS`: wheel-specific controls, page options, themes, and units.
