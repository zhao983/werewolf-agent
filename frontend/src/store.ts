import { defineStore } from "pinia";
import axios from "axios";
import type { KnowledgeMode } from "./knowledge";
import type {
  AdvanceCommand,
  AgentType,
  Game,
  GameConfig,
  GameSummary,
  LlmConfig,
  ObserverView,
} from "./types";

export const useLabStore = defineStore("lab", {
  state: () => ({
    games: [] as GameSummary[],
    current: null as Game | null,
    observer: null as ObserverView | null,
    viewRequest: 0,
    config: {
      playerCount: 7,
      werewolves: 2,
      villagers: 3,
      seers: 1,
      witches: 1,
    } as GameConfig,
    agents: Array<AgentType>(7).fill("RANDOM"),
    manual: false,
    knowledgeMode: "NONE" as KnowledgeMode,
    experimentName: "",
    experimentGroup: "",
    experimentNotes: "",
    llm: {
      baseUrl: "",
      apiKey: "",
      model: "",
      temperature: 0.7,
      maxTokens: 1000,
      decisionMode: "JSON",
      requestTimeoutSeconds: 120,
      tokenLimitParameter: "MAX_TOKENS",
    } as LlmConfig,
    busy: false,
    error: "",
  }),
  actions: {
    /** 改人数时保留已有座位配置，并为新增座位填入 RandomAgent。 */
    setPlayerCount(count: number) {
      this.config.playerCount = count;
      if (!Number.isInteger(count) || count < 3 || count > 20) return;
      this.agents = Array.from(
        { length: count },
        (_, index) => this.agents[index] ?? "RANDOM",
      );
    },
    async loadGames() {
      try {
        this.games = (await axios.get<GameSummary[]>("/api/games")).data;
        this.error = "";
      } catch {
        this.error = "无法连接后端，请先启动 Spring Boot 服务。";
      }
    },
    async loadGame(id: string) {
      const request = ++this.viewRequest;
      this.busy = true;
      this.current = null;
      this.observer = null;
      try {
        const game = (await axios.get<Game>(`/api/games/${id}`)).data;
        if (request !== this.viewRequest) return;
        this.current = game;
        this.error = "";
        await this.loadObserver(id);
      } catch (error) {
        if (request !== this.viewRequest) return;
        this.error =
          axios.isAxiosError(error) && error.response?.status === 403
            ? "这场对局属于另一浏览器会话，请在创建它的页面打开。"
            : "未找到这场对局，或后端已重启。";
        this.current = null;
      } finally {
        if (request === this.viewRequest) this.busy = false;
      }
    },
    /** 观战数据不参与创建请求，也不拼入 Agent 决策输入。 */
    async loadObserver(id: string) {
      try {
        const observer = (
          await axios.get<ObserverView>(`/api/games/${id}/observer`)
        ).data;
        if (this.current?.summary.gameId === id) this.observer = observer;
      } catch {
        if (this.current?.summary.gameId === id) this.observer = null;
      }
    },
    async createGame(seed?: number): Promise<string | null> {
      // LLM 会话按单步模式创建；自动推进由用户开启，前端仍逐条提交推进指令。
      this.busy = true;
      this.error = "";
      try {
        const body = {
          config: this.config,
          agentTypes: this.agents,
          seed: seed ?? null,
          llm: this.agents.includes("LLM") ? this.llm : null,
          manual: this.manual || this.agents.includes("LLM"),
          knowledgeMode: this.knowledgeMode,
          experimentName: this.experimentName,
          experimentGroup: this.experimentGroup,
          experimentNotes: this.experimentNotes,
        };
        const game = (await axios.post<Game>("/api/games", body)).data;
        this.current = game;
        await this.loadObserver(game.summary.gameId);
        await this.loadGames();
        return game.summary.gameId;
      } catch (error) {
        this.error = axios.isAxiosError(error)
          ? error.response?.data?.error || "创建对局失败，请检查后端状态。"
          : "创建对局失败。";
        return null;
      } finally {
        this.busy = false;
      }
    },
    async advanceGame(id: string, command: AdvanceCommand): Promise<boolean> {
      // 每次仍只提交一条后端允许的指令；手动和自动推进共用此入口，禁止重叠请求。
      if (this.busy || this.current?.summary.gameId !== id) return false;
      const request = this.viewRequest;
      this.busy = true;
      this.error = "";
      try {
        const game = (
          await axios.post<Game>(`/api/games/${id}/advance`, { command })
        ).data;
        // 用户可能在模型等待期间切换对局；旧响应不能覆盖新对局或其错误状态。
        if (request !== this.viewRequest || this.current?.summary.gameId !== id)
          return false;
        this.current = game;
        await this.loadObserver(id);
        if (request !== this.viewRequest || this.current?.summary.gameId !== id)
          return false;
        if (this.current.summary.result !== "ONGOING") await this.loadGames();
        return true;
      } catch (error) {
        if (request !== this.viewRequest || this.current?.summary.gameId !== id)
          return false;
        this.error = axios.isAxiosError(error)
          ? error.response?.data?.error || "推进对局失败。"
          : "推进对局失败。";
        // 请求失败仍可能已经记录一次模型尝试，刷新用户观战数据以展示该次知识使用。
        await this.loadObserver(id);
        return false;
      } finally {
        if (request === this.viewRequest) this.busy = false;
      }
    },
  },
});
