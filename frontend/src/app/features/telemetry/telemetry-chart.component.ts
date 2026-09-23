import { DatePipe, DecimalPipe } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { BaseChartDirective, provideCharts } from 'ng2-charts';
import { ChartData, ChartOptions, LineController, LineElement, LinearScale, PointElement, Plugin, Tooltip } from 'chart.js';
import { METRICS, TelemetryStore } from './telemetry.store';

@Component({
  selector: 'app-telemetry-chart',
  host: { class: 'block min-w-0' },
  imports: [BaseChartDirective, DatePipe, DecimalPipe],
  providers: [provideCharts({ registerables: [LineController, LineElement, LinearScale, PointElement, Tooltip] })],
  templateUrl: './telemetry-chart.component.html',
})
export class TelemetryChartComponent {
  protected readonly store = inject(TelemetryStore);
  protected readonly metrics = METRICS;
  protected readonly windows = [5, 10, 30];
  protected readonly recent = computed(() => this.store.history().data.slice(-20).reverse());
  protected readonly latest = computed(() => this.store.history().data.at(-1));
  protected readonly chartData = computed<ChartData<'line', { x: number; y: number }[]>>(() => ({
    datasets: [{
      label: this.store.metricInfo().label,
      data: this.store.history().data.map(reading => ({ x: Date.parse(reading.timestamp), y: reading.value })),
      borderColor: this.store.metricInfo().color,
      backgroundColor: this.store.metricInfo().color,
      borderWidth: 2,
      pointRadius: this.store.history().data.length === 1 ? 3 : 0,
      pointHitRadius: 12,
      pointHoverRadius: 4,
      tension: 0,
      spanGaps: 15_000,
    }],
  }));
  protected readonly chartOptions = computed<ChartOptions<'line'>>(() => ({
    responsive: true,
    maintainAspectRatio: false,
    animation: false,
    parsing: false,
    interaction: { mode: 'nearest', axis: 'x', intersect: false },
    plugins: {
      tooltip: {
        backgroundColor: '#0B0F19', titleColor: '#ffffff', bodyColor: '#e2e8f0',
        borderColor: '#64748b', borderWidth: 1, padding: 12,
        callbacks: {
          title: items => items.length ? new Date(items[0].parsed.x!).toLocaleString() : '',
          label: item => `${this.store.metricInfo().label}: ${item.parsed.y?.toFixed(this.store.metricInfo().decimals)} ${this.store.metricInfo().unit}`,
        },
      },
    },
    scales: {
      x: {
        type: 'linear',
        grid: { color: 'rgba(148,163,184,0.08)' },
        ticks: { color: '#94a3b8', maxTicksLimit: 5, maxRotation: 0, callback: value => new Date(Number(value)).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false }) },
        title: { display: true, text: 'Local time', color: '#94a3b8' },
      },
      y: {
        grid: { color: 'rgba(148,163,184,0.08)' },
        ticks: { color: '#94a3b8', maxTicksLimit: 5 },
        title: { display: true, text: this.store.metricInfo().unit, color: '#94a3b8' },
      },
    },
  }));
  protected readonly glow: Plugin<'line'>[] = [{
    id: 'telemetry-glow',
    beforeDatasetDraw: chart => {
      chart.ctx.save();
      chart.ctx.shadowColor = this.store.metricInfo().color;
      chart.ctx.shadowBlur = 7;
    },
    afterDatasetDraw: chart => chart.ctx.restore(),
  }];
}
