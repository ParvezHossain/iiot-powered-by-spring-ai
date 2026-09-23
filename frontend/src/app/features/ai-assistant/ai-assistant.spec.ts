import {TestBed} from '@angular/core/testing';
import {signal} from '@angular/core';
import {provideHttpClient} from '@angular/common/http';
import {HttpTestingController, provideHttpClientTesting} from '@angular/common/http/testing';
import {AuthService} from '../../core/auth.service';
import {AiAssistantStore} from './ai-assistant.store';
import {RagSearchComponent} from './rag-search/rag-search.component';
import {AgentChatComponent} from './agent-chat/agent-chat.component';
import {AgentChatResponse, RagQueryResponse} from '../../core/models';

const rag: RagQueryResponse = {
    answer: 'Stop and inspect.', insufficientEvidence: true, citations: [{
        sourceId: 'S1',
        chunkId: 'chunk',
        documentId: 'manual-1',
        source: 'manual',
        section: 'Safety',
        quote: '<img src=x onerror=alert(1)>',
        score: 0.9,
    }]
};
const chat: AgentChatResponse = {
    conversationId: 'conversation-1',
    answer: 'Inspect [T1].',
    insufficientEvidence: false,
    evidenceIds: ['T1'],
    evidence: [{
        id: 'T1',
        tool: 'retrieveEquipmentKnowledge',
        success: true,
        input: '{"question":"vibration"}',
        result: '{"text":"Stop"}'
    }]
};

describe('AI assistant panels', () => {
    let store: AiAssistantStore;
    let http: HttpTestingController;
    let token: ReturnType<typeof signal<string | null>>;
    let admin: ReturnType<typeof signal<boolean>>;
    beforeEach(() => {
        token = signal<string | null>('admin-token');
        admin = signal(true);
        TestBed.configureTestingModule({
            providers: [provideHttpClient(), provideHttpClientTesting(), AiAssistantStore,
                {provide: AuthService, useValue: {accessToken: token, isAdmin: admin}},
            ]
        });
        store = TestBed.inject(AiAssistantStore);
        http = TestBed.inject(HttpTestingController);
        TestBed.tick();
    });
    afterEach(() => {
        TestBed.resetTestingModule();
        http.verify({ignoreCancelled: true});
    });

    it('submits trimmed RAG questions once and renders sources as text', () => {
        const fixture = TestBed.createComponent(RagSearchComponent);
        fixture.detectChanges();
        store.ragQuestion.set('  maintenance  ');
        store.search();
        store.search();
        fixture.detectChanges();
        expect(fixture.nativeElement.textContent).toContain('Searching sources');
        const req = http.expectOne('/api/rag/query');
        expect(req.request.body).toEqual({question: 'maintenance'});
        req.flush(rag);
        fixture.detectChanges();
        const element: HTMLElement = fixture.nativeElement;
        expect(element.textContent).toContain('Stop and inspect.');
        expect(element.textContent).toContain('Insufficient evidence');
        expect(element.textContent).toContain('manual-1');
        expect(element.textContent).toContain('Safety');
        expect(element.textContent).toContain('<img src=x onerror=alert(1)>');
        expect(element.querySelector('img')).toBeNull();
        expect(store.ragBusy()).toBe(false);
    });

    it('reuses conversation IDs for follow-ups and resets them for a new conversation', () => {
        store.chatQuestion.set('first');
        store.send();
        store.send();
        const first = http.expectOne('/api/agent/chat');
        expect(first.request.body).toEqual({question: 'first'});
        first.flush(chat);
        store.chatQuestion.set('follow-up');
        store.send();
        const second = http.expectOne('/api/agent/chat');
        expect(second.request.body).toEqual({question: 'follow-up', conversationId: 'conversation-1'});
        second.flush(chat);
        expect(store.messages().map(m => m.role)).toEqual(['user', 'assistant', 'user', 'assistant']);
        store.newConversation();
        store.chatQuestion.set('fresh');
        store.send();
        const fresh = http.expectOne('/api/agent/chat');
        expect(fresh.request.body).toEqual({question: 'fresh'});
        fresh.flush(chat);
    });

    it('renders expandable tool inputs, outputs, failures and insufficient evidence', () => {
        const fixture = TestBed.createComponent(AgentChatComponent);
        fixture.detectChanges();
        const transcript = fixture.nativeElement.querySelector('[role=log]') as HTMLElement;
        Object.defineProperty(transcript, 'scrollHeight', {value: 700});
        store.chatQuestion.set('vibration');
        store.send();
        http.expectOne('/api/agent/chat').flush({
            ...chat,
            insufficientEvidence: true,
            evidence: [{...chat.evidence[0], success: false}]
        });
        fixture.detectChanges();
        const element: HTMLElement = fixture.nativeElement;
        expect(element.querySelectorAll('details').length).toBe(2);
        expect(transcript.scrollTop).toBe(700);
        expect(element.textContent).toContain('Input parameters');
        expect(element.textContent).toContain('"question": "vibration"');
        expect(element.textContent).toContain('"text": "Stop"');
        expect(element.textContent).toContain('Failed');
        expect(element.textContent).toContain('Insufficient evidence');
    });

    it('cancels requests and clears both panels on session replacement', () => {
        store.chatQuestion.set('hello');
        store.send();
        http.expectOne('/api/agent/chat').flush(chat);
        store.ragQuestion.set('manual');
        store.search();
        const ragRequest = http.expectOne('/api/rag/query');
        store.chatQuestion.set('again');
        store.send();
        const chatRequest = http.expectOne('/api/agent/chat');
        token.set('different-admin');
        TestBed.tick();
        expect(ragRequest.cancelled).toBe(true);
        expect(chatRequest.cancelled).toBe(true);
        expect(store.messages()).toEqual([]);
        expect(store.ragAnswer()).toBeNull();
        expect(store.chatBusy()).toBe(false);
        expect(store.ragBusy()).toBe(false);
        store.chatQuestion.set('new owner');
        store.send();
        const req = http.expectOne('/api/agent/chat');
        expect(req.request.body.conversationId).toBeUndefined();
        req.flush(chat);
    });

    it('rejects non-admin, blank and oversized submissions', () => {
        store.ragQuestion.set(' ');
        store.search();
        store.chatQuestion.set('x'.repeat(2001));
        store.send();
        admin.set(false);
        TestBed.tick();
        store.ragQuestion.set('manual');
        store.search();
        store.chatQuestion.set('hello');
        store.send();
        http.expectNone(req => req.url.startsWith('/api/'));
    });

    it('handles unavailable AI without automatic retries and allows explicit resubmission', () => {
        store.ragQuestion.set('manual');
        store.search();
        http.expectOne('/api/rag/query').flush({}, {status: 503, statusText: 'Unavailable'});
        expect(store.ragError()).toContain('AI is unavailable');
        expect(store.ragBusy()).toBe(false);
        http.expectNone('/api/rag/query');
        store.search();
        http.expectOne('/api/rag/query').flush(rag);
        expect(store.ragError()).toBe('');
        store.chatQuestion.set('follow up');
        store.send();
        http.expectOne('/api/agent/chat').flush({}, {status: 404, statusText: 'Expired'});
        expect(store.chatError()).toContain('new conversation');
        expect(store.chatQuestion()).toBe('follow up');
        expect(store.chatBusy()).toBe(false);
    });

    it('times out slow requests without replaying them', () => {
        vi.useFakeTimers();
        try {
            store.ragQuestion.set('manual');
            store.search();
            const req = http.expectOne('/api/rag/query');
            vi.advanceTimersByTime(180_000);
            expect(req.cancelled).toBe(true);
            expect(store.ragBusy()).toBe(false);
            expect(store.ragError()).toContain('timed out');
            http.expectNone('/api/rag/query');
        } finally {
            vi.useRealTimers();
        }
    });

    it('cancels outstanding work when the dashboard is destroyed', () => {
        store.chatQuestion.set('hello');
        store.send();
        const req = http.expectOne('/api/agent/chat');
        TestBed.resetTestingModule();
        expect(req.cancelled).toBe(true);
    });
});
