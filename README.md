# TuxWheel

<p align="center">
  <img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" alt="TuxWheel logo" width="256" height="256" />
</p>

[![Build](https://github.com/Tritbool/TuxWheel/actions/workflows/gradle.yml/badge.svg)](https://github.com/Tritbool/TuxWheel/actions)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)

TuxWheel is an Android companion for electric unicycles (EUCs). It connects over
Bluetooth Low Energy (BLE), through
[EUC_BLE_LIBRARY](https://github.com/Tritbool/euc_ble_library), to display telemetry,
configure supported wheel settings, and record rides.

It does not aim to fully replace your EUC's OEM app: TuxWheel is a discreet riding app
focused on the dashboard and on EUC data logging. BLE support relies on reverse
engineering, so some readings or commands may be missing depending on the wheel model
and firmware.

[Releases](https://github.com/Tritbool/TuxWheel/releases) ·
[Builds](https://github.com/Tritbool/TuxWheel/actions) ·
[Issues](https://github.com/Tritbool/TuxWheel/issues)

## What makes TuxWheel different

- **Fully offline.** No GPS, no trackers, no ads, no data collection. The only network
  use is an optional startup check for a newer app release (at most once per 5 min); F-Droid
  builds ship without it. Details in [docs/privacy.md](docs/privacy.md).
- **Speed limiter.** Cap the wheel's maximum speed at runtime; your own max speed is
  restored when the limiter is turned off.
- **InMotion diagnostic file download.** Download the wheel's binary diagnostic log and
  save it only after CRC verification — tested on the V9, Leaperkim (LK) pending
  validation.
- **Auto-connect over an ordered panel of wheels.** Save several wheels in *My wheels*,
  drag to set their priority, and TuxWheel connects at startup to the best-ranked wheel
  it finds.
- **Lock / unlock** when the wheel supports it (e.g. InMotion V9).
- **Bluetooth remote buttons.** Configure up to five buttons from any Bluetooth HID
  remote (e.g. AB Shutter 3) to toggle the **speed limiter**, the **headlight** or the
  **wheel lock**, or to beep. Buttons keep working with the screen off while the wheel
  is connected. Nothing is caught until a button is configured.
- **KingSong password management** (set, change or remove the 4-digit wheel password).

On top of that: customizable dashboard blocks and gauges (speed, battery, temperature,
current, PWM, ...), configurable alarms, charts, trips and events pages, Smart BMS with
per-pack measurements and cell voltages on supported wheels, CSV ride logging plus
optional raw BLE diagnostics (files saved under `Downloads/TuxWheel`),
picture-in-picture, and configurable speed/distance/temperature units.

## Screenshots

<!-- SCREENSHOT: docs/screenshots/01-dashboard.png — customized dashboard blocks and gauge -->
<!-- SCREENSHOT: docs/screenshots/02-my-wheels.png — saved-wheels panel with drag-to-reorder (auto-connect priority) -->
<!-- SCREENSHOT: docs/screenshots/03-speed-limiter.png — speed limiter on/off -->
<!-- SCREENSHOT: docs/screenshots/04-diagnostic-file.png — InMotion diagnostic file download (V9) -->
<!-- SCREENSHOT: docs/screenshots/05-wheel-settings.png — lock/unlock and KingSong password management -->
<!-- SCREENSHOT: docs/screenshots/06-trips.png — recorded trip statistics -->

<!-- Once the files exist under docs/screenshots/, replace this comment with:
<p align="center">
  <img src="docs/screenshots/01-dashboard.png" alt="Dashboard" width="240" />
  <img src="docs/screenshots/02-my-wheels.png" alt="My wheels" width="240" />
  <img src="docs/screenshots/03-speed-limiter.png" alt="Speed limiter" width="240" />
  <img src="docs/screenshots/04-diagnostic-file.png" alt="Diagnostic file download" width="240" />
  <img src="docs/screenshots/05-wheel-settings.png" alt="Wheel settings" width="240" />
  <img src="docs/screenshots/06-trips.png" alt="Trips" width="240" />
</p>
-->

## Other platforms

- **Android** — [GitHub releases](https://github.com/Tritbool/TuxWheel/releases) today;
  an **F-Droid** listing is planned (see [docs/f-droid.md](docs/f-droid.md)).
- **SailfishOS** — upcoming port, reusing the same
  [euc_ble_library](https://github.com/Tritbool/euc_ble_library) protocols with
  platform-specific glue code.
- **iOS** — upcoming port; may not be published because of the Apple developer
  program fee.

## Usage

- Android 6.0+ (API 23) with BLE hardware and Bluetooth enabled.
- Wheel families: KingSong, Gotway/Begode (including Leaperkim), Veteran, InMotion
  (including V2), Ninebot/Ninebot Z. Available features depend on the wheel model,
  firmware and EUC_BLE_LIBRARY support.
- Connect from the scan dialog or from *My wheels*; on startup TuxWheel scans for up
  to 10 s and auto-connects to the top-priority saved wheel it finds.
- Ride CSV logs and optional raw BLE diagnostics are saved under `Downloads/TuxWheel`.

Details: [docs/usage.md](docs/usage.md) (connecting, dashboard) and
[docs/logging.md](docs/logging.md) (logging formats). The ride CSV schemas
(legacy WheelLog-compatible and the enriched TuxWheel v2) are documented for
third-party consumers in [docs/log_format.md](docs/log_format.md).

## Development

Java/Kotlin compilation targets **JDK 17** (the Gradle daemon runs on **JDK 25**),
`compileSdk 37`, `targetSdk 36`. Application ID `io.github.tuxwheel`
(`io.github.tuxwheel.debug` for debug builds).

```sh
git clone --recurse-submodules https://github.com/Tritbool/TuxWheel.git
cd TuxWheel
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

A plain `git clone` leaves the `ble-library` submodule empty; run
`git submodule update --init` inside the checkout before building, otherwise
the build cannot resolve the BLE protocol APIs. The submodule pins the
`euc_ble_library` commit the app is built against; `master` of the library is
used for CI, and docs/development.md explains building against your own
checkout.

To build against a local `euc_ble_library` checkout, add
`-PeucBleLibraryDir=/absolute/path/to/euc_ble_library -Peuc.androidGradlePluginVersion=9.2.1`.
Full build, library-integration, architecture and license notes:
[docs/development.md](docs/development.md). F-Droid submission guide:
[docs/f-droid.md](docs/f-droid.md).

## Contributing and credits

Report reproducible problems in
[Issues](https://github.com/Tritbool/TuxWheel/issues).
Thanks to [WheelLog](https://github.com/Wheellog/Wheellog.Android) and the
[palachzzz fork](https://github.com/palachzzz/WheelLogAndroid).
TuxWheel is licensed under [GNU GPL v3](LICENSE).
