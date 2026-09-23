import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {Injectable, InjectionToken, computed, inject, isDevMode} from '@angular/core';
import {toObservable, toSignal} from '@angular/core/rxjs-interop';
import {TimeoutError, catchError, exhaustMap, map, of, startWith, switchMap, timeout, timer} from 'rxjs';
import {AuthService} from '../../core/auth.service';
import {SystemHealthService} from '../../core/system-health.service';
import {AnomalyAlert} from '../../core/models';
import {demoAnomalies} from './anomaly-demo';

export const ANOMALY_DEMO_ENABLED = new InjectionToken<boolean>('Development anomaly demo', {factory: () => isDevMode()});

export interface AnomalyFeedState {
    source: 'idle' | 'loading' | 'live' | 'demo' | 'error';
    alerts: AnomalyAlert[];
    message: string;
    updatedAt: number | null;
}

const empty = (source: AnomalyFeedState['source'], message = ''): AnomalyFeedState => ({
    source,
    alerts: [],
    message,
    updatedAt: null
});

/** Presentation priority, not a backend safety or machine-health classification. */
export function isCritical(alert: AnomalyAlert): boolean {
    return alert.reason === 'SENSOR_DROPOUT' || (alert.baseline !== null && alert.baseline.threshold > 0 &&
        Math.abs(alert.baseline.zScore) >= 2 * alert.baseline.threshold);
}

@Injectable()
export class AnomalyFeedStore {
    private readonly auth = inject(AuthService);
    private readonly health = inject(SystemHealthService);
    private readonly http = inject(HttpClient);
    private readonly allowDemo = inject(ANOMALY_DEMO_ENABLED);
    private readonly fixtures = demoAnomalies(Date.now());
    private readonly availability = computed(() => ({token: this.auth.accessToken(), health: this.health.status()}));
    readonly feed = toSignal(toObservable(this.availability).pipe(
        switchMap(({token, health}) => {
            // An offline development preview contains only fixtures and makes no protected requests.
            if (!token) return of(this.allowDemo && health === 'unavailable' ? this.demo() : empty('idle'));
            return timer(0, 5_000).pipe(
                exhaustMap(() => {
                    const to = new Date();
                    return this.http.get<AnomalyAlert[]>('/api/anomalies', {
                        params: {
                            from: new Date(to.getTime() - 3_600_000).toISOString(),
                            to: to.toISOString(),
                            limit: 100,
                            offset: 0,
                        }
                    }).pipe(
                        timeout(8_000),
                        map(alerts => ({
                            source: 'live' as const,
                            alerts: [...new Map(alerts.map(alert => [alert.reading.id, alert])).values()]
                                .sort((a, b) => Date.parse(b.reading.timestamp) - Date.parse(a.reading.timestamp) || b.reading.id - a.reading.id),
                            message: '',
                            updatedAt: Date.now()
                        })),
                        catchError((error: unknown) => {
                            const disconnected = error instanceof TimeoutError || error instanceof HttpErrorResponse && [0, 502, 503, 504].includes(error.status);
                            if (this.allowDemo && disconnected) return of(this.demo());
                            const denied = error instanceof HttpErrorResponse && error.status === 403;
                            return of(empty('error', denied ? 'Access to anomalies was denied.' : 'Unable to load anomalies. Retrying automatically.'));
                        }),
                    );
                }),
                startWith(empty('loading')),
            );
        }),
    ), {initialValue: empty('idle')});

    private demo(): AnomalyFeedState {
        return {
            source: 'demo',
            alerts: this.fixtures,
            message: 'Demo alerts — synthetic examples, not live machine data. Backend unavailable.',
            updatedAt: null
        };
    }
}
