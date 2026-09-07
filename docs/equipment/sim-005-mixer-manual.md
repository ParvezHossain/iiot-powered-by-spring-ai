---
document_id: MAN-SIM-005
document_type: equipment_manual
machine_ids: [SIM-005]
revision: 1
updated_at: 2026-09-01
synthetic: true
---

# SIM-005 — MX-25 batch mixer

## Measurement context

Synthetic training document for the fictional MX-25 mixer on Virtual factory /
line 2. SIM-005 blends an inert training material in repeatable batches.
Temperature refers to the drive gearbox housing, not the product. Vibration is
gearbox housing velocity RMS. Recipe, viscosity, torque, and batch mass are not
measured by the current simulator.

## Expected batch behavior

The fictional loaded range is 55–74 °C and 1.3–2.7 mm/s. A denser batch can
increase load and energy consumption, but the load register alone cannot prove
a viscosity change. Compare the same recipe and batch size using external batch
records. A ten-minute temperature above 80 °C is a training maintenance watch;
the application temperature anomaly threshold remains strictly greater than
90 °C without a duration requirement.

To estimate average power, subtract two `energy_kwh` samples and divide by the
elapsed hours. For example, an increase from 12.400 to 12.450 kWh in one minute
is 3.0 kW average. This describes that interval, not rated motor power. Do not
use samples across a meter reset or unavailable interval without qualification.

## Overload investigation

For a local `M-501` complaint, compare the load trend with the batch record,
then review housing temperature and vibration. Persistent vibration after the
batch is removed suggests that material resistance alone may not explain the
problem. Stop and isolate the fictional mixer before inspecting the vessel,
agitator, or coupling; a clear alarm does not establish an empty or safe vessel.

The scenario verification sequence uses an unloaded observation followed by
one reference batch. Document the batch identifier and compare the complete
temperature, vibration, and energy trend with a prior reference batch. No
historical SIM-005 maintenance event is supplied in this corpus.

See [REF-FAULTS-001](fault-code-reference.md) for `M-501` and
[REF-REGISTERS-001](telemetry-register-reference.md) for energy counter semantics.
