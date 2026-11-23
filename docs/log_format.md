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
match that file's original schema.

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

Header: `schema,timestamp_ms,date,time,speed_kmh,voltage_v,phase_current_a,current_a,power_w,torque_nm,pwm_pct,battery_pct,distance_km,total_distance_km,ride_time_s,total_ride_time_s,total_power_on_time_s,mainboard_temp_c,motor_temperature_c,mosfet_temperature_c,board_temperature_c,imu_temperature_c,cpu_load_pct,tire_pressure_kpa,pitch_deg,roll_deg,top_speed_kmh,speed_limit_kmh,dynamic_speed_limit_kmh,wheel_max_speed_kmh,alarm1_speed_kmh,alarm2_speed_kmh,alarm3_speed_kmh,mode,charging,fan_status,led_mode,light_mode,pedals_mode,alert_flags,wheel_alarm,uses_miles,alert,manufacturer,model,serial_number,firmware_version,frame_type,cell_voltages_v`

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
schema,timestamp_ms,date,time,speed_kmh,voltage_v,phase_current_a,current_a,power_w,torque_nm,pwm_pct,battery_pct,distance_km,total_distance_km,ride_time_s,total_ride_time_s,total_power_on_time_s,mainboard_temp_c,motor_temperature_c,mosfet_temperature_c,board_temperature_c,imu_temperature_c,cpu_load_pct,tire_pressure_kpa,pitch_deg,roll_deg,top_speed_kmh,speed_limit_kmh,dynamic_speed_limit_kmh,wheel_max_speed_kmh,alarm1_speed_kmh,alarm2_speed_kmh,alarm3_speed_kmh,mode,charging,fan_status,led_mode,light_mode,pedals_mode,alert_flags,wheel_alarm,uses_miles,alert,manufacturer,model,serial_number,firmware_version,frame_type,cell_voltages_v
tuxwheel-2,1732350000000,2024-11-23,10:20:00.000,12.340,67.150,3.290,1.210,81.25,10.44,33.20,88,1.234,1234.567,308,,,24.60,31.80,,,37,,,,0.51,13.20,30.00,,50.0,55,60,"Drive",false,,,2,,,,false,true,0,"Gotway","Begode T4","ABC123","2.42","BASE","[4.072,4.073,4.075]"
```

## Reading v2 logs with legacy-minded tools

The in-app trip parser maps the v2 names onto its legacy concepts internally
(`LogHeaderEnum.fromHeaderColumn`), so both schemas appear identically inside
the app. External consumers should parse v2 by the documented column names
above rather than expecting legacy names.

## Related files

The ride CSV described here is the primary artifact. Separate, unchanged
outputs exist for diagnostics: per-wheel BMS CSV and high-frequency BMS lab
JSONL.gz (see docs/logging.md), and raw BLE `*.raw.csv`. Those formats are
stable and independent of the schema selected here.
