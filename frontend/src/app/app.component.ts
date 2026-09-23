import { Component, computed, inject, signal } from '@angular/core';
import { RouterLink, RouterOutlet } from '@angular/router';
import { AuthService } from './core/auth.service';
import { LoginModalComponent } from './core/login-modal.component';
import { SystemHealthService } from './core/system-health.service';

@Component({
  selector: 'app-root',
  imports: [RouterLink, RouterOutlet, LoginModalComponent],
  templateUrl: './app.component.html',
})
export class App {
  protected readonly auth = inject(AuthService);
  protected readonly logoutError = signal('');
  protected signOut(): void {
    this.logoutError.set('');
    this.auth.logout().subscribe({ error: () => this.logoutError.set('Signed out locally. Server session revocation could not be confirmed.') });
  }
  protected readonly health = inject(SystemHealthService);
  protected readonly healthLabel = computed(() => ({
    checking: 'Checking system',
    up: 'System operational',
    down: 'System degraded',
    unavailable: 'Health unavailable',
  })[this.health.status()]);
}
