# TuxWheel
<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="EUC SoH logo" width="256" height="256" />
</p>


[![Build](https://github.com/Tritbool/TuxWheel/actions/workflows/gradle.yml/badge.svg)](https://github.com/Tritbool/TuxWheel/actions)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

TuxWheel is an Android companion for electric unicycles (EUCs). It connects
over Bluetooth Low Energy (BLE) to display
telemetry, configure supported wheel settings, and record rides.

**It does not aim at fully replace your EUC's OEM App.**
>Instead, TuxWheel is designed as a discreet riding app, focusing on dashboard and EUCs data logging.
N.B: Bluetooth stack relies on retro-engineering so some readings or commands can be fully missing so far**

[Releases](https://github.com/Tritbool/TuxWheel/releases) ·
[Builds](https://github.com/Tritbool/TuxWheel/actions) ·
[Issues](https://github.com/Tritbool/TuxWheel/issues)

## PRIVACY
**This application works offline. It is designed for DeGoogled Android devices and focuses on providing a dashboard for EUCs without bloatware features, focusing on privacy and effectiveness.**

- NO GPS data
- NO Trackers
- NO ads
- NO data collection

Core BLE, dashboard and logging features work offline. Upstream release builds
use Internet permission to check GitHub's latest stable release at startup, at
most once per 24 hours (including failed attempts). GitHub receives the network
request, including your IP address; no ride data is uploaded. Network failures
are silent. A newer stable version offers **Download** (opens the release APK
in your browser) or **Later**; TuxWheel does not download or install APKs itself.
Debug builds do not check for release APKs with a different application ID.
Source/help links open in your chosen browser; sharing opens your chosen app.
Automatic Android cloud backup and device-transfer extraction of app data are
disabled. This does not delete existing backups or protect public Downloads
files from other apps, device-owner tools, or a user's own backup software.
Export any data you need before uninstalling: settings will not restore
automatically.

### Distribution channels and update checks

Build with `-PenableGithubUpdates=false` to disable GitHub checks and remove
Internet permission, including library declarations. The default is `true`.
**F-Droid and other independently signed distributions must set this property**
for every build, including APKs that users might sideload. For example:
`./gradlew :app:assembleRelease -PenableGithubUpdates=false`.
Use the same property when running tests/lint for that channel.

Installer detection additionally suppresses checks for the official F-Droid
client/privileged installer and several alternative F-Droid clients. It is only
a fallback: sideloads can have no installer identity, and unknown clients cannot
be reliably identified. There is no upstream signing-certificate pin; Android
enforces signing compatibility at installation, but the build flag is essential
to avoid offering incompatible upstream APKs to independently signed builds.

Debug builds log to logcat, not automatically to public HTML files. Ride CSV
logging and optional raw BLE diagnostics remain explicit app features; they can
contain identifying data and should be disabled when not needed. Existing files
are not deleted by this cleanup.

## Requirements and wheel support

- Android 6.0 or newer (API 23), with BLE hardware and Bluetooth enabled.
- Wheel-family presentations: KingSong, Gotway/Begode, Veteran, InMotion
  (including V2), and Ninebot/Ninebot Z.
- Actual connectivity, telemetry, settings, and commands depend on
  EUC_BLE_LIBRARY support, the wheel model, and its firmware.
- Smart BMS details are available only for supported wheels/packs.
  A family-specific screen is not a guarantee that every model supports every feature.

## Features

- Customizable dashboard blocks and gauges, with Original and AJDM themes.
- Speed, battery, temperature, current, PWM, and other available telemetry.
- Configurable alarms and charts; optional trips and events pages.
- Smart BMS presentation with per-pack measurements and cell details when available.
- CSV ride logging through a foreground service, plus optional raw BLE diagnostics.
- Picture-in-picture (PiP) on supported Android versions.
- Configurable speed/distance and temperature units.

## Connect to a wheel

1. Enable Bluetooth and open the scan dialog.
2. Grant **Nearby devices** permissions on Android 12+.
   Older Android versions require location permission for BLE scanning;
   enable system location when required.
3. Wait for nearby devices. Scanning stops after **10 seconds**;
   discovered results remain selectable. **Back** cancels the dialog and scan.
4. Tap a device to connect, or enter its Bluetooth MAC address manually.
5. Long-press a discovered device to open the protocol picker.
   Select a specific protocol to force it only when the wheel requires it;
   **Automatic** leaves detection to the library and is the fallback when unsure.

If connection fails, check permissions, Bluetooth, and whether another app
already holds the wheel connection. Report the model, firmware, and selected
protocol with any issue; do not assume all devices in a scan are EUCs.

For KingSong, a password popup appears when the security-capable protocol is
selected. Enter the four-digit wheel password (including leading zeros), or leave
it empty if no password is set. Empty input or Cancel sends no authentication
command. Passwords are not saved or automatically replayed on reconnect.
Unconfirmed authentication offers retry; it does not necessarily mean a wrong
password. Authentication does not automatically send the experimental wheel
lock/unlock command.

KingSong maximum/tilt-back speed and all three alarm thresholds use settings
reported by the wheel, not saved app defaults. Controls wait for a settings
reply before allowing changes. The telemetry speed-limit row shows the separate
runtime ceiling (or “—” until known), which may differ from configured maximum speed.

## Dashboard and pages

Swipe between the dashboard, wheel settings, and enabled optional pages.
Enable charts, trips, and events in the page settings as needed.
Smart BMS visibility and detail depend on the connected wheel.

- **Long-press a dashboard block** to choose its replacement.
- **Single tap** triggers the horn only when that gesture is enabled.
- **Double tap** toggles lights only when the wheel supports the command.
- Choose Original/AJDM styling and units in settings.

Wheel settings and actions are capability-dependent. Verify changes while
stationary; app alarms do not replace the wheel's own safety protections.

## Logging, trips, and diagnostics

The app allows the logging of:
1. Standard data log (similar to WheelLog)
2. SMART BMS Data at detailed level (for battery analysis, later added into euc soh analyzer APP)
3. RAW BLE Frames (similar to WheelLog)

Start CSV logging from the app. The foreground service keeps recording while
the interface is in the background and exposes logging controls in its notification.
Grant notification permission on Android versions that request it.

New files are saved under **Downloads/TuxWheel**. Existing CSV logs remain
available to the trips page.
Storage access follows the Android version's permission and storage rules.

Enable the trips page to inspect recorded ride statistics, share a CSV through
Android's share chooser, or delete a trip after confirmation.
Raw BLE logging is a separate diagnostic option, not a substitute for ride CSVs.
While the logging service runs, it records received BLE chunks (RX) and outgoing
writes accepted by Android (TX) to `yyyy_MM_dd_HH_mm_ss.raw.csv` in the wheel's
Downloads/TuxWheel subfolder. TX does not confirm wheel delivery or execution.
The raw CSV columns are `timestamp_ms,hex,type,sequence,characteristic_uuid`;
`type` is `RX` or `TX`. Timestamps and sequence numbers come from the library,
not file-write time. Use sequence order, since wall-clock timestamps can move
backwards. Chunk boundaries are preserved; chunks need not be complete frames.

Capture subscribes before connecting, with a bounded 256-event pending buffer
for recent initialization traffic until the service starts. Capture is best-effort:
sequence gaps indicate dropped events. Consumed events are not replayed when
logging restarts. Disabling raw logging or stopping the service clears pending
events; reconnecting arms capture again if raw logging is enabled. Raw file I/O
is buffered on a background dispatcher and flushed on close. Only RX records
should be used as input to a decoder or replay tool; ride and BMS CSVs are unchanged.
Outgoing KingSong authentication frames are omitted to keep plaintext passwords
out of raw CSV files; this also produces sequence gaps.

Before sharing CSVs or diagnostic logs, inspect and sanitize them:
remove MAC addresses, device names, location/route data, and other identifiers.
Attach only the relevant, sanitized excerpt to a bug report.

## EUC_BLE_LIBRARY integration

BLE is provided by [EUC_BLE_LIBRARY](https://github.com/Tritbool/euc_ble_library),
not by wheel protocol decoders embedded in this app.
The dependency is `io.github.tritbool:euc-ble-library:0.1.2`;
its version is pinned in `gradle/libs.versions.toml`.

Version 0.1.2 is not yet on Maven Central. Until published, build and publish
`euc-ble-core` to Maven Local from revision
`62524e5e1af92da5f53ccea6543b281649e5be73` using
`./gradlew :euc-ble-core:publishToMavenLocal`. App checks pin that revision,
which includes raw traffic and the KingSong authentication, speed-limit, and
password-management APIs from
[PR #76](https://github.com/Tritbool/euc_ble_library/pull/76).

KingSong password set, change, and removal controls appear in Wheel Settings
when the active protocol supports them. A missing wheel confirmation is reported
as unconfirmed; it does not prove the password was wrong or unchanged. Raw BLE
diagnostics omit outgoing KingSong authentication and password-management frames
because they contain plaintext credentials.

The library owns discovery/scanning, connections, protocol detection/decoding,
and BLE transport. The app's `BleSessionViewModel` wraps library callbacks
into `StateFlow<BleSessionState>` for the UI and other consumers.
It is an **app-wide Koin `single`**, shared by activities, Compose, and services;
resolve it with `inject()`/`koinInject()`, not an Activity-scoped `koinViewModel()`.

Telemetry comes from `EUCData`; BMS details use `getBMSData()` per pack.
Commands must respect the active protocol's capabilities.
The app owns permission prompts, presentation, alarms, storage, and logging.

## UI architecture

Production pages, the pager, and scan content are Compose-only.
Native headers, dialogs, PiP, and the MPAndroidChart chart still use View
interoperability; view binding remains necessary for those integrations.
Compose rendering does not own BLE connections or foreground logging.

Architecture decisions:
[Compose-first dashboard](docs/adr/001-compose-first-dashboard.md) ·
[Koin DI](docs/adr/002-koin-di.md)

## Third-party software and licenses

Open **Settings → About TuxWheel → Third-party software and licenses** to
read the bundled dependency list and existing asset/license notices offline.
The viewer includes the GPL-3.0 and Source Sans Pro OFL texts.

The dependency license report is generated automatically by
`com.github.jk1.dependency-license-report` from the resolved release and debug
runtime classpaths, including transitive dependencies. Build plugins, annotation
processors and test-only libraries are outside its scope. Compose RichText
renders the bundled Markdown with Material 3 styling.

Run `./gradlew :app:copyLicenseReportToAssets` to regenerate the report without
building an APK; normal builds also run it automatically. The report and extracted
license files are bundled from `app/build/generated/licenseAssets/licenses/`,
not written into the source tree. Preserve the notices under
`app/src/main/assets/licenses/` and review the resolved release dependency graph
before distributing a release: published license metadata can be incorrect
(see the bundled EUC BLE notice).

## Development

Use Android Studio/SDK tooling and the checked-in Gradle wrapper.
Java/Kotlin compilation targets **JDK 17**; the checked-in
`gradle/gradle-daemon-jvm.properties` selects **JDK 25** for the Gradle daemon
and may download it if it is not installed.
The app currently uses **compileSdk 37** and **targetSdk 36**.

```sh
git clone https://github.com/Tritbool/TuxWheel.git
cd TuxWheel
./gradlew :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

These tasks require working resolution of the configured Gradle plugins and
dependencies from Google Maven, Maven Central, the Gradle Plugin Portal and
JitPack (MPAndroidChart). Local Maven artifacts are intentionally not used:
a clean checkout must resolve the published BLE dependency remotely.
Do not downgrade the pinned library or plugins to bypass resolution failures.

Pull requests and pushes to `main` run the debug unit tests and Android lint on
a hosted Linux runner. These checks need no emulator or release-signing secrets.

During F-Droid preparation, the local build stopped before compilation because
**AGP 9.2.1** could not be resolved. Build, unit-test and lint results for these
changes therefore remain unverified; the existing `0.0.1` GitHub release build
succeeded, but does not validate these changes.

The application ID and Android namespace are `io.github.tuxwheel`
(`io.github.tuxwheel.debug` for debug builds).

## F-Droid preparation and submission

TuxWheel is being prepared for F-Droid; it is **not yet listed**.
This follows the English/French Fastlane layout used in
[EUC SoH](https://github.com/Tritbool/euc_soh_kotlin).
See the [submission guide](https://f-droid.org/fr/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
and [metadata reference](https://f-droid.org/docs/Build_Metadata_Reference/).

### Store listing

`fastlane/metadata/android/en-US/` is the fallback listing; `fr-FR/` contains
the French translation. Each has a title, short description, HTML full
description and `changelogs/1.txt`, matching the current `versionCode 1`.
The English listing reuses the existing app icon at `images/icon.png`;
other locales can fall back to it. Fastlane itself is not needed.

Keep titles within 50 characters, summaries within 80, full descriptions
within 4,000, and release notes within 500. Add
`changelogs/<versionCode>.txt` in each locale for every release.

### Build and licensing checks before submission

- Run the development tasks above from a clean checkout with no local Maven
  artifacts, then test BLE connection and foreground logging on a real device.
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

### Verified licenses and F-Droid policy

The [F-Droid inclusion policy](https://f-droid.org/docs/Inclusion_Policy/)
requires freely licensed source and dependencies, a free build toolchain and
a working build. It does **not** require removing functional FOSS libraries,
BLE permissions, storage access or foreground services. It explicitly permits
freely licensed binaries from trusted repositories including JitPack.

- **Inherited WheelLog assets:** the maintainer confirms that the inherited
  fonts/theme artwork come from GPL-3.0 WheelLog Android. Its
  [GPL license](https://github.com/Wheellog/Wheellog.Android/blob/f44e39e5388e500a5d2f315d1dcf32f167607bf8/LICENSE)
  covers the project except components with their own license.
  Both bundled fonts match
  [WheelLog's font files](https://github.com/Wheellog/Wheellog.Android/tree/f44e39e5388e500a5d2f315d1dcf32f167607bf8/app/src/main/res/font)
  byte-for-byte (Git blob IDs are recorded in the packaged notice).
  `prime_regular.otf` is the inherited Wheellog font, not an identified
  proprietary dependency.
- **Source Sans Pro:** `ajdm.otf` also matches Adobe's
  [Source Sans Pro Regular 2.020](https://github.com/adobe-fonts/source-sans/blob/2.020R-ro/1.075R-it/OTF/SourceSansPro-Regular.otf)
  byte-for-byte. It remains under **SIL OFL 1.1**, not GPL.
  The original copyright notice and full OFL text are bundled at
  `app/src/main/assets/licenses/SourceSansPro-OFL.txt`.
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

### Release and external submission

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

### Screenshots to provide

Add fresh TuxWheel captures using sanitized/demo data under
`fastlane/metadata/android/en-US/images/phoneScreenshots/1.png`, `2.png`, etc.
Use the equivalent `fr-FR/images/phoneScreenshots/` directory for French captures.
No screenshots have been fabricated or copied from EUC SoH.
Suggested captures:

- `DASHBOARD`: customized blocks and gauge; capture Original and AJDM themes.
- `SCAN`: discovery dialog showing devices and manual MAC entry.
- `PROTOCOL_PICKER`: long-press device menu with Automatic and protocol choices.
- `SMART_BMS`: supported wheel with per-pack measurements and cell details.
- `TRIPS`: recorded ride statistics and share/delete actions.
- `SETTINGS`: wheel-specific controls, page options, themes, and units.

## Contributing and credits

Report reproducible problems in [Issues](https://github.com/Tritbool/TuxWheel/issues).
For changes, describe the affected wheel/features and validation performed.
UI checks and real-wheel testing remain necessary alongside automated tests.

Thanks to [WheelLog](https://github.com/Wheellog/Wheellog.Android), the
[palachzzz fork](https://github.com/palachzzz/WheelLogAndroid), and their contributors.
TuxWheel is licensed under [GNU GPL v3](LICENSE).
