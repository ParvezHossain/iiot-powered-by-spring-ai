import {Component, DestroyRef, effect, inject, signal} from '@angular/core';
import {HttpClient} from '@angular/common/http';
import {FormsModule} from '@angular/forms';
import {Subscription, finalize, timeout} from 'rxjs';
import {AuthService} from '../../core/auth.service';
import {DocumentSearchResult} from '../../core/models';
import {apiError} from '../../core/api-feedback';
import {formatJson} from '../ai-assistant/ai-assistant.store';

@Component({selector: 'app-documents', imports: [FormsModule], templateUrl: './documents.component.html'})
export class DocumentsComponent {
    readonly auth = inject(AuthService);
    private readonly http = inject(HttpClient);
    private requests = new Subscription();
    readonly results = signal<DocumentSearchResult[]>([]);
    readonly ingestion = signal<{ documents: number; chunks: number; model: string } | null>(null);
    readonly busy = signal(false);
    readonly searched = signal(false);
    readonly error = signal('');
    readonly format = formatJson;
    query = '';
    topK = 5;
    threshold = 0;
    replaceConfirmed = false;

    constructor() {
        effect(() => {
            this.auth.accessToken();
            this.auth.isAdmin();
            this.requests.unsubscribe();
            this.requests = new Subscription();
            this.results.set([]);
            this.ingestion.set(null);
            this.error.set('');
            this.searched.set(false);
            this.busy.set(false);
            this.replaceConfirmed = false;
            this.query = '';
            this.topK = 5;
            this.threshold = 0;
        });
        inject(DestroyRef).onDestroy(() => this.requests.unsubscribe());
    }

    search(): void {
        if (!this.auth.isAdmin() || this.busy() || !this.query.trim() || this.query.length > 2000 || !Number.isInteger(this.topK)
            || this.topK < 1 || this.topK > 20 || !Number.isFinite(this.threshold) || this.threshold < 0 || this.threshold > 1) return;
        this.busy.set(true);
        this.error.set('');
        this.results.set([]);
        this.searched.set(false);
        this.requests.add(this.http.get<DocumentSearchResult[]>('/api/documents/search', {
            params: {
                query: this.query.trim(),
                topK: this.topK,
                threshold: this.threshold
            }
        })
            .pipe(timeout(180_000), finalize(() => this.busy.set(false))).subscribe({
                next: results => {
                    this.results.set(results);
                    this.searched.set(true);
                }, error: error => this.error.set(apiError(error)),
            }));
    }

    ingest(): void {
        if (!this.auth.isAdmin() || this.busy() || !this.replaceConfirmed) return;
        this.busy.set(true);
        this.error.set('');
        this.ingestion.set(null);
        this.replaceConfirmed = false;
        this.requests.add(this.http.post<{
            documents: number;
            chunks: number;
            model: string
        }>('/api/documents/ingest', null)
            .pipe(timeout(600_000), finalize(() => this.busy.set(false))).subscribe({
                next: result => {
                    this.ingestion.set(result);
                    this.results.set([]);
                    this.searched.set(false);
                }, error: error => this.error.set(apiError(error)),
            }));
    }
}
