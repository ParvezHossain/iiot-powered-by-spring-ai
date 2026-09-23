import { TestBed } from '@angular/core/testing';
import { signal } from '@angular/core';
import { AuthService } from '../../core/auth.service';
import { MCP_FETCH, McpClientService, readEvents } from './mcp-client.service';

function eventResponse(parts: string[]): Response {
  const encoder = new TextEncoder();
  return new Response(new ReadableStream({ start(controller) { parts.forEach(part => controller.enqueue(encoder.encode(part))); controller.close(); } }), { headers: { 'Content-Type': 'text/event-stream' } });
}

describe('MCP session transport', () => {
  let client: McpClientService;
  let token: ReturnType<typeof signal<string | null>>;
  let admin: ReturnType<typeof signal<boolean>>;
  let fetchMock: ReturnType<typeof vi.fn>;
  let requireLogin: ReturnType<typeof vi.fn>;
  beforeEach(() => {
    token = signal<string | null>('admin-token'); admin = signal(true); requireLogin = vi.fn();
    fetchMock = vi.fn(async (_url: string, options: RequestInit) => {
      if (options.method === 'DELETE') return new Response(null, { status: 200 });
      const body = JSON.parse(options.body as string);
      if (body.method === 'notifications/initialized') return new Response(null, { status: 202 });
      const result = body.method === 'initialize' ? { protocolVersion: '2025-11-25', capabilities: { tools: {} } }
        : body.method === 'tools/list' ? { tools: [{ name: 'getMachineStatus', inputSchema: { type: 'object' } }] }
        : { isError: false, content: [{ type: 'text', text: 'RUNNING' }] };
      return new Response(JSON.stringify({ jsonrpc: '2.0', id: body.id, result }), { headers: { 'Content-Type': 'application/json', 'Mcp-Session-Id': 'server-session' } });
    });
    TestBed.configureTestingModule({ providers: [McpClientService, { provide: MCP_FETCH, useValue: fetchMock },
      { provide: AuthService, useValue: { accessToken: token, bearerToken: () => token(), isAdmin: admin, sessionVersion: 1, requireLogin } },
    ] }); client = TestBed.inject(McpClientService); TestBed.tick();
  });
  afterEach(() => TestBed.resetTestingModule());

  it('initializes, sends initialized, discovers tools, executes and deletes with session headers', async () => {
    await client.connect(); expect(client.connected()).toBe(true);
    const init = fetchMock.mock.calls[0][1] as RequestInit;
    expect(init.credentials).toBe('omit'); expect(init.redirect).toBe('error');
    expect(init.headers).toMatchObject({ Authorization: 'Bearer admin-token', Accept: 'application/json, text/event-stream' });
    expect((init.headers as Record<string, string>)['Mcp-Session-Id']).toBeUndefined();
    expect(JSON.parse(init.body as string).method).toBe('initialize');
    expect(JSON.parse(fetchMock.mock.calls[1][1].body).method).toBe('notifications/initialized');
    await client.listTools(); expect(client.tools()[0].name).toBe('getMachineStatus');
    await client.callTool('getMachineStatus', '{"machineId":"machine"}');
    const call = fetchMock.mock.calls[3][1];
    expect(call.headers).toMatchObject({ 'Mcp-Session-Id': 'server-session', 'MCP-Protocol-Version': '2025-11-25' });
    expect(JSON.parse(call.body).params).toEqual({ name: 'getMachineStatus', arguments: { machineId: 'machine' } });
    expect(client.output()).toMatchObject({ isError: false });
    await client.close(); expect(fetchMock.mock.calls[4][1].method).toBe('DELETE'); expect(client.connected()).toBe(false);
  });

  it('reads JSON-RPC answers from SSE and reports tool-level errors', async () => {
    await client.connect(); await client.listTools();
    fetchMock.mockImplementationOnce(async (_url: string, options: RequestInit) => {
      const { id } = JSON.parse(options.body as string);
      return eventResponse([': heartbeat\r\n\r\n', 'data: ' + JSON.stringify({ jsonrpc: '2.0', id, result: { isError: true, content: [{ text: 'Unknown machine' }] } }) + '\r\n\r\n']);
    });
    await client.callTool('getMachineStatus', '{}'); expect(client.error()).toContain('tool returned an error');
    expect(client.output()).toMatchObject({ isError: true });
  });

  it('opens and stops the GET event stream with authentication and session headers', async () => {
    await client.connect();
    let getOptions: RequestInit | undefined;
    fetchMock.mockImplementationOnce(async (_url: string, options: RequestInit) => {
      getOptions = options;
      return new Response(new ReadableStream({ start(controller) {
        controller.enqueue(new TextEncoder().encode('data: {"jsonrpc":"2.0","method":"notice"}\n\n'));
        options.signal?.addEventListener('abort', () => controller.error(new DOMException('Stopped', 'AbortError')));
      } }), { headers: { 'Content-Type': 'text/event-stream' } });
    });
    const stream = client.startEvents();
    await vi.waitFor(() => expect(client.events().length).toBe(1));
    expect(getOptions?.method).toBe('GET'); expect(getOptions?.headers).toMatchObject({ Authorization: 'Bearer admin-token', 'Mcp-Session-Id': 'server-session' });
    client.stopEvents(); await stream; expect(client.listening()).toBe(false); expect(client.error()).toBe('');
  });

  it('rejects malformed arguments, refuses non-admin use, and handles expired sessions', async () => {
    await client.connect(); await client.listTools(); const calls = fetchMock.mock.calls.length;
    await client.callTool('getMachineStatus', '[]'); expect(fetchMock.mock.calls.length).toBe(calls); expect(client.error()).toContain('JSON object');
    fetchMock.mockResolvedValueOnce(new Response(null, { status: 404 })); await client.listTools();
    expect(client.connected()).toBe(false); expect(client.error()).toContain('expired');
    admin.set(false); TestBed.tick(); await client.connect(); expect(fetchMock.mock.calls.length).toBe(calls + 1);
  });

  it('delegates 401 handling to auth and clears state when credentials change', async () => {
    await client.connect(); fetchMock.mockResolvedValueOnce(new Response(null, { status: 401 })); await client.listTools();
    expect(requireLogin).toHaveBeenCalledWith('admin-token', 1);
    token.set('replacement-token'); TestBed.tick(); expect(client.connected()).toBe(false); expect(client.output()).toBeNull(); expect(client.tools()).toEqual([]);
  });

  it('handles JSON-RPC errors without retry', async () => {
    await client.connect(); const calls = fetchMock.mock.calls.length;
    fetchMock.mockImplementationOnce(async (_url: string, options: RequestInit) => new Response(JSON.stringify({ jsonrpc: '2.0', id: JSON.parse(options.body as string).id, error: { code: -32601, message: 'Not supported' } })));
    await client.listTools(); expect(client.error()).toContain('Not supported'); expect(fetchMock.mock.calls.length).toBe(calls + 1);
  });
});

describe('SSE decoding', () => {
  it('handles fragmented CRLF frames and multiline data', async () => {
    const values: string[] = [];
    await readEvents(eventResponse([': comment\r', '\n\r\ndata: {"value":\r', '\ndata: "ok"}\r\n\r', '\n']), data => { values.push(data); });
    expect(values).toEqual(['{"value":\n"ok"}']);
  });
});
