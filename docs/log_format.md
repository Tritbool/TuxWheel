# TuxWheel CSV log format

TuxWheel ride logs are CSV files written under **Downloads/TuxWheel/<wheel>**
(one folder per wheel MAC, `_`-separated). Two schemas exist:

1. **Legacy (WheelLog-compatible)** - the default, kept for older
   third-party tooling. Produced when *Enriched log format* is off.
2. **TuxWheel v2 (enriched)** - opt-in from Log settings. Every decoded BLE
   field of the session is exported per row, with explicit unit-suffixed
   column names.

A file's schema is decided when the file is created and never changes
mid-file: sessions appended to an existing "continue this day" log always
match that file's original schema. This page also documents the diagnostics artifacts produced alongside the
ride CSV: the per-wheel **BMS CSV**, the high-frequency **BMS lab JSONL.gz**
capture, and the **raw BLE traffic CSV**.

## Detecting the schema

- v2 files carry a leading `schema` column whose value is `tuxwheel-2` on
  every row.
- Legacy files start with `date,time,speed,...`.

## Legacy schema

Header: `date,time,speed,voltage,phase_current,current,power,torque,pwm,battery_level,distance,totaldistance,system_temp,temp2,tilt,roll,mode,alert`

| Column | Unit | Notes |
|---|---|---|
| date | `yyyy-MM-dd` | local time |
| time | `HH:mm:ss.SSS` | local time |
| speed | km/h | empty when stealth logging hides it |
| voltage | V | |
| phase_current | A | motor-side |
| current | A | battery-side; sign convention depends on protocol |
| power | W | |
| torque | N.m | estimate |
| pwm | % | 0-100 |
| battery_level | % | 0-100 |
| distance | km | current trip |
| totaldistance | km | odometer |
| system_temp | C | mainboard/primary temperature sensor; physical sensor varies by protocol |
| temp2 | C | motor temperature |
| tilt | deg | pitch angle |
| roll | deg | roll angle |
| mode | text | protocol-dependent ride state |
| alert | | always `0` (reserved by the legacy format) |

## TuxWheel v2 schema

Header: `schema,timestamp_ms,date,time,speed_kmh,voltage_v,phase_current_a,current_a,power_w,torque_nm,pwm_pct,battery_pct,distance_km,total_distance_km,ride_time_s,total_ride_time_s,total_power_on_time_s,mainboard_temp_c,motor_temperature_c,mosfet_temperature_c,board_temperature_c,imu_temperature_c,cpu_load_pct,tire_pressure_kpa,pitch_deg,roll_deg,top_speed_kmh,speed_limit_kmh,dynamic_speed_limit_kmh,wheel_max_speed_kmh,alarm1_speed_kmh,alarm2_speed_kmh,alarm3_speed_kmh,mode,charging,fan_status,led_mode,light_mode,pedals_mode,alert_flags,wheel_alarm,uses_miles,alert,manufacturer,model,serial_number,firmware_version,cell_voltages_v`

Notes for third parties:

- Rows are emitted on every received telemetry frame; there is no fixed rate,
  and gaps simply mean no frame arrived in between.
- Every column name carries its unit. `timestamp_ms` is the epoch-millisecond
  receive time and is authoritative; `date`/`time` are local-time renderings
  for readability.
- Empty cell = the wheel did not report the field. Optional measurements are
  nullable per protocol.
- Column naming is intentionally NOT legacy-compatible: `temp2` is gone
  (`motor_temperature_c`), `battery_level` is `battery_pct`, `tilt` is
  `pitch_deg`, `totaldistance` is `total_distance_km`, etc. Parse v2 files
  by these exact names and detect the schema through the leading `schema`
  column.
- Text fields (`mode`, `manufacturer`, `model`, `serial_number`,
  `firmware_version`) are double-quoted; `cell_voltages_v` is a double-quoted
  bracket list of the pack's cell voltages, e.g. `"[4.072,4.073,4.075]"`.
- `speed_limit_kmh` semantics vary by protocol (configured tilt-back vs.
  runtime limit); KingSong's `dynamic_speed_limit_kmh` is the F6 runtime
  speed ceiling, independent of the configured limit.
- `ride_time_s` is the current sessions ride time (possibly estimated);
  `total_ride_time_s` and `total_power_on_time_s` are lifetime counters, not
  session values.
- `charging` and `wheel_alarm` are normalized booleans (`true`/`false`,
  empty if unknown); `uses_miles` reflects the wheel's display preference
  only - all measurements stay metric.
- Code columns (`fan_status`, `led_mode`, `light_mode`, `pedals_mode`,
  `alert_flags`) are protocol-specific integers whose mapping is
  manufacturer/firmware-dependent.
- `alert` is reserved and always `0`.

### Minimal example (v2, one row)

```csv
schema,timestamp_ms,date,time,speed_kmh,voltage_v,phase_current_a,current_a,power_w,torque_nm,pwm_pct,battery_pct,distance_km,total_distance_km,ride_time_s,total_ride_time_s,total_power_on_time_s,mainboard_temp_c,motor_temperature_c,mosfet_temperature_c,board_temperature_c,imu_temperature_c,cpu_load_pct,tire_pressure_kpa,pitch_deg,roll_deg,top_speed_kmh,speed_limit_kmh,dynamic_speed_limit_kmh,wheel_max_speed_kmh,alarm1_speed_kmh,alarm2_speed_kmh,alarm3_speed_kmh,mode,charging,fan_status,led_mode,light_mode,pedals_mode,alert_flags,wheel_alarm,uses_miles,alert,manufacturer,model,serial_number,firmware_version,cell_voltages_v
tuxwheel-2,1732350000000,2024-11-23,10:20:00.000,12.340,67.150,3.290,1.210,81.25,10.44,33.20,88,1.234,1234.567,308,,,24.60,31.80,,,37,,,,0.51,13.20,30.00,,50.0,55,60,"Drive",false,,,2,,,,false,true,0,"Gotway","Begode T4","ABC123","2.42","[4.072,4.073,4.075]"
```

## Reading v2 logs with legacy-minded tools

The in-app trip parser maps the v2 names onto its legacy concepts internally
(`LogHeaderEnum.fromHeaderColumn`), so both schemas appear identically inside
the app. External consumers should parse v2 by the documented column names
above rather than expecting legacy names.

## BMS CSV (per-wheel battery log)

Written **while ride logging is running** when *BMS data logging* is enabled
(Log settings). One file per recording session, stored next to the ride log
in the wheel's folder and named `yyyy_MM_dd_HH_mm_ss.bms.csv`. A row is
written at most once per *BMS recording interval* (separate Log setting),
using the latest decoded BMS snapshot; interval throttling only affects the
CSV, not the ride log.

The separator is a **semicolon** (`;`), not a comma.

Header: `timestamp_ms;bms_index;voltage_v;current_a;remaining_capacity_mah;factory_capacity_mah;cycles;is_charging;temperatures_c;cell_voltages_v`

| Column | Unit | Notes |
|---|---|---|
| timestamp_ms | epoch ms | host write time, not wheel-reported |
| bms_index | | raw library BMS index; multi-pack wheels report one row per pack |
| voltage_v | V | 3 decimals |
| current_a | A | 3 decimals; sign convention depends on protocol |
| remaining_capacity_mah | mAh | empty when unreported |
| factory_capacity_mah | mAh | empty when unreported |
| cycles | count | empty when unreported |
| is_charging | `true`/`false` | empty when unreported |
| temperatures_c | °C list | quoted bracket list, e.g. `"[22.00,23.50]"`; empty when unreported |
| cell_voltages_v | V list | quoted bracket list, 4 decimals; empty when unreported |

### Example

```csv
timestamp_ms;bms_index;voltage_v;current_a;remaining_capacity_mah;factory_capacity_mah;cycles;is_charging;temperatures_c;cell_voltages_v
1732350000123;0;67.152;1.210;157800;201600;42;false;"[24.00]";"[4.0721,4.0725,4.0718]"
```

## High-frequency BMS lab capture (JSONL.gz)

Independent, opt-in setting. While ride logging is running, one UTF-8 JSON
line per received telemetry callback carrying a BMS snapshot, written to a
gzip stream flushed at least once per second. File names are
`yyyy_MM_dd_HH_mm_ss_SSS.bms.jsonl.gz` in the wheel's folder.

Each line is a JSON object:

```json
{
  "sequence": 123,
  "timestamp_ms": 1732350000456,
  "packs": [
    {
      "bms_index": 0,
      "voltage_v": 67.152,
      "current_a": 1.21,
      "remaining_capacity_mah": 157800,
      "factory_capacity_mah": 201600,
      "cycles": 42,
      "is_charging": false,
      "temperatures_c": [24.0],
      "cell_voltages_v": [4.0721, 4.0725, 4.0718],
      "serial_number": "ABC123",
      "firmware_version": "1.2",
      "charge_current_a": null,
      "discharge_current_a": null
    }
  ]
}
```

- `sequence` is capture order; gaps mean dropped records (bounded queue). It
  is **not** the ride log's row order.
- `timestamp_ms` is the app callback time. Values may predate the telemetry
  callback: the library exposes its latest BMS state, snapshot freshness
  depends on the wheel.
- `bms_index` is the raw library index; one entry per pack, unchanged
  snapshots included — this mode is designed for battery analysis, not rides.
- Unreported fields are JSON `null`.
- Readable with `pandas.read_json(path, lines=True, compression="gzip")`.
- Files are finalized when logging stops; an abrupt process kill can leave a
  truncated gzip.

## Raw BLE traffic log

Opt-in setting, produced **while ride logging is running**: every received
BLE chunk (RX) and every outgoing write accepted by Android (TX) is appended
to `yyyy_MM_dd_HH_mm_ss.raw.csv` in the wheel's folder. Capture subscribes
before connecting; consumed events are not replayed when logging restarts.

Header: `timestamp_ms,hex,type,sequence,characteristic_uuid`

| Column | Notes |
|---|---|
| timestamp_ms | epoch ms, assigned by the library; sequence order is authoritative, wall clock can move backwards |
| hex | the chunk's bytes as continuous uppercase hex, no separator; chunk boundaries preserved and chunks need not be complete frames |
| type | `RX` (wheel/stack to app) or `TX` (accepted by Android's BLE stack — this does NOT confirm wheel reception or execution) |
| sequence | library-wide event sequence; gaps mean dropped events (or redaction, below) |
| characteristic_uuid | BLE characteristic the chunk traveled on |

Notes:
- Outgoing KingSong authentication frames are deliberately omitted, so those
  plaintext passwords never reach the file; this creates RX/TX sequence gaps.
- The file is buffered; entries appear in sequence order.
- Raw logs are a decoder/replay aid, not a telemetry record: only RX records
  should feed a protocol decoder. Ride and BMS CSVs are separate artifacts
  unaffected by this mode.

## Related files

The ride CSV described above is the primary artifact; the formats in this
section are the diagnostics companions. All of them live in the same
per-wheel folder and their content does not depend on the selected ride-log
schema.
