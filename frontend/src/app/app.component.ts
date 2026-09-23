import {Component, ElementRef, computed, effect, inject, signal} from '@angular/core';
import {takeUntilDestroyed} from '@angular/core/rxjs-interop';
import {NavigationEnd, Router, RouterLinkActive, RouterLink, RouterOutlet} from '@angular/router';
import {AuthService} from './core/auth.service';
import {LoginModalComponent} from './core/login-modal.component';
import {SystemHealthService} from './core/system-health.service';

@Component({
    selector: 'app-root',
    imports: [RouterLink, RouterLinkActive, RouterOutlet, LoginModalComponent],
    host: {
        '(document:click)': 'onOutsideClick($event)',
        '(document:focusin)': 'onOutsideClick($event)',
        '(document:keydown.escape)': 'closeMenus(true)'
    },
    templateUrl: './app.component.html',
})
export class App {
    protected readonly auth = inject(AuthService);
    protected readonly logoutError = signal('');
    protected readonly openMenu = signal<'admin' | 'account' | null>(null);
    private readonly element = inject<ElementRef<HTMLElement>>(ElementRef);

    constructor() {
        effect(() => {
            this.auth.accessToken();
            this.openMenu.set(null);
        });
        inject(Router).events.pipe(takeUntilDestroyed()).subscribe(event => {
            if (event instanceof NavigationEnd) this.closeMenus();
        });
    }

    protected toggleMenu(menu: 'admin' | 'account'): void {
        this.openMenu.update(open => open === menu ? null : menu);
    }

    protected closeMenus(restoreFocus = false): void {
        if (restoreFocus) this.element.nativeElement.querySelector<HTMLButtonElement>('[data-header-menu] button[aria-expanded="true"]')?.focus();
        this.openMenu.set(null);
    }

    protected onOutsideClick(event: Event): void {
        if (!(event.target instanceof Element) || !event.target.closest('[data-header-menu]')) this.closeMenus();
    }

    protected signOut(): void {
        this.closeMenus();
        this.logoutError.set('');
        this.auth.logout().subscribe({error: () => this.logoutError.set('Signed out locally. Server session revocation could not be confirmed.')});
    }

    protected readonly health = inject(SystemHealthService);
    protected readonly healthLabel = computed(() => ({
        checking: 'Checking system',
        up: 'System operational',
        down: 'System degraded',
        unavailable: 'Health unavailable',
    })[this.health.status()]);
}
