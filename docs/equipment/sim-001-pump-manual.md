---
document_id: MAN-SIM-001
document_type: equipment_manual
machine_ids: [SIM-001]
revision: 1
updated_at: 2026-09-01
synthetic: true
---

# SIM-001 — CP-40 coolant circulation pump

## Purpose and measurement locations

Synthetic training document for the fictional CP-40 pump on Virtual factory /
line 1. SIM-001 circulates coolant through a machining-cell heat exchanger.
`temperature_celsius` represents the motor drive-end bearing housing, not coolant
temperature. `vibration_mm_s` represents housing vibration velocity RMS.
`energy_kwh` is the cumulative electrical energy counter.

## Expected operation

For this fictional equipment profile, stabilized bearing temperature is 52–68 °C
and vibration is 1.2–2.4 mm/s at a comparable load. A gradual temperature rise
during warm-up is expected. Compare readings at similar `modbus_hr_40003` load
values before attributing a change to wear. An isolated temperature jump with
normal vibration may indicate a sensor problem; sustained heat with increased
vibration warrants a mechanical inspection.

The training maintenance watch level is temperature above 75 °C for ten minutes
or vibration above 3.5 mm/s for five minutes. These are fictional review criteria,
not configured application alarms. The query API uses >90 °C and >5 mm/s.

## Inspection and recovery

At the start of each fictional shift, review temperature and vibration trends,
check for reported leaks, and compare energy consumption over equal operating
intervals. For a cavitation complaint, record coolant level and suction-strainer
condition from inspection; these quantities are not available in raw telemetry.
Do not conclude cavitation from vibration alone.

For hands-on maintenance in the scenario, stop and isolate the equipment before
opening the strainer or coupling guard. Record the obstruction found, restore
the assembly, and compare a supervised thirty-minute run at the previous load.
Recovery requires temperature and vibration to return to the profile range with
no recurrence of the complaint.

## Related evidence

The clogged-strainer case is recorded in [LOG-SIM-001](sim-001-pump-maintenance.md).
The fictional local code `P-101` is defined in
[REF-FAULTS-001](fault-code-reference.md). A local code is not automatically
emitted by the simulator.
