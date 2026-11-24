# Development

The `background-session` integration pins `euc_ble_library` as the `ble-library`
Git submodule because `connectExternalDevice` is not in the published 1.1.0
artifact. Run `git submodule update --init` before building. An explicit
`-PeucBleLibraryDir=/path/to/euc_ble_library` still overrides this checkout.
Build with `-Peuc.androidGradlePluginVersion=9.2.1` to align the included Android
library with the app. CI uses the same pinned library commit.

## Using the latest local BLE library

Build against your local `euc_ble_library` checkout instead of a published release:

```sh
./gradlew -PeucBleLibraryDir=/absolute/path/to/euc_ble_library \
  -Peuc.androidGradlePluginVersion=9.2.1 :app:assembleDebug
```

The checkout must provide the descriptor-based command contract used by the app.
Use the library's `main` branch for development or its latest stable release tag
for a release build.

Or set `eucBleLibraryDir=/absolute/path/to/euc_ble_library` in your local
`~/.gradle/gradle.properties`, along with `euc.androidGradlePluginVersion=9.2.1`
to match this app's Android Gradle plugin version. The composite build substitutes all four BLE modules,
including the Android connector; no version bump or `publishToMavenLocal` is needed.
Without that property, the regular dependency coordinates remain unchanged.
The app-check workflow checks out the latest commit on the library's `main`
branch. The release workflow resolves GitHub's latest published stable library
release and prefers its Maven Central artifacts, including the Android connector
and JVM dependencies. Only an HTTP 404 for a required artifact or its metadata
triggers a checkout of that release's exact tag and a composite source build.
Network errors, other HTTP errors and build failures stop the release instead
of silently falling back. No stable GitHub release also stops the release.
There is no need to wait for Maven publication.

The release workflow uses `-PeucBleVersion=<version>` to override the BLE version
in the version catalogue without changing the checked-in default. This ensures
Maven and source builds both target the same latest stable release.

## EUC_BLE_LIBRARY integration

BLE is provided by [EUC_BLE_LIBRARY](https://github.com/Tritbool/euc_ble_library),
not by wheel protocol decoders embedded in this app.
The fallback dependency coordinate is pinned in `gradle/libs.versions.toml`.
Command descriptors and checked command submission come from the library API;
TuxWheel does not bundle or maintain a command catalogue.

Wheel Settings groups controls by theme. Tap a setting to change it: simple
modes apply when selected, while numeric settings and alarm edits require
**Apply**. The displayed value reflects the wheel’s report, not the last
selection. Settings the wheel reports without a matching control appear under
**Wheel information**. Power off and calibration require confirmation.

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
[Compose-first dashboard](adr/001-compose-first-dashboard.md) ·
[Koin DI](adr/002-koin-di.md)

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

## Building

Use Android Studio/SDK tooling and the checked-in Gradle wrapper.
Java/Kotlin compilation targets **JDK 17**; the checked-in
`gradle/gradle-daemon-jvm.properties` selects **JDK 25** for the Gradle daemon
and may download it if it is not installed.
The app currently uses **compileSdk 37** and **targetSdk 36**.

```sh
git clone --recurse-submodules https://github.com/Tritbool/TuxWheel.git
cd TuxWheel
git submodule update --init
./gradlew -PeucBleLibraryDir=../euc_ble_library \
  -Peuc.androidGradlePluginVersion=9.2.1 \
  :app:assembleDebug :app:assembleRelease :app:testDebugUnitTest :app:lintDebug
```

These tasks require working resolution of the configured Gradle plugins and
dependencies from Google Maven, Maven Central, the Gradle Plugin Portal and
JitPack (MPAndroidChart). The command assumes a sibling library checkout containing
the required library APIs, as described above. Local Maven artifacts are
intentionally not used: the composite consumes the library source directly.
Do not downgrade the library or plugins to bypass resolution failures.

Pull requests and pushes to `master` run the selected debug regression tests,
Android lint, and unsigned release assembly against the library's `main` branch on a
hosted Linux runner. These checks need no emulator or release-signing secrets.
They do not replace BLE testing on a physical wheel.

The application ID and Android namespace are `io.github.tuxwheel`
(`io.github.tuxwheel.debug` for debug builds).
