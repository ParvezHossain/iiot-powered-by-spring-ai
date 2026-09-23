import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../../core/auth.service';
import { SystemHealthService, SystemHealth } from '../../core/system-health.service';
import { ANOMALY_DEMO_ENABLED, AnomalyFeedStore, isCritical } from './anomaly-feed.store';
import { demoAnomalies } from './anomaly-demo';

describe('Anomaly feed', () => {
  let token: ReturnType<typeof signal<string | null>>;
  let health: ReturnType<typeof signal<SystemHealth>>;
  let store: AnomalyFeedStore;
  let http: HttpTestingController;
  function settle() { TestBed.tick(); vi.advanceTimersByTime(0); TestBed.tick(); }
  function setup(demo = true) {
    token = signal<string | null>(null);
    health = signal<SystemHealth>('up');
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), AnomalyFeedStore,
      { provide: AuthService, useValue: { accessToken: token } },
      { provide: SystemHealthService, useValue: { status: health } },
      { provide: ANOMALY_DEMO_ENABLED, useValue: demo },
    ] });
    store = TestBed.inject(AnomalyFeedStore);
    http = TestBed.inject(HttpTestingController);
    settle();
  }
  const request = () => http.expectOne(req => req.url === '/api/anomalies');
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => { TestBed.resetTestingModule(); http.verify({ ignoreCancelled: true }); vi.useRealTimers(); });

  it('polls only with credentials, sorts and deduplicates alerts, and clears on logout', () => {
    setup();
    http.expectNone(req => req.url.startsWith('/api/'));
    token.set('access'); settle();
    const req = request();
    expect(req.request.params.get('limit')).toBe('100');
    expect(Date.parse(req.request.params.get('to')!) - Date.parse(req.request.params.get('from')!)).toBe(3_600_000);
    const alerts = demoAnomalies(Date.now());
    req.flush([alerts[1], alerts[0], alerts[0]]);
    expect(store.feed().alerts.map(a => a.reading.id)).toEqual([-1, -2]);
    vi.advanceTimersByTime(5000);
    const pending = request();
    token.set(null); settle();
    expect(pending.cancelled).toBe(true);
    expect(store.feed().alerts).toEqual([]);
  });

  it('uses labeled development fixtures on disconnection and recovers on the next poll', () => {
    setup(); token.set('access'); settle();
    request().flush({}, { status: 503, statusText: 'Unavailable' });
    expect(store.feed().source).toBe('demo');
    vi.advanceTimersByTime(5000);
    request().flush([]);
    expect(store.feed().source).toBe('live');
    expect(store.feed().alerts).toEqual([]);
  });

  it('never substitutes demo data for access denial', () => {
    setup(); token.set('access'); settle();
    request().flush({}, { status: 403, statusText: 'Forbidden' });
    expect(store.feed().source).toBe('error');
    expect(store.feed().alerts).toEqual([]);
  });

  it('disables offline fixtures in production', () => {
    setup(false); health.set('unavailable'); settle();
    expect(store.feed().source).toBe('idle');
    token.set('access'); settle();
    request().flush({}, { status: 503, statusText: 'Unavailable' });
    expect(store.feed().source).toBe('error');
  });

  it('allows an offline preview without protected requests and avoids overlapping polls', () => {
    setup(); health.set('unavailable'); settle();
    expect(store.feed().source).toBe('demo');
    http.expectNone(req => req.url.startsWith('/api/'));
    token.set('access'); settle();
    const pending = request();
    vi.advanceTimersByTime(5000);
    http.expectNone(req => req.url === '/api/anomalies');
    pending.flush([]);
  });

  it('assigns presentation priority to extreme deviations and dropouts', () => {
    setup();
    expect(demoAnomalies(Date.now()).map(isCritical)).toEqual([true, false, true]);
  });
});
