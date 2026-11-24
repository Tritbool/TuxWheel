# Usage

## Requirements and wheel support

- Android 6.0 or newer (API 23), with BLE hardware and Bluetooth enabled.
- Wheel-family presentations: KingSong, Gotway/Begode, Veteran, InMotion
  (including V2), and Ninebot/Ninebot Z.
- Actual connectivity, telemetry, settings, and commands depend on
  EUC_BLE_LIBRARY support, the wheel model, and its firmware.
- Smart BMS details are available only for supported wheels/packs.
  A family-specific screen is not a guarantee that every model supports every feature.

On startup, TuxWheel looks for saved wheels for up to 10 seconds
without opening the scan screen. A BLE interruption during use triggers the
library's automatic retries for the same wheel. Open **My wheels** to stop
an attempt or disconnect intentionally without reconnecting.

### Optional priority following (experimental)

**Follow saved wheel priority** keeps a filtered, low-power scan running while
you are connected and hands the session over, without any action, to a
higher-priority saved wheel as soon as it advertises — no need to turn the
current wheel off first. Switches only move up in priority: riding the
top-priority wheel never gets overridden. When the current wheel goes away
(power off, out of range), the session falls back to the best saved wheel still
in range instead of staying disconnected, retrying for about five minutes with
a 15-second spacing; a manual disconnect is respected for one minute. The
trade-off is deliberate and is
stated in the setting: any higher-priority wheel coming into range interrupts
the current connection and takes over, even mid-ride. Connection losses are
expected in this mode, not bugs; leave it off when you need maximum connection
stability.

## Connect to a wheel

### Optional black box

In the main settings menu, open **Black box** and enable it after saving your wheels.
The mode is off by default. It connects and records immediately, independently
of the normal auto-log speed threshold, without opening the dashboard.
Saved-wheel order and its existing priority wait are retained.

A foreground service performs filtered, low-power BLE scans for saved wheels
and has a permanent notification even while waiting, on every Android version.
On Android 12+, approving Android's companion-device association for saved
wheels additionally lets Android itself wake TuxWheel when the app process is
gone; it is recommended but the in-app scan no longer depends on it. TuxWheel
disconnects from the wheel during association, because a connected wheel stops
advertising and Android cannot find it. One associated wheel is enough to
enable the mode; other wheels can be associated later from the same settings
screen. On Android 6–11, grant location and, on Android 10–11, background
location; older versions also need storage permission. Disabling manufacturer
battery restrictions for TuxWheel is recommended so the OS does not kill the
waiting service.

The settings screen shows whether the mode is disabled or paused, whether
permissions, Bluetooth or associations need attention, and the current service
status and last error when available. A notification appears when a session
starts and indicates connection and recording; the waiting notification shows
the service is alive but no wheel is in range. On Android 13+, allow
notifications to see them in the notification drawer. If recording is working,
the trip also appears in the app's trip list.

During a session, notifications remain visible. An interruption keeps the same
log open and retries the saved wheels again in the app-defined order, giving
another present wheel its turn ahead of a failing one. After **15 minutes**
without a connection (configurable from 1 to 120 minutes), the session closes
and the wheel is left alone until it is seen again after a disappearance;
wheel surveillance continues for other wheels. A "Connected to <wheel>"
notification is posted once per black-box connection. Opening or closing the
dashboard does not stop black-box recording.

Manual disconnection closes the log immediately. Choose whether to wait for the
wheel to disappear and return (default), reconnect immediately, or suspend the
mode until **Enable or resume black box** is switched on again. Absence is a
Bluetooth proximity signal, not proof the wheel is powered off; it requires 60
seconds without advertisements. The suspension settings survive
process restarts.

Enable Bluetooth and, on older Android versions, system location. Force-stop,
revoked permissions and manufacturer battery restrictions can prevent operation.
After a force-stop, reopen TuxWheel to restore monitoring. This is not a
replacement for the wheel's own safety systems.

### Manual connection

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

After a successful manual connection, TuxWheel saves the wheel on your phone.
Tap **My wheels** (the wheel icon) to manage saved wheels. Drag the handle on
the left of a row to change its priority. Use its green play button to connect or
its red stop button to disconnect.
**Add a wheel** offers a Bluetooth scan or manual MAC entry. Starting a scan
ends the active wheel connection after confirmation. The manual form inserts
the colons between the 12 hexadecimal digits. Without a custom name, a manual
wheel is called `wheel1`, `wheel2`, … until its first connection replaces that
placeholder with the wheel's Bluetooth name; custom names are never replaced.
On launch, TuxWheel scans for up to **10 seconds** and connects immediately if
it finds the top-ranked wheel. When it first finds another saved wheel, it gives
higher-ranked wheels **1 second** by default, then picks the best one found.
Change this wait in **My wheels** (0 seconds connects to the first saved wheel
found). The overall scan limit still applies. Tap the blinking **Bluetooth**
icon to cancel automatic connection and take over the scan manually.

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

### Bluetooth remote buttons

Pair a Bluetooth HID remote (e.g. AB Shutter 3; its Android button sends
**Volume Up**) in Android Bluetooth settings. In **Settings → Bluetooth Buttons**,
tap **Capture a new button**, press the remote button, then pick its action.

- Up to five buttons can be configured; different remotes and keys work, the same key is kept once.
- A remote whose buttons send the same Android key (the AB Shutter 3 sends **Volume
  Up from both buttons**, iOS and Android) cannot map them to different actions; capturing
  the same key again updates the existing entry instead.
- Capture from a foreground screen records the remote's Bluetooth device. Captured
  buttons only fire for that device: the phone's own volume keys and on-screen taps
  never trigger wheel actions, and with the captured remote disconnected the media
  volume keys behave normally again.
- Actions: toggle the speed limiter, toggle the wheel headlight, toggle the
  wheel lock (only when stationary), or beep.
- Nothing is caught by default. While mappings are active the system media
  volume is not changed by the remote's volume keys; forget the buttons to
  give the keys back to the system. 
- Buttons keep working with the screen off or another app in front while the wheel
  is connected, through an app-held media session. The `Beep on Volume Up` option
  historically built on the same session keeps working.
- The BACK and HOME keys are never captured. Repeated or held presses are throttled.
- Each button press cycles the wheel headlight through the light commands the wheel
  supports instead of guessing its current state from telemetry.

## Dashboard and pages

Swipe between the dashboard, wheel settings, and enabled optional pages.
Enable charts, trips, and events in the page settings as needed.
Smart BMS visibility and detail depend on the connected wheel.
For Inmotion wheels, reported pack measurements and cell voltages appear as they
arrive; unavailable values are left blank.

- **Long-press a dashboard block** to choose its replacement.
- **Single tap** triggers the horn only when that gesture is enabled.
- **Double tap** toggles lights only when the wheel supports the command.
- Choose units and day/night appearance in settings.
- When the outer ring is set to PWM, it shows the current PWM at the end of the
  ring fill. The separate **Show PWM below the speed** option displays the
  current and ride maximum PWM in place of the speed unit. If PWM is the main
  value, that option shows the speed below it instead.

Wheel settings and actions are capability-dependent. Verify changes while
stationary; app alarms do not replace the wheel's own safety protections.

InMotion V9 lock controls appear after model identification and wait for the wheel's
automatic token handshake, without asking for a KingSong PIN. Lock-state requests
and subsequent wheel notifications confirm the state; a BLE write alone does not.
P6 lock-state querying is not yet verified, so it does not expose this control.

For identified InMotion V9/P6 wheels, **Wheel diagnostic file** downloads the wheel's
opaque binary log, shows progress, and offers cancellation. **Save verified file**
opens Android's document picker only after the library verifies the complete file's
CRC. Failed/cancelled transfers cannot be saved. No storage permission, automatic
upload, or diagnostic-content decoding is required; choosing a destination and
sharing the file remain explicit user actions. Live-wheel behavior is unverified.
