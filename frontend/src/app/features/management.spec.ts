import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from '../core/auth.service';
import { User } from '../core/models';
import { UsersComponent } from './admin/users.component';
import { DocumentsComponent } from './documents/documents.component';
import { MachineStatusComponent } from './telemetry/machine-status.component';
import { AccountComponent } from './account/account.component';
import { validIdentity } from '../core/api-feedback';

const user: User = { id: 'other-id', username: 'operator', email: 'operator@example.test', role: 'USER', enabled: true };
describe('API management pages', () => {
  let http: HttpTestingController;
  let token: ReturnType<typeof signal<string | null>>;
  let admin: ReturnType<typeof signal<boolean>>;
  beforeEach(() => {
    token = signal<string | null>('access'); admin = signal(true);
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(),
      { provide: AuthService, useValue: { accessToken: token, isAdmin: admin, isAuthenticated: () => !!token(), currentUser: () => ({ ...user, id: 'self', role: 'ADMIN' }), busy: () => false } },
    ] }); http = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { TestBed.resetTestingModule(); http.verify({ ignoreCancelled: true }); });

  it('loads paginated users, creates accounts, and applies role/status changes', () => {
    const fixture = TestBed.createComponent(UsersComponent); fixture.detectChanges(); const page = fixture.componentInstance;
    const initial = http.expectOne(req => req.url === '/api/admin/users');
    expect(initial.request.params.get('limit')).toBe('100'); expect(initial.request.params.get('offset')).toBe('0'); initial.flush([user]);
    fixture.detectChanges(); http.expectNone(req => req.url === '/api/admin/users');
    page.load(100); const next = http.expectOne(req => req.url === '/api/admin/users'); expect(next.request.params.get('offset')).toBe('100'); next.flush([user]);
    page.username = 'supervisor'; page.email = 's@example.test'; page.password = 'long-password'; page.role = 'ADMIN'; page.create();
    const create = http.expectOne('/api/admin/users'); expect(create.request.method).toBe('POST'); expect(create.request.body.role).toBe('ADMIN'); create.flush({ ...user, role: 'ADMIN' });
    expect(page.password).toBe(''); page.changeRole(user);
    const role = http.expectOne('/api/admin/users/other-id/role'); expect(role.request.method).toBe('PUT'); expect(role.request.body).toEqual({ role: 'ADMIN' }); role.flush({ ...user, role: 'ADMIN' });
    page.changeStatus(user); const status = http.expectOne('/api/admin/users/other-id/status'); expect(status.request.body).toEqual({ enabled: false }); status.flush({ ...user, enabled: false });
    expect(page.users()[0].enabled).toBe(false);
  });

  it('blocks self changes, surfaces conflicts, and clears data on logout', () => {
    const fixture = TestBed.createComponent(UsersComponent); fixture.detectChanges(); const page = fixture.componentInstance;
    http.expectOne(req => req.url === '/api/admin/users').flush([user]);
    page.changeRole({ ...user, id: 'self' }); page.changeStatus({ ...user, id: 'self' }); http.expectNone(req => req.method === 'PUT');
    page.changeRole(user); http.expectOne('/api/admin/users/other-id/role').flush({}, { status: 409, statusText: 'Conflict' });
    expect(page.error()).toContain('administrator protection'); expect(page.users()[0]).toEqual(user);
    page.load(0); const pending = http.expectOne(req => req.url === '/api/admin/users'); token.set(null); admin.set(false); fixture.detectChanges();
    expect(pending.cancelled).toBe(true); expect(page.users()).toEqual([]);
  });

  it('gates admin pages for ordinary users without sending requests', () => {
    admin.set(false);
    const fixture = TestBed.createComponent(UsersComponent); fixture.detectChanges(); fixture.componentInstance.load(0);
    const documents = TestBed.createComponent(DocumentsComponent); documents.detectChanges();
    documents.componentInstance.query = 'manual'; documents.componentInstance.search(); documents.componentInstance.replaceConfirmed = true; documents.componentInstance.ingest();
    http.expectNone(req => req.url.startsWith('/api/'));
  });

  it('searches document passages and replaces the corpus only after explicit selection', () => {
    const fixture = TestBed.createComponent(DocumentsComponent); fixture.detectChanges(); const page = fixture.componentInstance;
    page.query = ' E204 '; page.topK = 3; page.threshold = 0.4; page.search(); page.search();
    const req = http.expectOne(req => req.url === '/api/documents/search');
    expect(req.request.params.get('query')).toBe('E204'); expect(req.request.params.get('topK')).toBe('3'); expect(req.request.params.get('threshold')).toBe('0.4');
    req.flush([{ id: 'chunk', text: '<script>bad()</script>', score: 0.8, metadata: { section: 'Faults' } }]); fixture.detectChanges();
    expect(fixture.nativeElement.textContent).toContain('<script>bad()</script>'); expect(fixture.nativeElement.querySelector('script')).toBeNull();
    page.ingest(); http.expectNone('/api/documents/ingest'); page.replaceConfirmed = true; page.ingest();
    const ingest = http.expectOne('/api/documents/ingest'); expect(ingest.request.method).toBe('POST'); expect(ingest.request.body).toBeNull();
    ingest.flush({ documents: 10, chunks: 34, model: 'nomic' }); expect(page.ingestion()?.chunks).toBe(34); expect(page.results()).toEqual([]); expect(page.replaceConfirmed).toBe(false);
  });

  it('validates search bounds, handles disabled RAG and cancels on navigation', () => {
    const fixture = TestBed.createComponent(DocumentsComponent); fixture.detectChanges(); const page = fixture.componentInstance;
    page.query = 'manual'; page.topK = 21; page.search(); http.expectNone(req => req.url.includes('search'));
    page.topK = 5; page.search(); http.expectOne(req => req.url.includes('search')).flush({}, { status: 503, statusText: 'Unavailable' }); expect(page.error()).toContain('unavailable');
    page.search(); const pending = http.expectOne(req => req.url.includes('search')); fixture.destroy(); expect(pending.cancelled).toBe(true);
  });

  it('loads the authenticated account view and machine status', () => {
    const account = TestBed.createComponent(AccountComponent); account.detectChanges(); account.componentInstance.loadProfile();
    http.expectOne('/api/auth/me').flush(user); expect(account.componentInstance.profile()).toEqual(user);
    const machine = TestBed.createComponent(MachineStatusComponent); machine.detectChanges(); const page = machine.componentInstance;
    page.id = 'invalid'; page.load(); http.expectNone(req => req.url.includes('/status'));
    page.id = '3fa85f64-5717-4562-b3fc-2c963f66afa6'; page.load();
    http.expectOne('/api/machines/3fa85f64-5717-4562-b3fc-2c963f66afa6/status').flush({ id: page.id, name: 'SIM-001', status: 'RUNNING', location: null, latestReadings: [] });
    expect(page.result()?.name).toBe('SIM-001'); token.set(null); machine.detectChanges(); expect(page.result()).toBeNull();
  });

  it('enforces username and UTF-8 password constraints', () => {
    expect(validIdentity('valid-user', 'a@example.test', 'long-password')).toBe(true);
    expect(validIdentity('ab', 'a@example.test', 'long-password')).toBe(false);
    expect(validIdentity('valid-user', 'a@example.test', 'é'.repeat(37))).toBe(false);
  });
});
