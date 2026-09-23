import { AnomalyAlert } from '../../core/models';

/** Synthetic fixtures only. Negative reading IDs and a demo-only machine ID cannot represent live readings. */
export function demoAnomalies(now: number): AnomalyAlert[] {
  return [
    { reading: { id: -1, machineId: 'demo-machine', metricType: 'vibration_mm_s', value: 12.5, timestamp: new Date(now - 30_000).toISOString() }, reason: 'HIGH_VIBRATION', baseline: { sampleCount: 30, mean: 3.5, standardDeviation: 1, scale: 1, zScore: 9, threshold: 4 } },
    { reading: { id: -2, machineId: 'demo-machine', metricType: 'temperature_celsius', value: 78, timestamp: new Date(now - 90_000).toISOString() }, reason: 'HIGH_TEMPERATURE', baseline: { sampleCount: 30, mean: 72, standardDeviation: 1, scale: 1, zScore: 6, threshold: 4 } },
    { reading: { id: -3, machineId: 'demo-machine', metricType: 'modbus_hr_40001', value: 65535, timestamp: new Date(now - 180_000).toISOString() }, reason: 'SENSOR_DROPOUT', baseline: null },
  ];
}
