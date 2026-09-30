import axios from "axios";
import type { GameConfig } from "./types";

/** 与后端的逐对局筛选、描述性指标保持一致，比例在接口中为 0–1。 */
export interface ExperimentMetadata {
  name: string;
  group: string;
  notes: string;
}
export interface AnalysisFilter {
  group: string | null;
  model: string | null;
  config: string | null;
  knowledgeMode: string | null;
  knowledgeRevision: string | null;
  source: string;
  agentTypes: string | null;
}
export interface AnalysisOptions {
  groups: string[];
  models: string[];
  configs: string[];
  knowledgeModes: string[];
  knowledgeRevisions: string[];
  agentTypes: string[];
}
export interface AnalysisMetrics {
  matchedGames: number;
  completedGames: number;
  excludedGames: number;
  duplicateGames: number;
  wolfWins: number;
  goodWins: number;
  wolfWinRate: number | null;
  goodWinRate: number | null;
  averageDays: number | null;
  goodVotes: number;
  goodVotesForWolf: number;
  goodVoteHitRate: number | null;
  actionAttempts: number;
  invalidActions: number;
  failedActions: number;
  invalidActionRate: number | null;
  apiCalls: number;
  apiFailures: number;
  invalidReplies: number;
  invalidReplyRate: number | null;
  correctionRetries: number;
  llmDecisions: number;
  averageLlmDecisionMillis: number | null;
  averageRequestMillis: number | null;
  usageReportedCalls: number;
  usageCoverage: number | null;
  reportedTokens: number | null;
  averageReportedTokens: number | null;
}
export interface AnalysisReference {
  experimentId: string;
  gameId: string;
  name: string;
  group: string;
  source: string;
  seed: number;
  status: string;
  model: string;
  knowledgeMode: string;
  knowledgeRevision: string | null;
}
export interface AnalysisCohort {
  name: string;
  filter: AnalysisFilter;
  metrics: AnalysisMetrics;
  games: AnalysisReference[];
  profiles: {
    configs: string[];
    models: string[];
    agentTypes: string[];
    engineVersions: string[];
    knowledgeModes: string[];
    knowledgeRevisions: string[];
  };
}
export interface AnalysisRequest {
  nameA: string;
  nameB: string;
  groupA: AnalysisFilter;
  groupB: AnalysisFilter;
}
export interface AnalysisComparison {
  generatedAt: string;
  groupA: AnalysisCohort;
  groupB: AnalysisCohort;
  overlapGames: number;
  notices: string[];
}
export const emptyOptions = (): AnalysisOptions => ({
  groups: [],
  models: [],
  configs: [],
  knowledgeModes: [],
  knowledgeRevisions: [],
  agentTypes: [],
});
export const emptyFilter = (): AnalysisFilter => ({
  group: null,
  model: null,
  config: null,
  knowledgeMode: null,
  knowledgeRevision: null,
  source: "LOCAL",
  agentTypes: null,
});
export const modeLabel: Record<string, string> = {
  NONE: "关闭知识",
  COMMON: "仅通用知识",
  ROLE: "通用 + 角色知识",
  NOT_APPLICABLE: "不使用 LLM",
  UNKNOWN: "旧记录，知识模式未知",
};
export function configKey(c: GameConfig) {
  return `${c.playerCount}/${c.werewolves}/${c.villagers}/${c.seers}/${c.witches}`;
}
export function configLabel(key: string) {
  const [count, wolves, villagers, seers, witches] = key.split("/");
  return `${count} 人 · ${wolves} 狼 / ${villagers} 村 / ${seers} 预言家 / ${witches} 女巫`;
}
export function agentsLabel(key: string) {
  const types = key.split("/");
  return types.every((t) => t === types[0])
    ? `全部 ${types[0]}（${types.length} 人）`
    : types.map((t, i) => `${i + 1}:${t}`).join(" · ");
}
export const analysisApi = {
  options: async () =>
    (await axios.get<AnalysisOptions>("/api/experiments/analysis/options"))
      .data,
  compare: async (request: AnalysisRequest) =>
    (await axios.post<AnalysisComparison>("/api/experiments/compare", request))
      .data,
  download: async (request: AnalysisRequest, format: "csv" | "json") => {
    const response = await axios.post<Blob>(
      "/api/experiments/compare/export",
      request,
      { params: { format }, responseType: "blob" },
    );
    const url = URL.createObjectURL(response.data);
    const link = document.createElement("a");
    link.href = url;
    link.download = `experiment-comparison.${format}`;
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  },
};
export interface MetricRow {
  key: keyof AnalysisMetrics;
  label: string;
  kind: "count" | "rate" | "decimal";
  definition: string;
}
export const metricRows: MetricRow[] = [
  {
    key: "matchedGames",
    label: "匹配对局",
    kind: "count",
    definition: "筛选后按对局编号去重",
  },
  {
    key: "completedGames",
    label: "完成对局",
    kind: "count",
    definition: "以下指标只使用已完成对局",
  },
  {
    key: "excludedGames",
    label: "未完成对局",
    kind: "count",
    definition: "保留在样本列表，不计入以下指标",
  },
  {
    key: "duplicateGames",
    label: "去重副本",
    kind: "count",
    definition: "同组内相同对局编号的副本数",
  },
  {
    key: "wolfWinRate",
    label: "狼人胜率",
    kind: "rate",
    definition: "狼人胜场 / 完成局数",
  },
  {
    key: "goodWinRate",
    label: "好人胜率",
    kind: "rate",
    definition: "好人胜场 / 完成局数",
  },
  {
    key: "averageDays",
    label: "平均轮次",
    kind: "decimal",
    definition: "完成局轮次之和 / 完成局数",
  },
  {
    key: "goodVoteHitRate",
    label: "好人投票命中率",
    kind: "rate",
    definition: "实际好人身份投中狼人的合法票数 / 好人合法票数",
  },
  {
    key: "goodVotes",
    label: "好人合法投票数",
    kind: "count",
    definition: "实际身份为好人的 VALID VOTE",
  },
  {
    key: "goodVotesForWolf",
    label: "好人投中狼人票数",
    kind: "count",
    definition: "合法投票目标的真实身份为狼人",
  },
  {
    key: "actionAttempts",
    label: "行动尝试数",
    kind: "count",
    definition: "含 VALID、INVALID 与 ERROR",
  },
  {
    key: "invalidActionRate",
    label: "非法行动率",
    kind: "rate",
    definition: "INVALID / 行动尝试；与模型非法回复率分别统计",
  },
  {
    key: "failedActions",
    label: "失败行动数",
    kind: "count",
    definition: "ERROR 行动尝试",
  },
  {
    key: "apiCalls",
    label: "模型调用数",
    kind: "count",
    definition: "含纠错重试和失败调用",
  },
  {
    key: "apiFailures",
    label: "模型调用失败数",
    kind: "count",
    definition: "接口或服务错误",
  },
  {
    key: "invalidReplyRate",
    label: "非法模型回复率",
    kind: "rate",
    definition: "格式或动作不合法的模型回复 / 模型调用",
  },
  {
    key: "correctionRetries",
    label: "模型纠错重试数",
    kind: "count",
    definition: "每步调用次数减一，不含用户点击重试",
  },
  {
    key: "averageLlmDecisionMillis",
    label: "平均模型决策耗时（ms）",
    kind: "decimal",
    definition: "模型行动耗时 / 调用模型的行动次数；含纠错重试",
  },
  {
    key: "averageRequestMillis",
    label: "平均接口请求耗时（ms）",
    kind: "decimal",
    definition: "请求耗时之和 / 模型调用次数",
  },
  {
    key: "usageCoverage",
    label: "用量报告覆盖率",
    kind: "rate",
    definition: "实际返回 usage 的调用 / 全部模型调用",
  },
  {
    key: "usageReportedCalls",
    label: "已报告用量调用数",
    kind: "count",
    definition: "用于 Token 指标的分母",
  },
  {
    key: "reportedTokens",
    label: "已报告 Token 总数",
    kind: "count",
    definition: "仅累计接口已返回的用量，缺失显示暂无数据",
  },
  {
    key: "averageReportedTokens",
    label: "平均已报告 Token / 调用",
    kind: "decimal",
    definition: "已报告 Token / 已报告用量调用数",
  },
];
export function metricValue(
  value: number | null,
  kind: MetricRow["kind"],
  difference = false,
): string {
  if (value === null) return "—";
  const result = kind === "rate" ? value * 100 : value;
  const sign = difference && result > 0 ? "+" : "";
  return `${sign}${kind === "count" ? result.toLocaleString("zh-CN") : result.toFixed(2)}${kind === "rate" ? (difference ? " 个百分点" : "%") : ""}`;
}
