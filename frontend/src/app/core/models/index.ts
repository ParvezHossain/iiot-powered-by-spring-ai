/** JSON wire contracts matching Spring controllers. Dates are ISO-8601 strings; IDs are UUID strings. */
export type JsonValue = string | number | boolean | null | JsonValue[] | { [key: string]: JsonValue };
export type Role = 'USER' | 'ADMIN';
export interface User { id: string; username: string; email: string; role: Role; enabled: boolean; }
export interface LoginCredentials { usernameOrEmail: string; password: string; }
export interface AuthResponse {
  accessToken: string;
  tokenType: 'Bearer';
  expiresIn: number;
  refreshToken: string;
  refreshExpiresAt: string;
  user: User;
}
export interface SensorReading { id: number; machineId: string; metricType: string; value: number; timestamp: string; }
export type MachineOperatingState = 'RUNNING' | 'IDLE' | 'STOPPED' | 'MAINTENANCE' | 'FAULTED' | 'OFFLINE';
export interface MachineStatus {
  id: string; name: string; location: string | null; status: MachineOperatingState; latestReadings: SensorReading[];
}
export interface TelemetryQueryParams { from: string; to: string; metricType?: string; limit?: number; offset?: number; }
export interface AnomalyQueryParams { machineId?: string; from?: string; to?: string; limit?: number; offset?: number; }
export interface AnomalyBaseline {
  sampleCount: number; mean: number; standardDeviation: number; scale: number; zScore: number; threshold: number;
}
export interface AnomalyAlert { reading: SensorReading; reason: string; baseline: AnomalyBaseline | null; }
export interface DocumentSearchResult { id: string; score: number | null; text: string | null; metadata: Record<string, JsonValue>; }
export interface RagQueryRequest { question: string; }
export interface Citation {
  sourceId: string; chunkId: string; documentId: JsonValue; source: JsonValue; section: JsonValue; quote: string; score: number | null;
}
export interface RagQueryResponse { answer: string; insufficientEvidence: boolean; citations: Citation[]; }
export interface AgentChatRequest { question: string; conversationId?: string | null; }
/** The backend calls these entries Evidence; result is serialized text, not a parsed object. */
export interface ToolExecution { id: string; tool: string; success: boolean; input?: string | null; result: string; }
export interface AgentChatResponse {
  conversationId: string; answer: string; insufficientEvidence: boolean; evidenceIds: string[]; evidence: ToolExecution[];
}
