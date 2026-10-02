export type Role = "WEREWOLF" | "VILLAGER" | "SEER" | "WITCH";
export type AgentType = "RANDOM" | "RULE" | "LLM";
export type GameResult = "ONGOING" | "WEREWOLF_WIN" | "VILLAGER_WIN";
export type AdvanceCommand =
  "NEXT_ACTION" | "COMPLETE_PHASE" | "END_DAY" | "NONE";
/** 与后端 GameConfig 同名字段，创建前后分别校验。 */
export interface GameConfig {
  playerCount: number;
  werewolves: number;
  villagers: number;
  seers: number;
  witches: number;
}
export interface LlmConfig {
  baseUrl: string;
  apiKey: string;
  model: string;
  temperature: number;
  maxTokens: number;
  /** 创建对局时冻结决策模式；不支持工具的兼容服务继续使用 JSON。 */
  decisionMode: "JSON" | "TOOLS" | "TOOLS_STRICT";
  requestTimeoutSeconds: number;
  tokenLimitParameter: "MAX_TOKENS" | "MAX_COMPLETION_TOKENS";
  /** null 不发送服务特有参数；关闭选项仅用于支持 enable_thinking 的服务。 */
  enableThinking: boolean | null;
  toolChoiceMode: "REQUIRED" | "AUTO";
}
export interface GameSummary {
  gameId: string;
  result: GameResult;
  days: number;
  eventCount: number;
  playerCount: number;
  phase: string;
}
export interface Player {
  id: string;
  name: string;
  role: Role | null;
  status: "ALIVE" | "DEAD";
}
export interface GameEvent {
  day: number;
  phase: string;
  type: string;
  actorId: string | null;
  targetId: string | null;
  text: string;
}
export interface Game {
  summary: GameSummary;
  config: GameConfig;
  players: Player[];
  events: GameEvent[];
  manual: boolean;
  nextCommand: AdvanceCommand;
  nextActorId: string | null;
  retryBlocked?: boolean;
}
/** 观战接口与普通对局接口分开，只有页面读取此数据。 */
export interface ObserverNote {
  day: number;
  phase: string;
  eventIndex: number;
  playerId: string;
  kind: "CLUE" | "DECISION";
  text: string;
}
export interface ObserverView {
  actions?: import("./experiments").ExperimentAction[];
  players: Player[];
  notes: ObserverNote[];
  knowledge?: import("./knowledge").KnowledgeRun | null;
}
export const roleName: Record<Role, string> = {
  WEREWOLF: "狼人",
  VILLAGER: "村民",
  SEER: "预言家",
  WITCH: "女巫",
};
export const roleImage: Record<Role, string> = {
  WEREWOLF: "/assets/werewolf.png",
  VILLAGER: "/assets/villager.png",
  SEER: "/assets/prophet.png",
  WITCH: "/assets/witch.png",
};
export const phaseName: Record<string, string> = {
  GAME_START: "开局",
  NIGHT_WEREWOLF: "狼人行动",
  NIGHT_SEER: "预言家查验",
  NIGHT_WITCH: "女巫行动",
  NIGHT_RESOLVE: "夜晚结算",
  DAY_ANNOUNCEMENT: "天亮公告",
  DAY_DISCUSSION: "公开讨论",
  DAY_VOTE: "白天投票",
  DAY_RESOLVE: "放逐结算",
  GAME_OVER: "游戏结束",
};
