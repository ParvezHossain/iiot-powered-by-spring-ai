import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, inject } from '@angular/core';
import { MachineStatus, MachineOperatingState } from '../../core/models';
import { METRICS, TelemetryStore } from './telemetry.store';

@Component({
  selector: 'app-machine-grid',
  host: { class: 'block min-w-0' },
  imports: [DecimalPipe, DatePipe],
  templateUrl: './machine-grid.component.html',
})
export class MachineGridComponent {
  protected readonly store = inject(TelemetryStore);
  protected readonly metrics = METRICS;
  protected readonly statusClass: Record<MachineOperatingState, string> = {
    RUNNING: 'text-status-running border-status-running/30 bg-status-running/10',
    IDLE: 'text-status-warning border-status-warning/30 bg-status-warning/10',
    MAINTENANCE: 'text-status-warning border-status-warning/30 bg-status-warning/10',
    FAULTED: 'text-status-critical border-status-critical/30 bg-status-critical/10',
    STOPPED: 'text-slate-400 border-muted bg-deep', OFFLINE: 'text-slate-400 border-muted bg-deep',
  };
  protected latest(machine: MachineStatus, metric: string) {
    return machine.latestReadings.find(reading => reading.metricType === metric && Number.isFinite(reading.value));
  }
}
