---
document_id: LOG-SIM-001
document_type: maintenance_log
machine_ids: [SIM-001]
revision: 1
updated_at: 2026-08-18
synthetic: true
---

# SIM-001 — Coolant pump strainer obstruction

## Work order WO-260818-01

This is a synthetic completed maintenance record for CP-40, SIM-001, Virtual
factory / line 1. All times are UTC on 2026-08-18. It is narrative retrieval
data, not a record inserted into `machine_events` or `sensor_readings`.

At 08:10, the operator reported a rattling sound and reduced visible coolant
return. At 08:15, housing temperature was 78.2 °C and vibration was 4.2 mm/s,
compared with the preceding shift's 61.5 °C and 1.8 mm/s. Load register 40003
was approximately 610 during both observations. The fictional local complaint
code was `P-101`. Neither 78.2 °C nor 4.2 mm/s exceeds the current API's generic
thresholds, despite exceeding this pump's maintenance watch levels.

## Findings and action

At 08:25, the pump was stopped and isolated for inspection. The suction strainer
contained accumulated training debris; the coupling inspection found no visible
damage. The strainer was cleaned and its condition recorded. No bearing was
replaced. Cavitation associated with restricted suction was the technician's
working explanation, supported by the obstruction and coolant-return observation;
the scalar telemetry alone did not establish that diagnosis.

## Verification and follow-up

A supervised run began at 09:00. At 09:30, temperature was 63.1 °C and vibration
was 1.9 mm/s at load 605. Rattling was no longer reported and coolant return
appeared restored. The work order closed at 09:40 with a strainer reinspection
assigned for 2026-08-25. That follow-up's outcome is not included here.

Use [MAN-SIM-001](sim-001-pump-manual.md) for operating context. This case does
not establish that every high-vibration pump event requires strainer cleaning.
