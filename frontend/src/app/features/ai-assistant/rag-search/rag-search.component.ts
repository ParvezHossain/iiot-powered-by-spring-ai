import {Component, inject} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {AiAssistantStore, formatJson} from '../ai-assistant.store';

@Component({
    selector: 'app-rag-search',
    imports: [FormsModule],
    templateUrl: './rag-search.component.html',
})
export class RagSearchComponent {
    readonly store = inject(AiAssistantStore);
    readonly format = formatJson;
}
