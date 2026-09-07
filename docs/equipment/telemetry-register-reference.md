---
document_id: REF-REGISTERS-001
document_type: telemetry_reference
machine_ids: [SIM-001, SIM-002, SIM-003, SIM-004, SIM-005]
revision: 1
updated_at: 2026-09-01
synthetic: true
---

# Telemetry units and MODBUS-style register map

## Scope

This synthetic training reference describes the implemented simulator encoding
shared by SIM-001 through SIM-005 and any additional virtual machines. Register
names are MODBUS-style labels stored as metric strings, not a network protocol
interface. No device address, function code, or register-write operation is
provided by the application.

## Register decoding

| Metric | Encoding | Example |
| --- | --- | --- |
| modbus_hr_40001 | Rounded temperature in tenths of °C; 65535 is unavailable | 637 means approximately 63.7 °C |
| modbus_hr_40002 | Rounded vibration in hundredths of mm/s | 185 means approximately 1.85 mm/s |
| modbus_hr_40003 | Rounded load fraction multiplied by 1000 | 620 means approximately 62% load |
| modbus_hr_40004 | Enumerated sample quality: 0 normal, 1 injected spike, 2 temperature dropout | 2 identifies a dropout batch |

All register samples are integer-valued numbers in the unsigned 16-bit range.
Register 40004 is an enumeration in this implementation; do not combine its
values as independent bits. Check for the 65535 sentinel before scaling register
40001. It does not mean 6553.5 °C. Rounded register values can differ slightly
from the unrounded engineering-unit readings.

## Missing temperature and latest status

During an injected dropout, `temperature_celsius` is absent for that machine
and batch, while vibration, energy, and all four registers remain present.
The status endpoint returns the latest sample independently for each metric.
Consequently, its temperature may be older than its dropout register. Compare
timestamps before presenting that last temperature as a current measurement.
No zero-valued temperature is inserted to fill the gap.

## Energy and reproducibility

`energy_kwh` is cumulative and has no corresponding register in this map.
For two valid samples, average kW equals the kWh difference divided by elapsed
hours. Within one simulator run, energy generally increases with elapsed time
and load. With persistent storage, a restart resumes the last counter value;
the generator caps the integration interval so it does not invent consumption
for downtime. A fresh in-memory database loses the previous counter history.

Default generation uses five machines and a five-second delay between batches.
Every twelfth batch affects one machine, alternating spike and dropout. A seed
controls noise and machine selection, while wall-clock time also affects load;
the seed alone does not reproduce an identical timestamped dataset.

The equipment roles and maintenance histories in this corpus are fictional
retrieval context. The simulator uses a shared mathematical model, not distinct
pump, conveyor, compressor, fan, and mixer physics. See
[REF-FAULTS-001](fault-code-reference.md) for event and anomaly distinctions.
