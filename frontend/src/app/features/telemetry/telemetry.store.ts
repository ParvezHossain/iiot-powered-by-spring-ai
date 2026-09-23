import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { toObservable, toSignal } from '@angular/core/rxjs-interop';
import { EMPTY, catchError, exhaustMap, expand, map, of, reduce, startWith, switchMap, throwError, timeout, timer } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { MachineStatus, SensorReading, TelemetryQueryParams } from '../../core/models';

export const METRICS = [
  { key: 'vibration_mm_s', label: 'Vibration', unit: 'mm/s', digits: '1.1-2', decimals: 2, color: '#a78bfa' },
  { key: 'temperature_celsius', label: 'Temperature', unit: '°C', digits: '1.1-2', decimals: 2, color: '#fbbf24' },
  { key: 'energy_kwh', label: 'Energy', unit: 'kWh', digits: '1.3-5', decimals: 5, color: '#34d399' },
] as const;
export const CHART_METRICS = [...METRICS, { key: 'modbus_hr_40001', label: 'Sensor register', unit: 'raw', digits: '1.0-0', decimals: 0, color: '#f87171' }] as const;
export type Metric = typeof CHART_METRICS[number]['key'];
export type LoadState<T> = { status: 'idle' | 'loading' | 'ready' | 'error'; data: T; error: string; updatedAt: number | null };
function state<T>(status: LoadState<T>['status'], data: T, error = ''): LoadState<T> {
  return { status, data, error, updatedAt: status === 'ready' ? Date.now() : null };
}
function errorMessage(error: unknown, discovery = false): string {
  if (error instanceof RangeError) return 'Too many readings. Choose a shorter time window.';
  if (error instanceof HttpErrorResponse && error.status === 403) return 'Access to telemetry was denied.';
  if (error instanceof HttpErrorResponse && error.status === 404) return discovery
    ? 'Machine discovery is unavailable on this server. Please contact your administrator.'
    : 'Telemetry is unavailable. The selected machine may have been removed.';
  return 'Unable to load telemetry. Retrying automatically.';
}

/** Scoped to the dashboard: all polling is disposed when that view is destroyed. */
@Injectable()
export class TelemetryStore {
  readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);
  private readonly chosenMachine = signal<string | null>(null);
  private readonly chosenMetric = signal<Metric>('vibration_mm_s');
  private readonly chosenWindow = signal(10);
  private readonly focused = signal<{ reading: SensorReading; demo: boolean; token: string | null } | null>(null);
  readonly anomalyFocus = computed(() => {
    const focus = this.focused();
    return focus?.token === this.auth.accessToken() ? focus : null;
  });
  readonly metric = this.chosenMetric.asReadonly();
  readonly windowMinutes = this.chosenWindow.asReadonly();
  readonly metricInfo = computed(() => CHART_METRICS.find(metric => metric.key === this.metric())!);

  readonly fleet = toSignal(toObservable(this.auth.accessToken).pipe(
    switchMap(token => token ? timer(0, 5_000).pipe(
      exhaustMap(() => this.http.get<MachineStatus[]>('/api/machines').pipe(
        timeout(8_000),
        map(machines => state('ready', machines)),
        catchError(error => of(state<MachineStatus[]>('error', [], errorMessage(error, true)))),
      )),
      startWith(state<MachineStatus[]>('loading', [])),
    ) : of(state<MachineStatus[]>('idle', []))),
  ), { initialValue: state<MachineStatus[]>('idle', []) });

  readonly selectedMachineId = computed(() => {
    if (this.anomalyFocus()) return this.anomalyFocus()!.reading.machineId;
    const machines = this.fleet().data;
    return machines.find(machine => machine.id === this.chosenMachine())?.id ?? machines[0]?.id ?? null;
  });
  readonly selectedMachine = computed(() => this.fleet().data.find(machine => machine.id === this.selectedMachineId()) ?? null);
  private readonly selection = computed(() => ({ token: this.auth.accessToken(), id: this.selectedMachineId(), metric: this.metric(), minutes: this.windowMinutes(), focus: this.anomalyFocus() }));

  readonly history = toSignal(toObservable(this.selection).pipe(
    // A changed selection/authentication cancels the previous request, including pagination.
    switchMap(selection => selection.focus?.demo ? of(state('ready', [selection.focus.reading])) : selection.token && selection.id ? timer(0, 5_000).pipe(
      exhaustMap(() => {
        const to = selection.focus ? new Date(Date.parse(selection.focus.reading.timestamp) + selection.minutes * 30_000) : new Date();
        const from = new Date(to.getTime() - selection.minutes * 60_000);
        return this.readings(selection.id!, { from: from.toISOString(), to: to.toISOString(), metricType: selection.metric }).pipe(
          timeout(12_000),
          map(readings => state('ready', readings)),
          catchError(error => of(state<SensorReading[]>('error', [], errorMessage(error)))),
        );
      }),
      startWith(state<SensorReading[]>('loading', [])),
    ) : of(state<SensorReading[]>('idle', []))),
  ), { initialValue: state<SensorReading[]>('idle', []) });

  selectMachine(id: string): void {
    if (this.fleet().data.some(machine => machine.id === id)) { this.resumeLive(); this.chosenMachine.set(id); }
  }
  selectMetric(metric: Metric): void { this.resumeLive(); this.chosenMetric.set(metric); }
  selectWindow(minutes: number): void { if ([5, 10, 30].includes(minutes)) this.chosenWindow.set(minutes); }

  resumeLive(): void { this.focused.set(null); }
  focusAnomaly(reading: SensorReading, demo = false): boolean {
    if (!CHART_METRICS.some(metric => metric.key === reading.metricType) || !Number.isFinite(Date.parse(reading.timestamp)) || (!demo && !this.auth.accessToken())) return false;
    this.chosenMetric.set(reading.metricType as Metric);
    this.focused.set({ reading, demo, token: this.auth.accessToken() });
    return true;
  }

  private readings(id: string, query: TelemetryQueryParams) {
    const page = (offset: number) => this.http.get<SensorReading[]>(`/api/machines/${encodeURIComponent(id)}/readings`, {
      params: { ...query, limit: 1000, offset },
    });
    // Fix the time bounds across all pages; the API sorts oldest first.
    return page(0).pipe(
      expand((readings, index) => readings.length < 1000 ? EMPTY
        : index >= 9 ? throwError(() => new RangeError('Reading limit')) : page((index + 1) * 1000), 1),
      reduce((all, readings) => all.concat(readings), [] as SensorReading[]),
      map(readings => [...new Map(readings.map(reading => [reading.id, reading])).values()]
        .filter(reading => Number.isFinite(reading.value) && Number.isFinite(Date.parse(reading.timestamp)))
        .sort((a, b) => Date.parse(a.timestamp) - Date.parse(b.timestamp) || a.id - b.id)),
    );
  }
}
