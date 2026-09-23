import {Component, effect, inject} from '@angular/core';
import {FormsModule} from '@angular/forms';
import {McpClientService} from './mcp-client.service';
import {formatJson} from '../ai-assistant/ai-assistant.store';

@Component({
    selector: 'app-mcp',
    imports: [FormsModule],
    providers: [McpClientService],
    templateUrl: './mcp.component.html'
})
export class McpComponent {
    readonly client = inject(McpClientService);
    readonly format = formatJson;
    selectedTool = '';
    arguments = '{}';

    constructor() {
        effect(() => {
            this.client.auth.accessToken();
            this.selectedTool = '';
            this.arguments = '{}';
        });
    }
}
