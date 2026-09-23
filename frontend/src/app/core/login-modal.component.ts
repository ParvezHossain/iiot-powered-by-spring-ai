import {Component, DestroyRef, ElementRef, afterRenderEffect, inject, signal, viewChild} from '@angular/core';
import {HttpErrorResponse} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {Subscription} from 'rxjs';
import {AuthService} from './auth.service';

@Component({
    selector: 'app-login-modal',
    imports: [FormsModule],
    templateUrl: './login-modal.component.html',
})
export class LoginModalComponent {
    protected readonly auth = inject(AuthService);
    protected readonly error = signal('');
    protected usernameOrEmail = '';
    protected password = '';
    private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
    private request?: Subscription;

    constructor() {
        afterRenderEffect(() => {
            const dialog = this.dialog().nativeElement;
            if (this.auth.loginVisible() && !dialog.open) {
                this.error.set('');
                dialog.showModal();
            } else if (!this.auth.loginVisible() && dialog.open) {
                dialog.close();
                this.password = '';
            }
        });
        inject(DestroyRef).onDestroy(() => this.request?.unsubscribe());
    }

    protected close(): void {
        this.request?.unsubscribe();
        this.password = '';
        this.error.set('');
        this.auth.closeLogin();
    }

    protected submit(): void {
        if (this.auth.busy() || !this.usernameOrEmail.trim() || !this.password) return;
        this.error.set('');
        this.request = this.auth.login({
            usernameOrEmail: this.usernameOrEmail.trim(),
            password: this.password
        }).subscribe({
            next: () => {
                this.password = '';
            },
            error: (error: unknown) => {
                this.password = '';
                this.error.set(error instanceof HttpErrorResponse && error.status === 401
                    ? 'Sign-in failed. Check your credentials or contact your administrator.'
                    : error instanceof HttpErrorResponse && error.status === 429
                        ? 'Too many attempts. Please wait before trying again.'
                        : 'Unable to sign in. Please try again.');
            },
        });
    }
}
