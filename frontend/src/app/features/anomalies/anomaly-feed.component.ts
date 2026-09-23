import {DOCUMENT, DatePipe, DecimalPipe} from '@angular/common';
import {Component, inject} from '@angular/core';
import {LucideTriangleAlert} from '@lucide/angular';
import {AnomalyAlert} from '../../core/models';
import {CHART_METRICS, TelemetryStore} from '../telemetry/telemetry.store';
import {AnomalyFeedStore, isCritical} from './anomaly-feed.store';

@Component({
    selector: 'app-anomaly-feed',
    host: {class: 'block min-w-0'},
    imports: [DatePipe, DecimalPipe, LucideTriangleAlert],
    providers: [AnomalyFeedStore],
    templateUrl: './anomaly-feed.component.html',
})
export class AnomalyFeedComponent {
    protected readonly store = inject(AnomalyFeedStore);
    protected readonly telemetry = inject(TelemetryStore);
    private readonly document = inject(DOCUMENT);
    protected readonly critical = isCritical;
    protected readonly reasons: Record<string, string> = {
        HIGH_TEMPERATURE: 'High temperature', LOW_TEMPERATURE: 'Low temperature',
        HIGH_VIBRATION: 'High vibration', LOW_VIBRATION: 'Low vibration', SENSOR_DROPOUT: 'Sensor dropout',
    };

    protected metric(alert: AnomalyAlert) {
        return CHART_METRICS.find(metric => metric.key === alert.reading.metricType);
    }

    protected machineName(alert: AnomalyAlert): string {
        return this.store.feed().source === 'demo' ? 'Demo machine'
            : this.telemetry.fleet().data.find(machine => machine.id === alert.reading.machineId)?.name ?? alert.reading.machineId;
    }

    protected focus(alert: AnomalyAlert): void {
        if (!this.telemetry.focusAnomaly(alert.reading, this.store.feed().source === 'demo')) return;
        const chart = this.document.getElementById('telemetry-chart');
        chart?.focus({preventScroll: true});
        chart?.scrollIntoView({
            block: 'start',
            behavior: this.document.defaultView?.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'
        });
    }
}
