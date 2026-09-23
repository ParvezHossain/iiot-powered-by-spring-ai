import {Component, DestroyRef, effect, inject, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {Subscription, finalize, timeout} from 'rxjs';
import {AuthService} from '../../core/auth.service';
import {User} from '../../core/models';
import {apiError, validIdentity} from '../../core/api-feedback';

@Component({selector: 'app-account', imports: [FormsModule], templateUrl: './account.component.html'})
export class AccountComponent {
    readonly auth = inject(AuthService);
    private readonly http = inject(HttpClient);
    private requests = new Subscription();
    readonly profile = signal<User | null>(null);
    readonly busy = signal(false);
    readonly message = signal('');
    readonly error = signal('');
    username = '';
    email = '';
    password = '';
    readonly validIdentity = validIdentity;

    constructor() {
        effect(() => {
            this.auth.accessToken();
            this.requests.unsubscribe();
            this.requests = new Subscription();
            this.profile.set(null);
            this.error.set('');
            this.message.set('');
            this.password = '';
            this.busy.set(false);
        });
        inject(DestroyRef).onDestroy(() => this.requests.unsubscribe());
    }

    register(): void {
        if (this.busy() || this.auth.isAuthenticated() || !validIdentity(this.username, this.email, this.password)) return;
        this.busy.set(true);
        this.error.set('');
        this.message.set('');
        this.requests.add(this.auth.register({username: this.username, email: this.email, password: this.password})
            .pipe(finalize(() => {
                this.busy.set(false);
                this.password = '';
            })).subscribe({
                next: () => this.message.set('Account created with USER access. You can now sign in.'),
                error: error => this.error.set(apiError(error)),
            }));
    }

    loadProfile(): void {
        if (!this.auth.isAuthenticated() || this.busy()) return;
        this.busy.set(true);
        this.error.set('');
        this.requests.add(this.http.get<User>('/api/auth/me').pipe(timeout(15_000), finalize(() => this.busy.set(false))).subscribe({
            next: user => this.profile.set(user), error: error => this.error.set(apiError(error)),
        }));
    }

    refresh(): void {
        if (!this.auth.isAuthenticated() || this.auth.busy() || this.busy()) return;
        this.error.set('');
        this.message.set('');
        this.requests.add(this.auth.refresh().subscribe({
            next: () => this.message.set('Session renewed.'), error: error => this.error.set(apiError(error)),
        }));
    }
}
