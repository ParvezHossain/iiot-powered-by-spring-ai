import {RagSearchComponent} from './features/ai-assistant/rag-search/rag-search.component';
import {AgentChatComponent} from './features/ai-assistant/agent-chat/agent-chat.component';
import {AiAssistantStore} from './features/ai-assistant/ai-assistant.store';
import {AnomalyFeedComponent} from './features/anomalies/anomaly-feed.component';
import {Component, inject} from '@angular/core';

import {MachineGridComponent} from './features/telemetry/machine-grid.component';
import {TelemetryChartComponent} from './features/telemetry/telemetry-chart.component';
import {TelemetryStore} from './features/telemetry/telemetry.store';
import {AuthService} from './core/auth.service';

@Component({
    selector: 'app-home',
    imports: [MachineGridComponent, TelemetryChartComponent, AnomalyFeedComponent, RagSearchComponent, AgentChatComponent],
    providers: [TelemetryStore, AiAssistantStore],
    templateUrl: './home.component.html',
})
export class HomeComponent {
    protected readonly auth = inject(AuthService);
}
