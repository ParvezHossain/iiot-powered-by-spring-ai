---
document_id: MAN-SIM-004
document_type: equipment_manual
machine_ids: [SIM-004]
revision: 1
updated_at: 2026-09-01
synthetic: true
---

# SIM-004 — EF-18 extraction fan

## Application and baseline

Synthetic training document for the fictional EF-18 extraction fan on Virtual
factory / line 1. SIM-004 extracts air from an enclosed finishing station.
`temperature_celsius` is the motor housing reading and `vibration_mm_s` is the
fan support housing velocity RMS. No airflow or filter differential-pressure
sensor is represented in the current simulator.

The fictional stabilized baseline is 51–69 °C and 1.1–2.5 mm/s. A maintenance
watch is vibration above 3.8 mm/s for five minutes at comparable load. That watch
is a document rule only; the API flags individual samples above 5 mm/s.

## Troubleshooting elevated vibration

A progressive rise in vibration with little temperature change can accompany
uneven deposits on the impeller, looseness, or a damaged support. The scalar RMS
reading cannot distinguish those causes. A sudden change following cleaning
should prompt review of what was disturbed during the work. Do not diagnose
imbalance from one simulator spike: injected spikes also increase temperature
and carry simulator quality flag 1.

In the fictional inspection procedure, stop and isolate the fan before opening
access panels. Document deposits, mounting condition, and any contact marks.
Remove the unit from the training return-to-service sequence if visible damage
is found. Cleaning or replacing components requires a recorded maintenance
action; clearing an alert alone is not a repair.

## Verification and evidence limits

Compare a twenty-minute run before and after the action at a matched load.
Recovery means vibration returns to 1.1–2.5 mm/s without recurring noise and the
housing temperature remains in range. A normal vibration value does not prove
adequate extraction; independent airflow evidence is required for that claim.

Local code `F-401` means vibration review requested; it is defined in
[REF-FAULTS-001](fault-code-reference.md). No completed SIM-004 repair log is
included in this corpus, so retrieval should not invent a previous repair date.
