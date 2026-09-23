import {inject} from '@angular/core';
import {CanActivateFn, Router} from '@angular/router';
import {AuthService} from './auth.service';

export const adminGuard: CanActivateFn = () => {
    const auth = inject(AuthService);
    const router = inject(Router);
    // Recheck expiration even when the browser has delayed the session timer.
    if (!auth.bearerToken()) {
        auth.openLogin();
        return router.createUrlTree(['/']);
    }
    return auth.isAdmin() ? true : router.createUrlTree(['/']);
};
