import {Component, DestroyRef, effect, inject, signal, untracked} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {Observable, Subscription, finalize, timeout} from 'rxjs';
import {AuthService} from '../../core/auth.service';
import {Role, User} from '../../core/models';
import {apiError, validIdentity} from '../../core/api-feedback';

@Component({selector: 'app-users', imports: [FormsModule], templateUrl: './users.component.html'})
export class UsersComponent {
    readonly auth = inject(AuthService);
    private readonly http = inject(HttpClient);
    private requests = new Subscription();
    readonly users = signal<User[]>([]);
    readonly busy = signal(false);
    readonly error = signal('');
    readonly message = signal('');
    readonly offset = signal(0);
    readonly loaded = signal(false);
    username = '';
    email = '';
    password = '';
    role: Role = 'USER';
    readonly validIdentity = validIdentity;

    constructor() {
        effect(() => {
            const token = this.auth.accessToken();
            const admin = this.auth.isAdmin();
            this.requests.unsubscribe();
            this.requests = new Subscription();
            this.users.set([]);
            this.offset.set(0);
            this.error.set('');
            this.message.set('');
            this.username = '';
            this.email = '';
            this.password = '';
            this.role = 'USER';
            this.busy.set(false);
            this.loaded.set(false);
            if (token && admin) untracked(() => this.load(0));
        });
        inject(DestroyRef).onDestroy(() => this.requests.unsubscribe());
    }

    load(offset: number): void {
        if (!this.auth.isAdmin() || this.busy() || offset < 0) return;
        this.perform(this.http.get<User[]>('/api/admin/users', {params: {limit: 100, offset}}), users => {
            this.users.set(users);
            this.offset.set(offset);
            this.loaded.set(true);
        });
    }

    create(): void {
        if (!this.auth.isAdmin() || this.busy() || !validIdentity(this.username, this.email, this.password) || !['USER', 'ADMIN'].includes(this.role)) return;
        this.perform(this.http.post<User>('/api/admin/users', {
            username: this.username,
            email: this.email,
            password: this.password,
            role: this.role
        }), () => {
            this.password = '';
            this.message.set('Account created. Reload the list to view it.');
        });
    }

    changeRole(user: User): void {
        this.update(user, 'role', {role: user.role === 'ADMIN' ? 'USER' : 'ADMIN'});
    }

    changeStatus(user: User): void {
        this.update(user, 'status', {enabled: !user.enabled});
    }

    private update(user: User, kind: string, body: object): void {
        if (!this.auth.isAdmin() || this.busy() || user.id === this.auth.currentUser()?.id) return;
        this.perform(this.http.put<User>(`/api/admin/users/${encodeURIComponent(user.id)}/${kind}`, body), updated => {
            this.users.update(users => users.map(row => row.id === updated.id ? updated : row));
            this.message.set('Account updated. Its existing sessions have been revoked.');
        });
    }

    private perform<T>(request: Observable<T>, next: (result: T) => void): void {
        this.busy.set(true);
        this.error.set('');
        this.message.set('');
        this.requests.add(request.pipe(timeout(15_000), finalize(() => this.busy.set(false))).subscribe({
            next,
            error: error => {
                this.password = '';
                this.error.set(apiError(error));
            },
        }));
    }
}
