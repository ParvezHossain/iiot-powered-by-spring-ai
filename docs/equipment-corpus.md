# Synthetic equipment corpus

T2.1 provides ten Markdown source documents under [`equipment/`](equipment/)
for [RAG ingestion](rag-ingestion.md). All equipment models, maintenance events, work orders,
and equipment-specific operating ranges are invented training data. The shared
telemetry and API descriptions reflect this project's implementation. These
files are not manufacturer instructions or actual service records.

## Source inventory

| Document ID | Machine | Topic | Source |
| --- | --- | --- | --- |
| MAN-SIM-001 | SIM-001 | CP-40 coolant pump operation and cavitation investigation | [Manual](equipment/sim-001-pump-manual.md) |
| MAN-SIM-002 | SIM-002 | BC-12 conveyor tracking and gearbox monitoring | [Manual](equipment/sim-002-conveyor-manual.md) |
| MAN-SIM-003 | SIM-003 | AC-7 compressor cooling and load cycles | [Manual](equipment/sim-003-compressor-manual.md) |
| MAN-SIM-004 | SIM-004 | EF-18 extraction fan vibration and evidence limits | [Manual](equipment/sim-004-fan-manual.md) |
| MAN-SIM-005 | SIM-005 | MX-25 mixer batch comparison and energy interpretation | [Manual](equipment/sim-005-mixer-manual.md) |
| LOG-SIM-001 | SIM-001 | Strainer obstruction, inspection, repair, verification | [Maintenance log](equipment/sim-001-pump-maintenance.md) |
| LOG-SIM-002 | SIM-002 | Belt tracking correction and energy calculation | [Maintenance log](equipment/sim-002-conveyor-maintenance.md) |
| LOG-SIM-003 | SIM-003 | Cooling screen restriction with temperature-only anomaly | [Maintenance log](equipment/sim-003-compressor-maintenance.md) |
| REF-FAULTS-001 | SIM-001–005 | Local complaint codes, API reasons, simulator events | [Error-code reference](equipment/fault-code-reference.md) |
| REF-REGISTERS-001 | SIM-001–005 | Register scaling, missing samples, cumulative energy | [Telemetry reference](equipment/telemetry-register-reference.md) |

## Ingestion conventions

Use `docs/equipment/*.md` as the source glob. Keep this index and the retrieval
checks below outside the source corpus to avoid indexing expected answers.
Each source has YAML front matter with a unique `document_id`, `document_type`,
`machine_ids`, `revision`, `updated_at`, and `synthetic: true`. Preserve that
metadata, the relative source path, and section heading with each future chunk
so answers can cite the source and filter by machine. Headings delimit coherent
sections, and tables keep codes adjacent to their meanings.

Machine names match the simulator's first five labels; equipment roles are
document context and are not new machine-type fields in the database. Simulator
locations also match the manuals. Resolve these labels to runtime machine UUIDs
when joining retrieval context to telemetry; labels are not database keys.
The three maintenance logs are not seed rows and do not assert that historical
samples exist in the database. T2.1 supplies source content; the T2.2 ingestion
pipeline packages these files, preserves their metadata, and provides similarity
search. See the [ingestion guide](rag-ingestion.md) to run it.

## Suggested retrieval checks

These are manual evaluation prompts for the future retriever, not executed RAG
tests. Expected evidence is deliberately specific enough to detect mixing machines.

| Question | Expected evidence | Sources |
| --- | --- | --- |
| Why did SIM-001 need maintenance at 4.2 mm/s when the anomaly API showed nothing? | Pump watch is >3.5 mm/s for five minutes; API requires >5; the log records obstruction and a strainer cleaning | MAN-SIM-001, LOG-SIM-001, REF-FAULTS-001 |
| Was the conveyor gearbox replaced on August 21? | No; tracking was corrected, gearbox was not replaced, post-repair vibration was 1.7 mm/s | LOG-SIM-002 |
| Which repair reduced SIM-003 temperature from 92.4 to 72.6 °C? | Cooling screen cleaning; no bearing or sensor replacement | LOG-SIM-003 |
| Does SIM-003 telemetry measure compressed-air discharge temperature? | No; it measures motor housing temperature and has no pressure/discharge-temperature metric | MAN-SIM-003 |
| How should a SIM-004 high-vibration complaint be investigated? | Review deposits and mounting condition; RMS alone cannot identify imbalance; no historical repair is supplied | MAN-SIM-004, REF-FAULTS-001 |
| What power does a mixer counter increase of 0.050 kWh in one minute imply? | 3.0 kW average for that interval, not rated power | MAN-SIM-005 |
| Why does status show an old temperature alongside register 65535? | Latest values are per metric; dropout omits temperature; 65535 is unavailable, not a scaled temperature | REF-REGISTERS-001 |
| Are A-301 and HIGH_TEMPERATURE the same stored code? | A-301 is a fictional local complaint; HIGH_TEMPERATURE is a query-derived reason | REF-FAULTS-001 |
| What does error E204 mean? | Motor temperature sensor signal unavailable on fictional SIM-003; a missing or invalid measurement, not overheating | REF-FAULTS-001 |
| Does a simulated fault change RUNNING to FAULTED? | No automatic status change is performed by the simulator | REF-FAULTS-001 |
| When was the SIM-005 agitator last replaced? | Unknown; no SIM-005 historical maintenance event or replacement date is supplied | MAN-SIM-005 |

Useful negative checks include requests for pressure trip settings, adjustment
torques, and completed follow-up outcomes: the corpus explicitly does not supply
those facts. Retrieval should preserve that uncertainty.
