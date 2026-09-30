import axios from "axios";
import type {
  GameConfig,
  GameEvent,
  GameResult,
  Role,
  ObserverNote,
} from "./types";

/** 实验接口只供用户界面使用，不参与 Agent 的决策请求。 */
export interface AgentMetrics {
  apiCalls: number;
  apiFailures: number;
  invalidReplies: number;
  usageReportedCalls: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  requestMillis: number;
}
export interface ExperimentAction {
  sequence: number;
  day: number;
  phase: string;
  playerId: string;
  action: string | null;
  targetPlayerId: string | null;
  status: string;
  decisionMillis: number;
  metrics: AgentMetrics;
}
export interface ExperimentGame {
  gameId: string;
  seed: number;
  startedAt: string;
  endedAt: string | null;
  status: string;
  result: GameResult;
  days: number;
  elapsedMillis: number | null;
  model: { model: string; temperature: number; maxTokens: number } | null;
  seats: { playerId: string; role: Role; agentType: string; status: string }[];
  actions: ExperimentAction[];
  events: GameEvent[];
  observerNotes?: ObserverNote[] | null;
  knowledge?: import("./knowledge").KnowledgeRun | null;
}
export interface ExperimentSummary {
  completedGames: number;
  failedGames: number;
  wolfWins: number;
  goodWins: number;
  averageDays: number;
  actionAttempts: number;
  validActions: number;
  invalidActions: number;
  failedActions: number;
  invalidReplies: number;
  apiCalls: number;
  apiFailures: number;
  usageReportedCalls: number;
  promptTokens: number;
  completionTokens: number;
  totalTokens: number;
  decisionMillis: number;
  requestMillis: number;
}
export interface ExperimentRecord {
  schemaVersion: number;
  engineVersion: string;
  id: string;
  name: string;
  source: string;
  status: string;
  createdAt: string;
  updatedAt: string;
  config: GameConfig;
  agentTypes: ("RANDOM" | "RULE" | "LLM")[];
  startSeed: number;
  requestedGames: number;
  games: ExperimentGame[];
  errorCode: string | null;
  group: string;
  notes: string;
}
export interface ExperimentView {
  record: ExperimentRecord;
  summary: ExperimentSummary;
  metadataRevision: string;
}
export interface ExperimentItem {
  id: string;
  name: string;
  source: string;
  status: string;
  createdAt: string;
  startSeed: number;
  requestedGames: number;
  recordedGames: number;
  summary: ExperimentSummary;
  group: string;
  notes: string;
  config: GameConfig;
  agentTypes: ("RANDOM" | "RULE" | "LLM")[];
  models: string[];
  knowledgeModes: string[];
  knowledgeRevisions: string[];
}
export interface BatchRequest {
  name: string;
  config: GameConfig;
  agentTypes: ("RANDOM" | "RULE")[];
  startSeed: number;
  runs: number;
  group?: string;
  notes?: string;
}
export interface ArchiveItem {
  experimentId: string;
  experimentName: string;
  source: string;
  experimentStatus: string;
  gameId: string;
  status: string;
  result: GameResult;
  days: number;
  playerCount: number;
  startedAt: string;
}
export const experimentApi = {
  updateMetadata: async (
    id: string,
    revision: string,
    metadata: import("./analysis").ExperimentMetadata,
  ) =>
    (
      await axios.patch<ExperimentView>(
        `/api/experiments/${id}/metadata`,
        metadata,
        { params: { revision } },
      )
    ).data,
  backfillObserver: async (id: string, gameId: string, notes: ObserverNote[]) =>
    (
      await axios.post<ExperimentGame>(
        `/api/experiments/${id}/games/${gameId}/observer-notes`,
        notes,
      )
    ).data,
  storage: async () =>
    (
      await axios.get<{ directory: string; visibleRecords: number }>(
        "/api/experiments/storage",
      )
    ).data,
  archives: async () =>
    (await axios.get<ArchiveItem[]>("/api/experiments/games")).data,
  replay: async (id: string, gameId: string) =>
    (await axios.get<ExperimentGame>(`/api/experiments/${id}/games/${gameId}`))
      .data,
  async importFile(file: File) {
    const body = new FormData();
    body.append("file", file);
    return (await axios.post<ExperimentView>("/api/experiments/import", body))
      .data;
  },
  list: async () =>
    (await axios.get<ExperimentItem[]>("/api/experiments")).data,
  get: async (id: string) =>
    (await axios.get<ExperimentView>(`/api/experiments/${id}`)).data,
  start: async (body: BatchRequest) =>
    (await axios.post<ExperimentView>("/api/experiments", body)).data,
  cancel: async (id: string) =>
    (await axios.post<ExperimentView>(`/api/experiments/${id}/cancel`)).data,
  async download(id: string, format: "json" | "csv" | "actions") {
    const response = await axios.get<Blob>(`/api/experiments/${id}/export`, {
      params: { format },
      responseType: "blob",
    });
    const url = URL.createObjectURL(response.data);
    const link = document.createElement("a");
    link.href = url;
    link.download = `experiment-${id}${format === "json" ? ".json" : format === "actions" ? "-actions.csv" : ".csv"}`;
    document.body.append(link);
    link.click();
    link.remove();
    // 留给浏览器处理下载的时间，再释放临时 URL。
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
};
export function experimentError(error: unknown): string {
  return axios.isAxiosError(error)
    ? error.response?.status === 413
      ? "JSON 文件不能超过 25 MB。"
      : error.response?.status === 403
        ? "该记录属于另一浏览器，或本浏览器的访问凭据已清除。"
        : typeof error.response?.data?.error === "string"
          ? error.response.data.error
          : "实验操作失败，请确认后端已启动并更新到当前版本。"
    : "实验操作失败。";
}
