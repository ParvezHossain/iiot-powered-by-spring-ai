---
document_id: REF-FAULTS-001
document_type: error_code_reference
machine_ids: [SIM-001, SIM-002, SIM-003, SIM-004, SIM-005]
revision: 2
updated_at: 2026-09-07
synthetic: true
---

# Fault and anomaly reference — fictional training fleet

## Equipment-local complaint codes

These invented codes belong to the equipment document scenarios. They are not
implemented simulator outputs or API reason strings. A code names a complaint,
not a confirmed root cause.

| Code | Machine | Meaning | First evidence to review |
| --- | --- | --- | --- |
| P-101 | SIM-001 CP-40 pump | Coolant circulation or cavitation complaint | Matched-load vibration, coolant-return observation, suction strainer inspection |
| C-201 | SIM-002 BC-12 conveyor | Belt tracking complaint | Belt-edge wear, rubbing report, gearbox vibration |
| A-301 | SIM-003 AC-7 compressor | Motor housing overheating complaint | Temperature trend, cooling screen condition, load history |
| F-401 | SIM-004 EF-18 fan | Fan vibration review requested | Housing vibration trend, deposits and mounting inspection |
| M-501 | SIM-005 MX-25 mixer | Sustained load complaint | Load history, reference batch record, temperature and vibration |

Only P-101, C-201, and A-301 have completed maintenance cases in this corpus.
There is no documented F-401 or M-501 repair outcome. Local codes do not define
automatic trips or permission to restart equipment.

## E204 — Compressor temperature sensor signal unavailable

E204 means the motor temperature sensor signal is unavailable on the fictional
SIM-003 AC-7 compressor. It indicates a missing or invalid temperature
measurement, not motor overheating. A previously stored temperature must not be
treated as a current measurement while the sensor is unavailable.

For this synthetic maintenance scenario, stop and isolate the equipment before
checking temperature sensor wiring and connector condition. Restore a valid
sensor signal and verify fresh temperature readings before closing the complaint.
No sensor replacement interval or automatic reset timeout is specified.

The telemetry dropout markers are `modbus_hr_40001 = 65535` and
`modbus_hr_40004 = 2`; the `temperature_celsius` row is absent in that batch.
E204 is a fictional equipment-local reference code, not a code emitted by the
simulator. The simulator records `SIMULATED_DROPOUT`, and the query API reports
`SENSOR_DROPOUT`. E204 has no completed repair history in this corpus and is
distinct from the compressor overheating complaint A-301.

## Implemented query API reasons

`GET /api/anomalies` derives reasons directly from raw readings:

| Reason | Exact predicate |
| --- | --- |
| HIGH_TEMPERATURE | `temperature_celsius` > 90 |
| HIGH_VIBRATION | `vibration_mm_s` > 5 |
| SENSOR_DROPOUT | `modbus_hr_40001` = 65535 |

Exactly 90 °C and 5 mm/s do not qualify. Rules apply per sample with no duration
or equipment-specific adjustment. A temperature spike and vibration spike at
the same instant produce separate results. Energy totals and register 40004
are not directly classified by this endpoint. Manual watch levels can therefore
identify a maintenance concern absent from the API anomaly list.

## Implemented simulator event codes

`SIMULATED_SPIKE` is a `FAULT` event accompanying added temperature and vibration
in one selected machine's batch. `SIMULATED_DROPOUT` accompanies an omitted
temperature row, temperature register 65535, and quality flag 2. These event
codes record injected ground truth; they are distinct from API reason strings.
The following batch returns to the normal generation formula.

The current simulator does not change `machines.status` when it injects a fault.
A stored `RUNNING` status can coexist with an anomaly. See
[REF-REGISTERS-001](telemetry-register-reference.md) for decoding and the
[API reference](../telemetry-api.md) for query filters.
