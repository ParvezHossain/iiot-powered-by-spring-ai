import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { DestroyRef, Injectable, effect, inject, signal } from '@angular/core';
import { Subscription, TimeoutError, finalize, timeout } from 'rxjs';
import { AuthService } from '../../core/auth.service';
import { AgentChatRequest, AgentChatResponse, RagQueryResponse } from '../../core/models';

export interface ChatMessage { role: 'user' | 'assistant'; text: string; response?: AgentChatResponse; }
export function formatJson(value: unknown): string {
  if (value === null || value === undefined) return 'Not provided';
  if (typeof value === 'string') {
    try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
  }
  return JSON.stringify(value, null, 2);
}
function failure(error: unknown): string {
  if (error instanceof TimeoutError) return 'The request timed out. The server may still be processing it. Try again when ready.';
  if (error instanceof HttpErrorResponse) {
    switch (error.status) {
      case 401: return 'Your session expired. Sign in again.';
      case 403: return 'An administrator account is required.';
      case 404: return 'This conversation or AI endpoint is unavailable. For chat, start a new conversation.';
      case 409: return 'A reply is already being generated for this conversation. Wait before sending again.';
      case 503: return 'AI is unavailable. Check that RAG, the agent, and their model services are enabled.';
      case 400: return 'The question could not be accepted. Use between 1 and 2,000 characters.';
    }
  }
  return 'Unable to reach the AI service. Your request has not been automatically retried.';
}

@Injectable()
export class AiAssistantStore {
  readonly auth = inject(AuthService);
  private readonly http = inject(HttpClient);
  private ragRequest?: Subscription;
  private chatRequest?: Subscription;
  private session: string | null = null;
  private conversationId: string | null = null;
  readonly ragQuestion = signal('');
  readonly chatQuestion = signal('');
  readonly ragAnswer = signal<RagQueryResponse | null>(null);
  readonly messages = signal<ChatMessage[]>([]);
  readonly ragBusy = signal(false);
  readonly chatBusy = signal(false);
  readonly ragError = signal('');
  readonly chatError = signal('');

  constructor() {
    effect(() => {
      const token = this.auth.accessToken();
      const admin = this.auth.isAdmin();
      if (token !== this.session || !admin) {
        this.reset();
        this.session = token;
      }
    });
    inject(DestroyRef).onDestroy(() => this.cancel());
  }

  private allowed(): boolean {
    // Also guard submissions that occur before the session effect has run.
    if (this.session !== this.auth.accessToken()) {
      this.reset();
      this.session = this.auth.accessToken();
    }
    return !!this.session && this.auth.isAdmin();
  }
  search(): void {
    if (!this.allowed() || this.ragBusy()) return;
    const question = this.ragQuestion().trim();
    if (!question || question.length > 2000) return;
    this.ragBusy.set(true); this.ragError.set(''); this.ragAnswer.set(null);
    const token = this.session;
    this.ragRequest = this.http.post<RagQueryResponse>('/api/rag/query', { question }).pipe(
      timeout(180_000), finalize(() => this.ragBusy.set(false)),
    ).subscribe({
      next: answer => { if (token === this.auth.accessToken()) this.ragAnswer.set(answer); },
      error: error => { if (token === this.auth.accessToken()) this.ragError.set(failure(error)); },
    });
  }
  send(): void {
    if (!this.allowed() || this.chatBusy()) return;
    const question = this.chatQuestion().trim();
    if (!question || question.length > 2000) return;
    const request: AgentChatRequest = { question, ...(this.conversationId ? { conversationId: this.conversationId } : {}) };
    this.chatBusy.set(true); this.chatError.set(''); this.chatQuestion.set('');
    this.messages.update(messages => [...messages, { role: 'user', text: question }]);
    const token = this.session;
    this.chatRequest = this.http.post<AgentChatResponse>('/api/agent/chat', request).pipe(
      timeout(180_000), finalize(() => this.chatBusy.set(false)),
    ).subscribe({
      next: response => {
        if (token !== this.auth.accessToken()) return;
        this.conversationId = response.conversationId;
        this.messages.update(messages => [...messages, { role: 'assistant', text: response.answer, response }]);
      },
      error: error => {
        if (token !== this.auth.accessToken()) return;
        this.chatError.set(failure(error)); this.chatQuestion.set(question);
      },
    });
  }
  newConversation(): void {
    this.chatRequest?.unsubscribe();
    this.conversationId = null;
    this.messages.set([]); this.chatQuestion.set(''); this.chatError.set('');
  }
  private cancel(): void { this.ragRequest?.unsubscribe(); this.chatRequest?.unsubscribe(); }
  private reset(): void {
    this.cancel(); this.newConversation(); this.ragQuestion.set(''); this.ragAnswer.set(null); this.ragError.set('');
  }
}
