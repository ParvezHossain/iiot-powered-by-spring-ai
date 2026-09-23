import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {Injectable, inject} from '@angular/core';
import {toSignal} from '@angular/core/rxjs-interop';
import {catchError, map, of, switchMap, timer, timeout} from 'rxjs';

export type SystemHealth = 'checking' | 'up' | 'down' | 'unavailable';

@Injectable({providedIn: 'root'})
export class SystemHealthService {
    private readonly http = inject(HttpClient);

    // Catch inside switchMap so a failed check does not stop subsequent polls.
    readonly status = toSignal(
        timer(0, 30_000).pipe(
            switchMap(() => this.http.get<{ status?: string }>('/actuator/health').pipe(
                timeout(5_000),
                map((response): SystemHealth => {
                    if (response.status === 'UP') return 'up';
                    if (response.status === 'DOWN' || response.status === 'OUT_OF_SERVICE') return 'down';
                    return 'unavailable';
                }),
                catchError((error: unknown) => of<SystemHealth>(
                    error instanceof HttpErrorResponse && error.status === 503 &&
                    (error.error?.status === 'DOWN' || error.error?.status === 'OUT_OF_SERVICE')
                        ? 'down' : 'unavailable',
                )),
            )),
        ),
        {initialValue: 'checking' as SystemHealth},
    );
}
