# TuxWheel CSV log format

TuxWheel ride logs are CSV files written under **Downloads/TuxWheel/<wheel/**
(one folder per wheel MAC, `_`-separated). Two schemas exist:

1. **Legacy (WheelLog-compatible)** — the default. Required by older
   third-party tooling and still produced when *Enriched log format* is off.
2. **TuxWheel v2 (enriched)** — opt-in from Log settings. Every decoded BLE
   field of the session is exported per row.

A file's schema is decided when the file is created and never changes
mid-file: sessions appended to an existing "continue this day" log always
match that file's original schema.

## Detecting the schema

- v2 files carry a leading `schema` column whose value is `tuxwheel-2` on
  every row.
- Legacy files start with `date,time,speed,…`.

The v2 schema keeps the legacy column names (and units) for all quantities
that existed in the legacy schema, and appends new typed columns. Consumers
that parse by header name keep working on both schemas.

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
| torque | N·m | estimate |
| pwm | % | 0-100 |
| battery_level | % | 0-100 |
| distance | km | current trip |
| totaldistance | km | odometer |
| system_temp | °C | primary temperature sensor; physical sensor varies by protocol |
| temp2 | °C | motor temperature (`motor_temperature_c` in v2) |
| tilt | ° | pitch angle |
| roll | ° | roll angle |
| mode | text | protocol-dependent ride state |
| alert | | always `0` (reserved by the legacy format) |

## TuxWheel v2 schema

Header: `schema,timestamp_ms,date,time,speed,voltage,phase_current,current,power,torque,pwm,battery_level,distance,totaldistance,ride_time_s,total_ride_time_s,total_power_on_time_s,system_temp,temp2,motor_temperature_c,mosfet_temperature_c,board_temperature_c,imu_temperature_c,cpu_load_pct,tire_pressure_kpa,tilt,roll,top_speed_kmh,speed_limit_kmh,dynamic_speed_limit_kmh,wheel_max_speed_kmh,alarm1_speed_kmh,alarm2_speed_kmh,alarm3_speed_kmh,mode,charging,fan_status,led_mode,light_mode,pedals_mode,alert_flags,wheel_alarm,uses_miles,alert,manufacturer,model,serial_number,firmware_version,frame_type,cell_voltages_v`

Notes for third parties:

- Rows are emitted on every received telemetry frame; there is no fixed rate
  and gaps mean no frame arrived in between.
- `timestamp_ms` is the epoch-millisecond receive time and is authoritative;
  `date`/`time` are local-time renderings for readability.
- Empty cell = the wheel did not report the field (optional measurements are
  null-able by protocol).
- `mode`, `manufacturer`, `model`, `serial_number`, `firmware_version` are
  double-quoted (they may contain commas/spaces).
- Speeds and limits are km/h. `speed_limit_kmh` semantics vary by protocol
  (configured tilt-back vs. runtime limit); KingSong's `dynamic_speed_limit_kmh`
  is the F6 runtime ceiling.
- `total_ride_time_s` and `total_power_on_time_s` are lifetime odometers of
  time, not session values.
- `cell_voltages_v` is a double-quoted bracket list of the pack's cell
  voltages, e.g. `"[4.072,4.073,4.075]"`.
- Protocol-specific code columns (`fan_status`, `led_mode`, `light_mode`,
  `charging_status`-style values in `charging` aside, `pedals_mode`,
  `alert_flags`) are documented as codes: their mapping is
  manufacturer/firmware-dependent. `charging` and `wheel_alarm` are
  normalized booleans (`true`/`false`, empty if unknown); `uses_miles`
  reflects the wheel's display preference only — all measurements stay
  metric.

### Minimal example (v2, one row)

```csv
schema,timestamp_ms,date,time,speed,voltage,phase_current,current,power,torque,pwm,battery_level,distance,totaldistance,ride_time_s,total_ride_time_s,total_power_on_time_s,system_temp,temp2,motor_temperature_c,mosfet_temperature_c,board_temperature_c,imu_temperature_c,cpu_load_pct,tire_pressure_kpa,tilt,roll,top_speed_kmh,speed_limit_kmh,dynamic_speed_limit_kmh,wheel_max_speed_kmh,alarm1_speed_kmh,alarm2_speed_kmh,alarm3_speed_kmh,mode,charging,fan_status,led_mode,light_mode,pedals_mode,alert_flags,wheel_alarm,uses_miles,alert,manufacturer,model,serial_number,firmware_version,frame_type,cell_voltages_v
tuxwheel-2,1732350000000,2024-11-23,10:20:00.000,12.340,67.150,3.290,1.210,81.25,10.44,33.20,88,1.234,1234.567,308,,,24.60,31.80,31.80,,25.10,,37,,,,12.34,0.51,13.20,30.00,,50.00,55.00,60.00,"Drive",false,,,2,,,,false,true,0,"Gotway","Begode T4","ABC123","2.42","BASE","[4.072,4.073,4.075]"
```

## Related files

The ride CSV described here is the primary artifact. Separate, unchanged
outputs exist for diagnostics: per-wheel BMS CSV and high-frequency BMS lab
JSONL.gz (see docs/logging.md), and raw BLE `*.raw.csv`. Those formats are
stable and independent of the schema selected here.
