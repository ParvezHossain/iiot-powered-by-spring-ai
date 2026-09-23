import {TestBed} from '@angular/core/testing';
import {ActivatedRouteSnapshot, Router, RouterStateSnapshot, provideRouter} from '@angular/router';
import {AuthService} from './auth.service';
import {adminGuard} from './admin.guard';
import {routes} from '../app.routes';

describe('Account admin guard', () => {
    const auth = {bearerToken: vi.fn(), isAdmin: vi.fn(), openLogin: vi.fn()};
    beforeEach(() => {
        vi.resetAllMocks();
        TestBed.configureTestingModule({providers: [provideRouter([]), {provide: AuthService, useValue: auth}]});
    });
    const check = () => TestBed.runInInjectionContext(() => adminGuard({} as ActivatedRouteSnapshot, {} as RouterStateSnapshot));
    it('protects the account route', () => {
        expect(routes.find(route => route.path === 'account')?.canActivate).toContain(adminGuard);
    });
    it('allows an authenticated administrator', () => {
        auth.bearerToken.mockReturnValue('valid');
        auth.isAdmin.mockReturnValue(true);
        expect(check()).toBe(true);
        expect(auth.openLogin).not.toHaveBeenCalled();
    });
    it('redirects ordinary users to the dashboard', () => {
        auth.bearerToken.mockReturnValue('valid');
        auth.isAdmin.mockReturnValue(false);
        expect(check()).toEqual(TestBed.inject(Router).createUrlTree(['/']));
        expect(auth.openLogin).not.toHaveBeenCalled();
    });
    it('redirects missing or expired sessions and opens sign-in', () => {
        auth.bearerToken.mockReturnValue(null);
        auth.isAdmin.mockReturnValue(true);
        expect(check()).toEqual(TestBed.inject(Router).createUrlTree(['/']));
        expect(auth.openLogin).toHaveBeenCalledOnce();
    });
});
