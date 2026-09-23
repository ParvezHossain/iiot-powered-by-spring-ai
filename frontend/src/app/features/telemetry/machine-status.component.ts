import { Component, DestroyRef, effect, inject, signal } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { HttpClient } from '@angular/common/http';
import { FormsModule } from '@angular/forms';
import { Subscription, finalize, timeout } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { MachineStatus } from '../../core/models';
import { apiError } from '../../core/api-feedback';
import { formatJson } from '../ai-assistant/ai-assistant.store';

@Component({ selector: 'app-machine-status', imports: [FormsModule], template: `
<section class="panel mx-auto max-w-3xl p-6"><h1 class="text-xl font-semibold">Machine status lookup</h1>
<p class="mt-3 text-sm text-slate-400">Load the current status and latest reading for every metric using a machine UUID from the dashboard.</p>
<form (ngSubmit)="load()" class="my-5 space-y-3"><label class="block">Machine UUID<input name="id" [(ngModel)]="id" class="ai-input" required [disabled]="busy() || !auth.isAuthenticated()"></label><button class="ai-button" [disabled]="busy() || !auth.isAuthenticated() || !validId()">Load status</button></form>
@if (!auth.isAuthenticated()) { <p>Sign in to query telemetry.</p> }
@if (busy()) { <p role="status">Loading status…</p> }
@if (result(); as machine) { <h2 class="font-semibold">{{ machine.name }} · {{ machine.status }}</h2><p class="my-2 text-sm">{{ machine.location ?? 'Location not provided' }}</p><pre class="ai-code">{{ format(machine.latestReadings) }}</pre> }
@if (error()) { <p role="alert" class="mt-4 text-amber-300">{{ error() }}</p> }</section>` })
export class MachineStatusComponent {
  readonly auth = inject(AuthService); private readonly http = inject(HttpClient);
  id = inject(ActivatedRoute, { optional: true })?.snapshot.queryParamMap.get('id') ?? ''; readonly result = signal<MachineStatus | null>(null); readonly busy = signal(false); readonly error = signal(''); readonly format = formatJson;
  private request?: Subscription;
  constructor() {
    effect(() => { this.auth.accessToken(); this.request?.unsubscribe(); this.result.set(null); this.error.set(''); });
    inject(DestroyRef).onDestroy(() => this.request?.unsubscribe());
  }
  validId(): boolean { return /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/i.test(this.id.trim()); }
  load(): void {
    if (!this.validId() || !this.auth.isAuthenticated() || this.busy()) return;
    this.busy.set(true); this.error.set(''); this.result.set(null);
    this.request = this.http.get<MachineStatus>(`/api/machines/${encodeURIComponent(this.id.trim())}/status`).pipe(timeout(15_000), finalize(() => this.busy.set(false)))
      .subscribe({ next: result => this.result.set(result), error: error => this.error.set(apiError(error)) });
  }
}
