import {HttpBackend, HttpClient} from '@angular/common/http';
import {DestroyRef, Injectable, computed, inject, signal} from '@angular/core';
import {catchError, defer, finalize, of, tap, throwError, timeout} from 'rxjs';
import {AuthResponse, LoginCredentials, User} from './models';

@Injectable({providedIn: 'root'})
export class AuthService {
    // Auth endpoints deliberately bypass bearer/401 interception to avoid recursive login handling.
    private readonly http = new HttpClient(inject(HttpBackend));
    private readonly session = signal<AuthResponse | null>(null);
    private readonly modal = signal(false);
    private readonly pending = signal(false);
    private generation = 0;
    private expiryTimer?: ReturnType<typeof setTimeout>;
    private expiresAt = 0;

    readonly accessToken = computed(() => this.session()?.accessToken ?? null);
    readonly currentUser = computed<User | null>(() => this.session()?.user ?? null);
    readonly isAuthenticated = computed(() => this.session() !== null);
    readonly isAdmin = computed(() => this.currentUser()?.role === 'ADMIN');
    readonly loginVisible = this.modal.asReadonly();
    readonly busy = this.pending.asReadonly();

    constructor() {
        inject(DestroyRef).onDestroy(() => clearTimeout(this.expiryTimer));
    }

    openLogin(): void {
        this.modal.set(true);
    }

    closeLogin(): void {
        this.modal.set(false);
    }

    login(credentials: LoginCredentials) {
        return defer(() => {
            if (this.pending()) return throwError(() => new Error('Sign-in already in progress'));
            const generation = ++this.generation;
            this.pending.set(true);
            return this.http.post<AuthResponse>('/api/auth/login', credentials).pipe(
                timeout(15_000),
                tap(response => {
                    if (generation !== this.generation) return;
                    if (!response.accessToken || response.tokenType !== 'Bearer' || !response.refreshToken ||
                        !Number.isFinite(response.expiresIn) || response.expiresIn <= 0 || !response.user?.enabled) {
                        throw new Error('Invalid authentication response');
                    }
                    this.installSession(response);
                    this.modal.set(false);
                }),
                finalize(() => {
                    if (generation === this.generation) this.pending.set(false);
                }),
            );
        });
    }

    /** Refresh is explicit and serialized: single-use refresh tokens must never be replayed. */
    refresh() {
        return defer(() => {
            if (this.pending() || !this.session()) return throwError(() => new Error('No refreshable session'));
            const refreshToken = this.session()!.refreshToken;
            let completed = false;
            const generation = ++this.generation;
            this.pending.set(true);
            return this.http.post<AuthResponse>('/api/auth/refresh', {refreshToken}).pipe(
                timeout(15_000),
                tap(response => {
                    if (generation !== this.generation) return;
                    if (!response.accessToken || response.tokenType !== 'Bearer' || !response.refreshToken ||
                        !Number.isFinite(response.expiresIn) || response.expiresIn <= 0 || !response.user?.enabled) {
                        throw new Error('Invalid refresh response');
                    }
                    this.installSession(response);
                    completed = true;
                }),
                catchError(error => {
                    if (generation === this.generation) {
                        this.clearSession();
                        this.modal.set(true);
                    }
                    return throwError(() => error);
                }),
                finalize(() => {
                    if (generation !== this.generation) return;
                    if (!completed) {
                        this.clearSession();
                        this.modal.set(true);
                    } else this.pending.set(false);
                }),
            );
        });
    }

    register(request: { username: string; email: string; password: string }) {
        // Pick fields explicitly: self-registration never sends role or roles.
        return this.http.post<User>('/api/auth/register', {
            username: request.username, email: request.email, password: request.password,
        }).pipe(timeout(15_000));
    }

    private installSession(response: AuthResponse): void {
        clearTimeout(this.expiryTimer);
        this.session.set(response);
        this.expiresAt = Date.now() + response.expiresIn * 1000;
        this.scheduleExpiry();
    }

    /** Re-check time on each outgoing request, even if the browser throttled background timers. */
    bearerToken(): string | null {
        if (this.session() && Date.now() >= this.expiresAt) this.requireLogin(this.accessToken());
        return this.accessToken();
    }

    get sessionVersion(): number {
        return this.generation;
    }

    requireLogin(requestToken: string | null, version = this.generation): void {
        // An old request must not invalidate a more recent successful sign-in.
        if (version !== this.generation || requestToken !== this.accessToken()) return;
        // Background anonymous polling must not cancel an active sign-in.
        if (this.pending() && !this.session()) return;
        this.clearSession();
        this.modal.set(true);
    }

    logout() {
        const refreshToken = this.session()?.refreshToken;
        this.clearSession();
        this.modal.set(false);
        // Local credentials are cleared even if server revocation fails.
        if (!refreshToken) return of(undefined);
        const generation = this.generation;
        return defer(() => {
            this.pending.set(true);
            return this.http.post<void>('/api/auth/logout', {refreshToken}).pipe(
                timeout(15_000),
                finalize(() => {
                    if (generation === this.generation) this.pending.set(false);
                }),
            );
        });
    }

    private clearSession(): void {
        ++this.generation;
        clearTimeout(this.expiryTimer);
        this.session.set(null);
        this.pending.set(false);
        this.expiresAt = 0;
    }

    private scheduleExpiry(): void {
        const remaining = this.expiresAt - Date.now();
        if (remaining <= 0) {
            this.requireLogin(this.accessToken());
            return;
        }
        this.expiryTimer = setTimeout(() => this.scheduleExpiry(), Math.min(remaining, 2_147_483_647));
    }
}
