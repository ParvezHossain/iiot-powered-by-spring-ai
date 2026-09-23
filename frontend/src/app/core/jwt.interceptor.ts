import {DOCUMENT} from '@angular/common';
import {HttpErrorResponse, HttpInterceptorFn} from '@angular/common/http';
import {inject} from '@angular/core';
import {catchError, throwError} from 'rxjs';
import {AuthService} from './auth.service';

export function isBackendUrl(url: string, baseUri: string): boolean {
    try {
        const target = new URL(url, baseUri);
        const base = new URL(baseUri);
        return target.origin === base.origin && !target.username && !target.password &&
            (target.pathname.startsWith('/api/') || target.pathname === '/mcp' || target.pathname.startsWith('/mcp/'));
    } catch {
        return false;
    }
}

export const jwtInterceptor: HttpInterceptorFn = (request, next) => {
    const baseUri = inject(DOCUMENT).baseURI;
    if (!isBackendUrl(request.url, baseUri)) return next(request);
    const path = new URL(request.url, baseUri).pathname;
    if (/^\/api\/auth\/(login|register|refresh|logout)\/?$/.test(path)) return next(request);

    const auth = inject(AuthService);
    const token = auth.bearerToken();
    const version = auth.sessionVersion;
    const authenticated = token ? request.clone({setHeaders: {Authorization: `Bearer ${token}`}}) : request;
    return next(authenticated).pipe(catchError((error: unknown) => {
        if (error instanceof HttpErrorResponse && error.status === 401) auth.requireLogin(token, version);
        return throwError(() => error);
    }));
};
