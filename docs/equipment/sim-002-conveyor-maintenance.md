---
document_id: LOG-SIM-002
document_type: maintenance_log
machine_ids: [SIM-002]
revision: 1
updated_at: 2026-08-21
synthetic: true
---

# SIM-002 — Conveyor belt tracking correction

## Work order WO-260821-02

Synthetic maintenance history for BC-12, SIM-002, Virtual factory / line 2.
All observations occurred on 2026-08-21 in UTC and are document-only examples.
The fictional local complaint code was `C-201`.

At 13:05, the operator reported rubbing on the left belt edge. Gearbox vibration
was 3.7 mm/s, up from 1.6 mm/s on the prior reference batch; temperature was
68.0 °C, up from 59.0 °C. Load register 40003 remained near 580. Counter readings
of 18.200 kWh at 12:55 and 18.650 kWh at 13:05 imply an average 2.7 kW during
that ten-minute interval. The higher counter value itself was not a fault.

## Inspection and correction

The conveyor was stopped and isolated at 13:15. Inspection found left-edge wear
and an uneven tracking position. No seized idler was found. The training crew
corrected tracking and recorded the belt's condition for replacement planning;
the gearbox was not replaced. These physical findings distinguish this case
from the pump obstruction described in LOG-SIM-001.

## Verification

After an unloaded observation, a representative carton batch ran from 14:00
to 14:20. Vibration settled at 1.7 mm/s and temperature at 60.5 °C, with load
near 575 and no reported rubbing. Work order closure was recorded at 14:30.
The open follow-up was a belt-edge condition check on 2026-08-28; no later result
is supplied. The 3.7 mm/s pre-repair reading would not appear in the generic
API anomaly list because its vibration rule requires a value above 5 mm/s.

See [MAN-SIM-002](sim-002-conveyor-manual.md) for the conveyor-specific watch
level and verification procedure.
