---
document_id: MAN-SIM-003
document_type: equipment_manual
machine_ids: [SIM-003]
revision: 1
updated_at: 2026-09-01
synthetic: true
---

# SIM-003 — AC-7 workshop air compressor

## Scope and signal meaning

Synthetic training document for the fictional AC-7 compressor on Virtual factory
/ line 3. SIM-003 supplies a small pneumatic assembly cell. Temperature is
measured at the motor housing, not at the compressed-air discharge. Vibration
is motor housing velocity RMS. Pressure, air flow, and discharge temperature
are not among the simulator's seven metrics.

## Operating profile

The fictional loaded motor housing range is 58–76 °C with vibration of
1.5–2.8 mm/s. Repeated loading and unloading can produce a repeating temperature
and energy slope. A load cycle alone is not evidence of a pressure fault.
For training review, investigate housing temperature above 82 °C for ten minutes
at a comparable load. The raw query API only identifies temperature >90 °C;
it does not implement this duration or the equipment-specific watch level.

If housing temperature climbs while vibration stays near its previous level,
inspect the cooling-air path before assuming bearing damage. A blocked intake
screen is one possible cause, but ambient conditions and sensor accuracy must
also be checked. Ambient temperature is an inspection observation, not a stored
simulator metric.

## Maintenance and return to service

The fictional weekly review covers cooling-path cleanliness, reported leaks,
and temperature at matched load. Isolate electrical and stored pneumatic energy
before physical maintenance. Record the condition of the cooling screen and
any material removed. Never interpret a register dropout as proof that the
compressor has cooled.

After restoring the system, observe a thirty-minute representative run. The
scenario recovery criterion is temperature at or below 76 °C and vibration
within the stated range, with no repeated local `A-301` complaint. A pressure
complaint requires separate pressure evidence; these documents provide no
pressure trip setting.

See [LOG-SIM-003](sim-003-compressor-maintenance.md) for the cooling-screen case
and [REF-REGISTERS-001](telemetry-register-reference.md) for dropout interpretation.
