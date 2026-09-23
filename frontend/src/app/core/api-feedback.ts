import {HttpErrorResponse} from '@angular/common/http';
import {TimeoutError} from 'rxjs';

export function apiError(error: unknown): string {
    if (error instanceof TimeoutError) return 'The request timed out. It may still be running on the server. Check before retrying.';
    if (error instanceof HttpErrorResponse) {
        switch (error.status) {
            case 400:
                return 'Check the supplied values and try again.';
            case 401:
                return 'Your session is no longer valid. Sign in again.';
            case 403:
                return 'Your account does not have permission for this operation.';
            case 404:
                return 'The requested resource or service is unavailable.';
            case 409:
                return 'The change conflicts with an existing account or administrator protection. Check for duplicate username/email, self-demotion, or the last enabled administrator.';
            case 429:
                return 'Too many requests. Wait a minute before trying again.';
            case 503:
                return 'The service is unavailable. Check the feature configuration and its dependencies.';
        }
    }
    return 'The operation could not be completed. Check the connection before retrying.';
}

export function validIdentity(username: string, email: string, password: string): boolean {
    return /^[A-Za-z0-9_.-]{3,64}$/.test(username) && email.length <= 254 && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)
        && password.length >= 12 && password.length <= 72 && new TextEncoder().encode(password).length <= 72;
}
