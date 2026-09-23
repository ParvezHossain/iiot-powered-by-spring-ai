import { TestBed } from '@angular/core/testing';
import { computed, signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../core/auth.service';
import { MachineStatus, SensorReading } from '../../core/models';
import { TelemetryStore } from './telemetry.store';

const machines: MachineStatus[] = ['one', 'two'].map((id, index) => ({
  id, name: `SIM-00${index + 1}`, location: null, status: 'RUNNING', latestReadings: [],
}));
const reading = (id: number, value = 5): SensorReading => ({
  id, machineId: 'one', metricType: 'vibration_mm_s', value, timestamp: new Date(1_800_000_000_000 + id * 1000).toISOString(),
});

describe('Telemetry polling and shared selection', () => {
  let token: ReturnType<typeof signal<string | null>>;
  let store: TelemetryStore;
  let http: HttpTestingController;
  function settle() { TestBed.tick(); vi.advanceTimersByTime(0); TestBed.tick(); }
  function signIn() {
    token.set('access'); settle();
    http.expectOne('/api/machines').flush(machines); settle();
  }
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2027-01-15T08:10:00Z'));
    token = signal<string | null>(null);
    TestBed.configureTestingModule({ providers: [
      provideHttpClient(), provideHttpClientTesting(), TelemetryStore,
      { provide: AuthService, useValue: { accessToken: token, isAuthenticated: computed(() => !!token()), openLogin: vi.fn() } },
    ] });
    store = TestBed.inject(TelemetryStore);
    http = TestBed.inject(HttpTestingController);
    settle();
  });
  afterEach(() => { TestBed.resetTestingModule(); http.verify({ ignoreCancelled: true }); vi.useRealTimers(); });

  it('does not poll while signed out and selects the first discovered UUID on sign-in', () => {
    vi.advanceTimersByTime(15_000);
    http.expectNone('/api/machines');
    signIn();
    expect(store.selectedMachineId()).toBe('one');
    const req = http.expectOne(request => request.url === '/api/machines/one/readings');
    expect(req.request.params.get('metricType')).toBe('vibration_mm_s');
    expect(req.request.params.get('limit')).toBe('1000');
    expect(Date.parse(req.request.params.get('to')!) - Date.parse(req.request.params.get('from')!)).toBe(600_000);
    req.flush([reading(1)]);
    expect(store.history().data.length).toBe(1);
  });

  it('cancels in-flight readings on machine/metric/window changes', () => {
    signIn();
    const old = http.expectOne(request => request.url.endsWith('/one/readings'));
    store.selectMachine('two'); settle();
    expect(old.cancelled).toBe(true);
    const second = http.expectOne(request => request.url.endsWith('/two/readings'));
    store.selectMetric('temperature_celsius'); settle();
    expect(second.cancelled).toBe(true);
    const third = http.expectOne(request => request.params.get('metricType') === 'temperature_celsius');
    store.selectWindow(30); settle();
    expect(third.cancelled).toBe(true);
    const fourth = http.expectOne(request => request.url.endsWith('/two/readings'));
    expect(Date.parse(fourth.request.params.get('to')!) - Date.parse(fourth.request.params.get('from')!)).toBe(1_800_000);
    fourth.flush([]);
    expect(store.history().status).toBe('ready');
    expect(store.history().data).toEqual([]);
  });

  it('keeps time bounds fixed while paginating beyond 1000 readings', () => {
    signIn();
    const first = http.expectOne(request => request.url.endsWith('/readings'));
    const firstBounds = first.request.params.get('to');
    first.flush(Array.from({ length: 1000 }, (_, index) => reading(index)));
    const next = http.expectOne(request => request.url.endsWith('/readings'));
    expect(next.request.params.get('offset')).toBe('1000');
    expect(next.request.params.get('to')).toBe(firstBounds);
    next.flush([reading(1000)]);
    expect(store.history().data.length).toBe(1001);
    expect(store.history().data.at(-1)?.id).toBe(1000);
  });

  it('recovers on the next poll after a failure and retains the selected machine', () => {
    signIn();
    store.selectMachine('two'); settle();
    const pending = http.match(request => request.url.endsWith('/readings'));
    pending.filter(request => !request.cancelled).forEach(request => request.flush({}, { status: 503, statusText: 'Unavailable' }));
    expect(store.history().status).toBe('error');
    vi.advanceTimersByTime(5000); TestBed.tick();
    http.expectOne('/api/machines').flush(machines); settle();
    http.expectOne(request => request.url.endsWith('/two/readings')).flush([reading(2)]);
    expect(store.history().status).toBe('ready');
    expect(store.selectedMachineId()).toBe('two');
  });

  it('clears protected data and cancels polling on logout', () => {
    signIn();
    const req = http.expectOne(request => request.url.endsWith('/readings'));
    token.set(null); settle();
    expect(req.cancelled).toBe(true);
    expect(store.fleet().data).toEqual([]);
    expect(store.history().data).toEqual([]);
    expect(store.selectedMachineId()).toBeNull();
    vi.advanceTimersByTime(10_000);
    http.expectNone(request => request.url.startsWith('/api/'));
  });

  it('focuses an old anomaly with fixed bounds and resumes live polling', () => {
    signIn();
    const previous = http.expectOne(request => request.url.endsWith('/readings'));
    const alert = { ...reading(7), machineId: 'two', timestamp: '2027-01-15T07:30:00Z' };
    expect(store.focusAnomaly(alert)).toBe(true); settle();
    expect(previous.cancelled).toBe(true);
    const focused = http.expectOne(request => request.url.endsWith('/two/readings'));
    expect(focused.request.params.get('from')).toBe('2027-01-15T07:25:00.000Z');
    expect(focused.request.params.get('to')).toBe('2027-01-15T07:35:00.000Z');
    focused.flush([alert]);
    store.resumeLive(); settle();
    const live = http.expectOne(request => request.url.endsWith('/one/readings'));
    expect(live.request.params.get('to')).toBe('2027-01-15T08:10:00.000Z');
    live.flush([]);
  });

  it('keeps demo samples local and clears focus on authentication changes', () => {
    expect(store.focusAnomaly(reading(-1), true)).toBe(true); settle();
    expect(store.history().data[0].id).toBe(-1);
    http.expectNone(request => request.url.startsWith('/api/'));
    signIn();
    expect(store.anomalyFocus()).toBeNull();
    http.expectOne(request => request.url.endsWith('/readings')).flush([]);
  });

  it('handles an empty fleet without requesting invented machine IDs', () => {
    token.set('access'); settle();
    http.expectOne('/api/machines').flush([]); settle();
    expect(store.selectedMachineId()).toBeNull();
    http.expectNone(request => request.url.endsWith('/readings'));
  });

  it('does not overlap requests while a poll is still pending', () => {
    signIn();
    const pending = http.expectOne(request => request.url.endsWith('/readings'));
    vi.advanceTimersByTime(5000); TestBed.tick();
    http.expectOne('/api/machines').flush(machines); settle();
    http.expectNone(request => request.url.endsWith('/readings'));
    pending.flush([reading(1)]);
  });

  it('shows fleet errors and recovers without stopping the timer', () => {
    token.set('access'); settle();
    http.expectOne('/api/machines').flush({}, { status: 500, statusText: 'Failure' }); settle();
    expect(store.fleet().status).toBe('error');
    vi.advanceTimersByTime(5000); TestBed.tick();
    http.expectOne('/api/machines').flush(machines); settle();
    http.expectOne(request => request.url.endsWith('/readings')).flush([]);
    expect(store.fleet().status).toBe('ready');
  });
});
