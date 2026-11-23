# Logging, trips, and diagnostics

The app allows the logging of:
1. Standard data log (similar to WheelLog)
2. SMART BMS Data at detailed level (for battery analysis, later added into euc soh analyzer APP)
3. RAW BLE Frames (similar to WheelLog)

Start CSV logging from the app. The foreground service keeps recording while
the interface is in the background and exposes logging controls in its notification.
Grant notification permission on Android versions that request it.

**True background black box.** When *Auto Log* is enabled, TuxWheel can log without
being opened: after device boot (and after app updates or closing the app) the
logging service starts a `connectedDevice` foreground service, auto-connects to the
remembered wheels itself, and opens the log file once a wheel is connected (or once
it moves, when the *Start logging when is moving* threshold is set). If no wheel is
found the scan is retried periodically, so powering a wheel on later in the day is
still captured, and logging parks between sessions like the foreground flow.
The Log settings page lists every missing prerequisite (remembered wheel, Bluetooth
permissions, battery-optimization exemption) with the matching fix action.

Android version nuances (the feature works on all versions supported by the app,
Android 6/API 23 and up):
- Android 6–11 (API 23–30): the background scan relies on the location permission
  already required for BLE scanning on these versions.
- Android 12+ (API 31+): requires the `BLUETOOTH_SCAN`/`BLUETOOTH_CONNECT` runtime
  permissions; the boot start is skipped while they are revoked.
- Android 14+ (API 34+): enforces the `connectedDevice` foreground-service type;
  the same runtime permissions gate both the boot start and the notification.
- Android 15+ (API 35+): `connectedDevice` remains startable from `BOOT_COMPLETED`,
  unlike `mediaPlayback`/`mediaProjection`/`mediaProcessing` types.
- All versions: an OEM battery-optimization exemption (already surfaced by the app
  elsewhere) keeps the session alive with the screen off.

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

**High-frequency BMS lab capture** is an independent logging setting. While logging
is active it saves one record per received telemetry callback with an available
BMS snapshot, including unchanged snapshots, to `*.bms.jsonl.gz` in the wheel's
Downloads/TuxWheel folder. It does not generate samples between callbacks or change
the wheel's polling rate. Each UTF-8 JSON Lines record has an app callback time
`timestamp_ms`, a `sequence`, and a `packs` array containing raw library indices,
nullable measurements, and cell and temperature arrays. It can be read with
`pandas.read_json(path, lines=True, compression="gzip")` and converted to Parquet.
The library returns its latest BMS state, so values may predate the telemetry
callback; callback rate and BMS freshness depend on the wheel and library. This
mode cannot guarantee 10–20 Hz or capture frames not delivered by the library.
The bounded queue warns on overflow; gaps in sequence indicate dropped records.
Files are finalized when logging stops; an abrupt process termination can leave a
truncated gzip file. The normal BMS CSV and its recording interval are unaffected.

Before sharing CSVs or diagnostic logs, inspect and sanitize them:
remove MAC addresses, device names, location/route data, and other identifiers.
Attach only the relevant, sanitized excerpt to a bug report.
