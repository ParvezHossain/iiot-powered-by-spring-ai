import { TestBed } from '@angular/core/testing';
import { DOCUMENT } from '@angular/common';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { AuthService } from './auth.service';
import { isBackendUrl, jwtInterceptor } from './jwt.interceptor';
import { AuthResponse } from './models';

const response: AuthResponse = {
  accessToken: 'test-access', tokenType: 'Bearer', expiresIn: 900,
  refreshToken: 'test-refresh', refreshExpiresAt: '2030-01-01T00:00:00Z',
  user: { id: 'user-id', username: 'operator', email: 'operator@example.test', role: 'USER', enabled: true },
};

describe('Authentication state and bearer pipeline', () => {
  let auth: AuthService;
  let http: HttpClient;
  let requests: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(withInterceptors([jwtInterceptor])), provideHttpClientTesting()] });
    auth = TestBed.inject(AuthService);
    http = TestBed.inject(HttpClient);
    requests = TestBed.inject(HttpTestingController);
  });
  afterEach(() => { requests.verify(); vi.useRealTimers(); });
  function login(tokens = response) {
    auth.login({ usernameOrEmail: 'operator', password: 'test-password' }).subscribe();
    const req = requests.expectOne('/api/auth/login');
    expect(req.request.headers.has('Authorization')).toBe(false);
    expect(req.request.body.usernameOrEmail).toBe('operator');
    req.flush(tokens);
  }

  it('sets signals from the server user, closes login and keeps credentials out of browser storage', () => {
    const local = vi.spyOn(Storage.prototype, 'setItem');
    auth.openLogin(); login();
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.currentUser()).toEqual(response.user);
    expect(auth.isAdmin()).toBe(false);
    expect(auth.accessToken()).toBe('test-access');
    expect(auth.loginVisible()).toBe(false);
    expect(auth.busy()).toBe(false);
    expect(local).not.toHaveBeenCalled();
    local.mockRestore();
  });

  it('reads ADMIN from the server response', () => {
    login({ ...response, user: { ...response.user, role: 'ADMIN' } });
    expect(auth.isAdmin()).toBe(true);
  });

  it('keeps failed login errors inline without setting a session', () => {
    auth.openLogin();
    const failed = vi.fn();
    auth.login({ usernameOrEmail: 'wrong', password: 'wrong' }).subscribe({ error: failed });
    requests.expectOne('/api/auth/login').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(failed).toHaveBeenCalledOnce();
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.loginVisible()).toBe(true);
    expect(auth.busy()).toBe(false);
  });

  it('attaches bearer to relative and absolute same-origin API and MCP routes', () => {
    login();
    const origin = new URL(TestBed.inject(DOCUMENT).baseURI).origin;
    for (const url of ['/api/auth/me', '/mcp', '/mcp/tools', origin + '/api/machines/test/status']) {
      http.get(url).subscribe();
      const req = requests.expectOne(url);
      expect(req.request.headers.get('Authorization')).toBe('Bearer test-access');
      req.flush({});
    }
  });

  it('never adds credentials to external URLs, health, assets, lookalike paths or public auth endpoints', () => {
    login();
    for (const url of ['https://untrusted.example/api/private', '//untrusted.example/mcp', '/actuator/health', '/assets/app.js', '/api-evil/test', '/mcp-evil', '/api/auth/login', '/api/auth/refresh']) {
      http.get(url).subscribe({ error: () => {} });
      const req = requests.expectOne(url);
      expect(req.request.headers.has('Authorization')).toBe(false);
      req.flush({}, { status: 401, statusText: 'Unauthorized' });
      expect(auth.isAuthenticated()).toBe(true);
    }
  });

  it('clears the session and opens one modal on concurrent 401s without retrying mutations', () => {
    login();
    for (let i = 0; i < 2; i++) http.post('/api/agent/chat', { question: 'hello' }).subscribe({ error: () => {} });
    const pending = requests.match('/api/agent/chat');
    pending.forEach(req => req.flush({}, { status: 401, statusText: 'Unauthorized' }));
    expect(auth.accessToken()).toBeNull();
    expect(auth.currentUser()).toBeNull();
    expect(auth.loginVisible()).toBe(true);
    requests.expectNone('/api/agent/chat');
  });

  it('opens login on anonymous 401 and preserves authenticated state on 403', () => {
    http.get('/api/auth/me').subscribe({ error: () => {} });
    requests.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.loginVisible()).toBe(true);
    login();
    http.get('/mcp').subscribe({ error: () => {} });
    requests.expectOne('/mcp').flush({}, { status: 403, statusText: 'Forbidden' });
    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.loginVisible()).toBe(false);
  });

  it('ignores a stale anonymous 401 while a newer login is in flight', () => {
    http.get('/api/auth/me').subscribe({ error: () => {} });
    const old = requests.expectOne('/api/auth/me');
    auth.login({ usernameOrEmail: 'operator', password: 'test-password' }).subscribe();
    old.flush({}, { status: 401, statusText: 'Unauthorized' });
    requests.expectOne('/api/auth/login').flush(response);
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('ignores a stale token 401 after signing in again', () => {
    login();
    http.get('/api/auth/me').subscribe({ error: () => {} });
    const old = requests.expectOne('/api/auth/me');
    login({ ...response, accessToken: 'new-access' });
    old.flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.accessToken()).toBe('new-access');
    expect(auth.loginVisible()).toBe(false);
  });

  it('clears credentials on logout even if revocation fails', () => {
    login();
    auth.logout().subscribe({ error: () => {} });
    expect(auth.isAuthenticated()).toBe(false);
    const logout = requests.expectOne('/api/auth/logout');
    expect(logout.request.body).toEqual({ refreshToken: 'test-refresh' });
    expect(logout.request.headers.has('Authorization')).toBe(false);
    logout.flush({}, { status: 503, statusText: 'Unavailable' });
    expect(auth.accessToken()).toBeNull();
  });

  it('keeps a sign-in in progress when anonymous polling fails', () => {
    auth.login({ usernameOrEmail: 'operator', password: 'test-password' }).subscribe();
    http.get('/api/auth/me').subscribe({ error: () => {} });
    requests.expectOne('/api/auth/me').flush({}, { status: 401, statusText: 'Unauthorized' });
    requests.expectOne('/api/auth/login').flush(response);
    expect(auth.isAuthenticated()).toBe(true);
  });

  it('cancels a login request without leaving busy state or storing credentials', () => {
    const subscription = auth.login({ usernameOrEmail: 'operator', password: 'test-password' }).subscribe();
    const pending = requests.expectOne('/api/auth/login');
    subscription.unsubscribe();
    expect(pending.cancelled).toBe(true);
    expect(auth.busy()).toBe(false);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('ends busy state after a login timeout', () => {
    vi.useFakeTimers();
    const failed = vi.fn();
    auth.login({ usernameOrEmail: 'operator', password: 'test-password' }).subscribe({ error: failed });
    const pending = requests.expectOne('/api/auth/login');
    vi.advanceTimersByTime(15_001);
    expect(pending.cancelled).toBe(true);
    expect(failed).toHaveBeenCalledOnce();
    expect(auth.busy()).toBe(false);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('does not restore a late login response after logout', () => {
    auth.login({ usernameOrEmail: 'operator', password: 'test-password' }).subscribe();
    auth.logout().subscribe();
    requests.expectOne('/api/auth/login').flush(response);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('expires the session and opens login without persisting refresh tokens', () => {
    vi.useFakeTimers();
    login({ ...response, expiresIn: 1 });
    vi.advanceTimersByTime(1001);
    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.loginVisible()).toBe(true);
  });

  it('checks expiry even when the browser did not run its timer', () => {
    vi.useFakeTimers();
    login({ ...response, expiresIn: 1 });
    vi.setSystemTime(Date.now() + 2000);
    expect(auth.bearerToken()).toBeNull();
  });

  it('registers without role injection or bearer credentials', () => {
    const created = vi.fn();
    auth.register({ username: 'new-user', email: 'new@example.test', password: 'long-password', ...{ role: 'ADMIN' } }).subscribe(created);
    const req = requests.expectOne('/api/auth/register');
    expect(req.request.body).toEqual({ username: 'new-user', email: 'new@example.test', password: 'long-password' });
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush(response.user); expect(created).toHaveBeenCalledWith(response.user);
    expect(auth.isAuthenticated()).toBe(false);
  });

  it('rotates both tokens once and uses the new refresh token for logout', () => {
    login(); auth.refresh().subscribe();
    const duplicate = vi.fn(); auth.refresh().subscribe({ error: duplicate });
    expect(duplicate).toHaveBeenCalledOnce();
    const req = requests.expectOne('/api/auth/refresh');
    expect(req.request.body).toEqual({ refreshToken: 'test-refresh' });
    expect(req.request.headers.has('Authorization')).toBe(false);
    req.flush({ ...response, accessToken: 'rotated-access', refreshToken: 'rotated-refresh' });
    expect(auth.accessToken()).toBe('rotated-access'); expect(auth.busy()).toBe(false);
    auth.logout().subscribe();
    const logout = requests.expectOne('/api/auth/logout');
    expect(logout.request.body.refreshToken).toBe('rotated-refresh'); logout.flush(null);
  });

  it('discards the session on refresh failure without retrying a single-use token', () => {
    login(); auth.refresh().subscribe({ error: () => {} });
    requests.expectOne('/api/auth/refresh').flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(auth.accessToken()).toBeNull(); expect(auth.loginVisible()).toBe(true); expect(auth.busy()).toBe(false);
    requests.expectNone('/api/auth/refresh');
  });

  it('cannot restore a logged-out session with a late refresh response', () => {
    login(); auth.refresh().subscribe(); const req = requests.expectOne('/api/auth/refresh');
    auth.logout().subscribe(); requests.expectOne('/api/auth/logout').flush(null);
    req.flush({ ...response, accessToken: 'late' }); expect(auth.accessToken()).toBeNull();
  });

  it('discards credentials if a refresh is cancelled after it may have reached the server', () => {
    login(); const subscription = auth.refresh().subscribe(); const req = requests.expectOne('/api/auth/refresh');
    subscription.unsubscribe(); expect(req.cancelled).toBe(true); expect(auth.accessToken()).toBeNull(); expect(auth.loginVisible()).toBe(true);
  });

  it('rejects malformed or disabled login responses', () => {
    auth.login({ usernameOrEmail: 'operator', password: 'test-password' }).subscribe({ error: () => {} });
    requests.expectOne('/api/auth/login').flush({ ...response, user: { ...response.user, enabled: false } });
    expect(auth.isAuthenticated()).toBe(false);
  });
});

describe('Backend URL boundary', () => {
  it('rejects foreign origins, credentials, changed ports, invalid URLs and misleading prefixes', () => {
    const base = 'https://iiot.example/';
    for (const url of ['https://iiot.example.evil/api/x', 'https://iiot.example:444/api/x', 'https://user@iiot.example/api/x', 'http://iiot.example/api/x', 'https://[invalid', '/api2/x', '/mcp-other']) {
      expect(isBackendUrl(url, base)).toBe(false);
    }
    expect(isBackendUrl('api/auth/me', base)).toBe(true);
    expect(isBackendUrl('/mcp?session=x', base)).toBe(true);
  });
});
