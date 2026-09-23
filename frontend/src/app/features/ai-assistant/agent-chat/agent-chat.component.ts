import {Component, ElementRef, afterRenderEffect, inject, viewChild} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {AiAssistantStore, formatJson} from '../ai-assistant.store';

@Component({
    selector: 'app-agent-chat',
    imports: [FormsModule],
    templateUrl: './agent-chat.component.html',
})
export class AgentChatComponent {
    readonly store = inject(AiAssistantStore);
    readonly format = formatJson;
    private readonly transcript = viewChild<ElementRef<HTMLElement>>('transcript');

    constructor() {
        afterRenderEffect(() => {
            this.store.messages();
            this.store.chatBusy();
            const element = this.transcript()?.nativeElement;
            if (element) element.scrollTop = element.scrollHeight;
        });
    }
}
