---
document_id: LOG-SIM-003
document_type: maintenance_log
machine_ids: [SIM-003]
revision: 1
updated_at: 2026-08-26
synthetic: true
---

# SIM-003 — Compressor cooling screen restriction

## Work order WO-260826-03

Synthetic document-only maintenance record for AC-7, SIM-003, Virtual factory /
line 3. All times below are UTC on 2026-08-26. Local complaint `A-301` described
high motor housing temperature during a sustained loaded period.

At 10:00, temperature reached 92.4 °C while vibration was 2.1 mm/s and load
register 40003 was 690. A previous comparable run recorded 70.2 °C and
2.0 mm/s at load 685. The 92.4 °C sample meets the API `HIGH_TEMPERATURE` rule;
the vibration does not meet `HIGH_VIBRATION`. This case differs from an injected
simulator spike, which raises both temperature and vibration together.

## Evidence and work performed

The compressor was stopped and isolated, including stored pneumatic energy,
at 10:10. Inspection found a cooling-air screen covered with lint. The screen
was cleaned; no bearing or temperature sensor was replaced. Ambient conditions
were recorded as unchanged by the fictional technician, but no ambient sensor
series is available. No pressure measurement was captured, so this work order
does not establish whether air-delivery pressure changed.

## Outcome

A supervised run began at 11:00. At 11:30, housing temperature was 72.6 °C and
vibration was 2.0 mm/s at load 688. The temperature recovery following cleaning
supported restricted cooling as the cause in this case. Closure was recorded
at 11:45, with a weekly screen inspection added to the fictional maintenance
plan. A later recurrence is not documented.

Refer to [MAN-SIM-003](sim-003-compressor-manual.md) for measurement location and
normal ranges. Do not apply the 72.6 °C post-repair observation as a universal
temperature target for the conveyor or pump.
