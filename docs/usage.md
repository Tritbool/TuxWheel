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

## Connect to a wheel

### Optional black box

In the main settings menu, open **Black box** and enable it after saving your wheels.
The mode is off by default. It connects and records immediately, independently
of the normal auto-log speed threshold, without opening the dashboard.
Saved-wheel order and its existing priority wait are retained.

On Android 12+, turn on each saved wheel once and approve Android's companion
device association. Android watches associated wheels and can wake TuxWheel
after a phone restart. Associate newly saved wheels in the same settings screen.
On Android 6–11, a foreground service performs filtered, low-power BLE scans
and has a permanent notification even while waiting. Grant location and, on
Android 10–11, background location; older versions also need storage permission.

The settings screen shows whether the mode is disabled or paused, whether
permissions, Bluetooth or associations need attention, and the current service
status and last error when available. On Android 12+, "monitoring requested"
means presence observation was requested from Android, not that a wheel is connected;
there is no waiting notification. A notification appears when a session starts
and indicates connection and recording. On Android 13+, allow notifications to
see it in the notification drawer. If recording is working, the trip also appears
in the app's trip list.

During a session, notifications remain visible. An interruption keeps the same
log open and retries the same wheel. After **15 minutes** without a connection
(configurable from 1 to 120 minutes), the session closes; wheel surveillance
continues. Opening or closing the dashboard does not stop black-box recording.

Manual disconnection closes the log immediately. Choose whether to wait for the
wheel to disappear and return (default), reconnect immediately, or suspend the
mode until **Enable or resume black box** is switched on again. Absence is a
Bluetooth proximity signal, not proof the wheel is powered off; on Android 6–11
it requires 60 seconds without advertisements. The suspension settings survive
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
