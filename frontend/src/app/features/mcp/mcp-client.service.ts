import {DestroyRef, Injectable, InjectionToken, effect, inject, signal} from '@angular/core';
import {AuthService} from '../../core/auth.service';

export const MCP_FETCH = new InjectionToken<typeof fetch>('MCP fetch', {factory: () => globalThis.fetch.bind(globalThis)});

export interface McpTool {
    name: string;
    description?: string;
    inputSchema: unknown;
}

interface RpcMessage {
    jsonrpc: string;
    id?: number;
    result?: Record<string, unknown>;
    error?: { code: number; message: string };
}

/** Incremental SSE decoder, including split UTF-8, multiline data and CR/LF boundaries. */
export async function readEvents(response: Response, onMessage: (data: string) => boolean | void): Promise<void> {
    const reader = response.body?.getReader();
    if (!reader) throw new Error('The server returned no event stream.');
    const decoder = new TextDecoder();
    let buffer = '';
    let data: string[] = [];
    const line = (value: string): boolean => {
        if (!value) {
            const payload = data.join('\n');
            data = [];
            return payload ? onMessage(payload) === true : false;
        }
        if (value.startsWith('data:')) data.push(value.slice(5).replace(/^ /, ''));
        return false;
    };
    try {
        while (true) {
            const {value, done} = await reader.read();
            buffer += decoder.decode(value, {stream: !done});
            if (buffer.length + data.join('').length > 1_000_000) throw new Error('MCP event exceeds the display limit.');
            let match: RegExpExecArray | null;
            while ((match = /\r\n|\r|\n/.exec(buffer))) {
                if (match[0] === '\r' && match.index === buffer.length - 1 && !done) break;
                const current = buffer.slice(0, match.index);
                buffer = buffer.slice(match.index + match[0].length);
                if (line(current)) return;
            }
            if (done) {
                if (buffer) line(buffer);
                line('');
                return;
            }
        }
    } finally {
        await reader.cancel().catch(() => {
        });
        reader.releaseLock();
    }
}

@Injectable()
export class McpClientService {
    readonly auth = inject(AuthService);
    private readonly fetch = inject(MCP_FETCH);
    private sessionId: string | null = null;
    private version = '2025-11-25';
    private sequence = 0;
    private generation = 0;
    private readonly controllers = new Set<AbortController>();
    private eventController?: AbortController;
    readonly connected = signal(false);
    readonly busy = signal(false);
    readonly listening = signal(false);
    readonly tools = signal<McpTool[]>([]);
    readonly output = signal<unknown>(null);
    readonly events = signal<unknown[]>([]);
    readonly error = signal('');

    constructor() {
        effect(() => {
            this.auth.accessToken();
            this.auth.isAdmin();
            this.reset();
        });
        inject(DestroyRef).onDestroy(() => this.reset());
    }

    private reset(): void {
        ++this.generation;
        this.controllers.forEach(controller => controller.abort());
        this.controllers.clear();
        this.eventController = undefined;
        this.sessionId = null;
        this.version = '2025-11-25';
        this.connected.set(false);
        this.listening.set(false);
        this.busy.set(false);
        this.tools.set([]);
        this.output.set(null);
        this.events.set([]);
        this.error.set('');
    }

    private headers(): Record<string, string> {
        const token = this.auth.bearerToken();
        if (!token || !this.auth.isAdmin()) throw new Error('Sign in as an administrator to use MCP.');
        return {
            Authorization: `Bearer ${token}`, Accept: 'application/json, text/event-stream',
            'MCP-Protocol-Version': this.version, ...(this.sessionId ? {'Mcp-Session-Id': this.sessionId} : {})
        };
    }

    private async response(method: string, controller: AbortController, body?: unknown): Promise<Response> {
        const headers = this.headers();
        const token = this.auth.accessToken();
        const version = this.auth.sessionVersion;
        const response = await this.fetch('/mcp', {
            method,
            headers: {...headers, ...(body ? {'Content-Type': 'application/json'} : {})},
            ...(body ? {body: JSON.stringify(body)} : {}),
            signal: controller.signal,
            credentials: 'omit',
            redirect: 'error'
        });
        if (response.status === 401) this.auth.requireLogin(token, version);
        if (!response.ok) {
            const messages: Record<number, string> = {
                401: 'Session expired. Sign in again.',
                403: 'MCP access denied. ADMIN access and a trusted origin are required.',
                404: 'MCP is disabled or this session expired. Enable MCP_ENABLED or reconnect.',
                405: 'This MCP server does not support this operation.'
            };
            if (response.status === 404 && this.sessionId) {
                this.sessionId = null;
                this.connected.set(false);
                this.tools.set([]);
                this.stopEvents();
            }
            throw new Error(messages[response.status] ?? `MCP request failed (HTTP ${response.status}).`);
        }
        return response;
    }

    private async rpc(method: string, params?: unknown, notification = false): Promise<Record<string, unknown>> {
        const generation = this.generation;
        const id = notification ? undefined : ++this.sequence;
        const controller = new AbortController();
        this.controllers.add(controller);
        const timer = setTimeout(() => controller.abort(), 180_000);
        try {
            const response = await this.response('POST', controller, {
                jsonrpc: '2.0', ...(id === undefined ? {} : {id}),
                method, ...(params === undefined ? {} : {params})
            });
            if (generation !== this.generation) throw new DOMException('Session changed', 'AbortError');
            if (method === 'initialize') this.sessionId = response.headers.get('Mcp-Session-Id');
            if (notification && response.status === 202) return {};
            let message: RpcMessage | undefined;
            if (response.headers.get('Content-Type')?.includes('text/event-stream')) {
                await readEvents(response, data => {
                    const event = JSON.parse(data) as RpcMessage;
                    if (event.id === id) {
                        message = event;
                        return true;
                    }
                    this.events.update(events => [...events.slice(-49), event]);
                    return false;
                });
            } else message = await response.json() as RpcMessage;
            if (!message || message.jsonrpc !== '2.0' || message.id !== id) throw new Error('Invalid or missing MCP response.');
            if (message.error) throw new Error(`MCP ${message.error.code}: ${message.error.message}`);
            if (!message.result) throw new Error('Missing MCP result.');
            return message.result;
        } finally {
            clearTimeout(timer);
            this.controllers.delete(controller);
        }
    }

    private async run(work: () => Promise<void>): Promise<void> {
        if (this.busy() || !this.auth.isAdmin()) return;
        const generation = this.generation;
        this.busy.set(true);
        this.error.set('');
        try {
            await work();
        } catch (error) {
            if (generation === this.generation) this.error.set(error instanceof Error && error.name !== 'AbortError' ? error.message : 'The MCP request was interrupted or timed out. It was not retried.');
        } finally {
            if (generation === this.generation) this.busy.set(false);
        }
    }

    async connect(): Promise<void> {
        if (this.connected()) return;
        return this.run(async () => {
            const generation = this.generation;
            this.sessionId = null;
            this.version = '2025-11-25';
            const result = await this.rpc('initialize', {
                protocolVersion: this.version,
                capabilities: {},
                clientInfo: {name: 'iiot-control-center', version: '1.0'}
            });
            if (generation !== this.generation) return;
            if (!this.sessionId || typeof result['protocolVersion'] !== 'string') throw new Error('MCP initialization did not return a session and protocol version.');
            this.version = result['protocolVersion'];
            await this.rpc('notifications/initialized', undefined, true);
            if (generation !== this.generation) return;
            this.connected.set(true);
            this.output.set(result);
        });
    }

    async listTools(): Promise<void> {
        if (!this.connected()) return;
        return this.run(async () => {
            const generation = this.generation;
            const all: McpTool[] = [];
            let cursor: unknown;
            let pages = 0;
            do {
                if (++pages > 20) throw new Error('Tool pagination exceeded the display limit.');
                const result = await this.rpc('tools/list', cursor ? {cursor} : {});
                if (generation !== this.generation) return;
                if (!Array.isArray(result['tools'])) throw new Error('Invalid MCP tools response.');
                all.push(...result['tools'] as McpTool[]);
                cursor = result['nextCursor'];
                if (cursor && all.length >= 1000) throw new Error('Too many tools to display.');
            } while (cursor);
            this.tools.set(all);
            this.output.set({tools: all});
        });
    }

    async callTool(name: string, input: string): Promise<void> {
        if (!this.connected()) return;
        return this.run(async () => {
            const args: unknown = JSON.parse(input);
            if (!args || typeof args !== 'object' || Array.isArray(args)) throw new Error('Tool parameters must be a JSON object.');
            if (!this.tools().some(tool => tool.name === name)) throw new Error('Select a discovered tool.');
            const generation = this.generation;
            const result = await this.rpc('tools/call', {name, arguments: args});
            if (generation === this.generation) {
                this.output.set(result);
                if (result['isError']) this.error.set('The tool returned an error. Inspect the result below.');
            }
        });
    }

    async close(): Promise<void> {
        if (!this.connected()) return;
        return this.run(async () => {
            const controller = new AbortController();
            this.controllers.add(controller);
            const timer = setTimeout(() => controller.abort(), 15_000);
            const generation = this.generation;
            try {
                await this.response('DELETE', controller);
                if (generation === this.generation) this.reset();
            } finally {
                clearTimeout(timer);
                this.controllers.delete(controller);
            }
        });
    }

    stopEvents(): void {
        this.eventController?.abort();
        this.eventController = undefined;
        this.listening.set(false);
    }

    async startEvents(): Promise<void> {
        if (!this.connected() || this.listening() || !this.auth.isAdmin()) return;
        const generation = this.generation;
        const controller = new AbortController();
        this.eventController = controller;
        this.controllers.add(controller);
        this.listening.set(true);
        this.error.set('');
        try {
            const response = await this.response('GET', controller);
            if (!response.headers.get('Content-Type')?.includes('text/event-stream')) throw new Error('Expected an MCP event stream.');
            await readEvents(response, data => {
                if (generation === this.generation) this.events.update(events => [...events.slice(-49), JSON.parse(data)]);
            });
        } catch (error) {
            if (generation === this.generation && !controller.signal.aborted) this.error.set(error instanceof Error ? error.message : 'MCP stream failed.');
        } finally {
            this.controllers.delete(controller);
            if (this.eventController === controller) {
                this.eventController = undefined;
                this.listening.set(false);
            }
        }
    }
}
