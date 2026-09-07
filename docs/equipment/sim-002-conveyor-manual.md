---
document_id: MAN-SIM-002
document_type: equipment_manual
machine_ids: [SIM-002]
revision: 1
updated_at: 2026-09-01
synthetic: true
---

# SIM-002 — BC-12 packaging conveyor

## Equipment and sensors

Synthetic training document for the fictional BC-12 belt conveyor on Virtual
factory / line 2. SIM-002 transfers cartons from inspection to packing.
`temperature_celsius` measures the gearbox housing; `vibration_mm_s` describes
gearbox vibration velocity RMS. Neither metric measures belt tension directly.

## Normal profile and load changes

The fictional steady-load range is 50–66 °C and 1.0–2.2 mm/s. During a carton
surge, load and energy use can rise together without a fault. Compare cumulative
`energy_kwh` differences over equal durations rather than comparing counter
levels between machines. A higher total counter does not establish inefficiency.

For training maintenance review, flag vibration above 3.0 mm/s for ten minutes at
unchanged load. This watch level is separate from the API's >5 mm/s anomaly rule.
Elevated gearbox temperature with rubbing reported at one belt edge suggests
tracking should be inspected. High vibration without belt-edge wear has other
possible causes, including a loose mounting or gearbox issue.

## Maintenance workflow

Review the previous shift's load history and any carton-jam report. Stop and
isolate the fictional conveyor before inspecting belt tracking, idlers, or the
drive guard. Record whether wear is centered or confined to one edge. A missing
guard or unresolved jam prevents the supervised verification run.

After correction, perform an unloaded observation followed by a representative
carton batch. In this scenario, acceptance is no visible edge rubbing and a
twenty-minute vibration trend within 1.0–2.2 mm/s. Do not use a single low sample
as evidence of recovery. Part numbers and adjustment torques are deliberately
outside this synthetic document's scope.

## References

See [LOG-SIM-002](sim-002-conveyor-maintenance.md) for a belt-tracking case and
[REF-FAULTS-001](fault-code-reference.md) for local code `C-201`. SIM-002's lower
watch level must not be applied as a universal threshold to the other profiles.
