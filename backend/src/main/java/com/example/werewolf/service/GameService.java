package com.example.werewolf.service;

import com.example.werewolf.agent.Agent;
import com.example.werewolf.agent.LlmAgent;
import com.example.werewolf.agent.RandomAgent;
import com.example.werewolf.agent.RuleAgent;
import com.example.werewolf.ai.LlmClient;
import com.example.werewolf.ai.LlmConfig;
import com.example.werewolf.ai.OpenAiCompatibleClient;
import com.example.werewolf.experiment.ExperimentRecord.GameSnapshot;
import com.example.werewolf.experiment.ExperimentRecord.ModelSpec;
import com.example.werewolf.game.AdvanceCommand;
import com.example.werewolf.game.GameConfig;
import com.example.werewolf.game.GameEngine;
import com.example.werewolf.game.GameEvent;
import com.example.werewolf.game.GamePhase;
import com.example.werewolf.game.GameResult;
import com.example.werewolf.game.GameState;
import com.example.werewolf.game.ObserverNote;
import com.example.werewolf.player.PlayerStatus;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.Set;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import com.example.werewolf.knowledge.KnowledgeBase;
import com.example.werewolf.knowledge.KnowledgeBase.*;
import com.example.werewolf.knowledge.KnowledgeService;

/** 内存对局服务：保存可继续推进的会话，不把 API Key 写入事件或返回值。 */
@Service
public class GameService {
    private final Map<String, TrackedGame> games = new LinkedHashMap<>();
    private final ObjectMapper mapper;
    private final LlmClient llmClient;
    private final KnowledgeService knowledge;

    public GameService(ObjectMapper mapper) {
        this(mapper, null, new OpenAiCompatibleClient(mapper));
    }

    @Autowired
    public GameService(ObjectMapper mapper, KnowledgeService knowledge) {
        this(mapper, knowledge, new OpenAiCompatibleClient(mapper));
    }

    /** 可替换模型客户端供隔离与存档测试使用，不调用真实模型 API。 */
    public GameService(ObjectMapper mapper, KnowledgeService knowledge, LlmClient client) {
        this.mapper = mapper;
        this.knowledge = knowledge;
        this.llmClient = client;
    }

    public GameView create(CreateGameRequest request) {
        if (request == null) throw new IllegalArgumentException("缺少创建对局参数");
        if (request.seed() != null && (request.seed() < -9_007_199_254_740_991L || request.seed() > 9_007_199_254_740_991L))
            throw new IllegalArgumentException("种子须在浏览器可精确表示的整数范围内");
        GameConfig config = request.config() == null ? GameConfig.classicSeven() : request.config();
        List<String> types = request.agentTypes() == null || request.agentTypes().isEmpty()
                ? Collections.nCopies(config.playerCount(), "RANDOM") : request.agentTypes();
        if (types.size() != config.playerCount())
            throw new IllegalArgumentException("Agent 数量必须与玩家总数相同");
        if (types.stream().anyMatch(t -> t == null || !Set.of("RANDOM", "RULE", "LLM").contains(t)))
            throw new IllegalArgumentException("Agent 类型只能是 RANDOM、RULE 或 LLM");
        if (types.contains("LLM") && request.llm() == null)
            throw new IllegalArgumentException("LLM 对局需要模型配置");
        // 留空也生成并记录实际种子，后续才能用同一配置重跑。
        long seed = request.seed() == null ? new Random().nextLong(9_007_199_254_740_991L) : request.seed();
        Random random = new Random(seed);
        String startedAt = Instant.now().toString();
        List<Agent> agents = new ArrayList<>();
        for (String type : types) {
            agents.add(switch (type) {
                case "RANDOM" -> new RandomAgent(random);
                case "RULE" -> new RuleAgent();
                default -> new LlmAgent(llmClient, request.llm(), mapper);
            });
        }
        // Web 对局的完整私密日志只保存在内存，不写入服务端控制台；Console 演示仍可单独输出。
        GameEngine.GameSession session = new GameEngine(random, ignored -> { }).newSession(config, agents);
        Mode mode = request.knowledgeMode() == null ? Mode.NONE : request.knowledgeMode();
        if (types.contains("LLM") && mode != Mode.NONE && knowledge == null)
            throw new IllegalArgumentException("知识库尚未配置");
        Snapshot knowledgeSnapshot = !types.contains("LLM") || mode == Mode.NONE ? null : KnowledgeService.snapshot(
                knowledge.get().entries().stream().filter(Entry::enabled).toList());
        List<Usage> usages = new ArrayList<>();
        for (var player : session.getState().getPlayers()) {
            if (player.getAgent() instanceof LlmAgent llm) {
                llm.bindKnowledge(player.getRole(), KnowledgeBase.forRole(knowledgeSnapshot, mode, player.getRole()),
                        (context, selected) -> usages.add(new Usage(session.getState().getActionRecords().size() + 1,
                                context.dayNumber(), context.phase(), session.getState().getEvents().size(),
                                context.playerId(), selected.stream().map(Entry::id).toList())));
            }
        }
        boolean manual = Boolean.TRUE.equals(request.manual()) || types.contains("LLM");
        if (!manual) {
            while (session.getState().getResult() == GameResult.ONGOING)
                session.advance(session.getAvailableCommand());
        }
        synchronized (games) {
            games.put(session.getState().getGameId(), new TrackedGame(session, seed, List.copyOf(types), startedAt,
                    types.contains("LLM") ? ModelSpec.of(request.llm()) : null,
                    types.contains("LLM") ? mode : null, knowledgeSnapshot, usages));
            if (games.size() > 30) games.remove(games.keySet().iterator().next());
        }
        return view(session, manual);
    }

    public GameView advance(String id, AdvanceCommand command) {
        GameEngine.GameSession session = find(id);
        synchronized (session) {
            session.advance(command);
            return view(session, true);
        }
    }

    public List<GameSummary> list() {
        synchronized (games) {
            return games.values().stream().map(run -> {
                GameEngine.GameSession s = run.session();
                synchronized (s) { return summary(s.getState()); }
            }).toList().reversed();
        }
    }

    public GameView get(String id) {
        GameEngine.GameSession session = find(id);
        synchronized (session) { return view(session, session.getState().getResult() == GameResult.ONGOING); }
    }

    /** 本地观战数据与普通对局视图分开；Agent 决策只从引擎创建的 AgentContext 取值。 */
    public ObserverView observe(String id) {
        TrackedGame run = tracked(id);
        GameEngine.GameSession session = run.session();
        synchronized (session) {
            GameState state = session.getState();
            List<PlayerView> players = state.getPlayers().stream().map(p ->
                    new PlayerView(p.getId(), p.getName(), p.getRole(), p.getStatus())).toList();
            return new ObserverView(players, state.getObserverNotes(), knowledgeRun(run), state.getActionRecords());
        }
    }

    private GameEngine.GameSession find(String id) {
        return tracked(id).session();
    }

    private TrackedGame tracked(String id) {
        synchronized (games) {
            TrackedGame run = games.get(id);
            if (run == null) throw new NoSuchElementException("对局不存在或服务已重启");
            return run;
        }
    }

    /** 实验快照供观战用户存档，不放入普通对局响应或 AgentContext。 */
    public RecordData snapshot(String id) {
        TrackedGame run = tracked(id);
        synchronized (run.session()) {
            return new RecordData(run.session().getState().getConfig(), run.agentTypes(),
                    GameSnapshot.capture(run.session().getState(), run.seed(), run.agentTypes(), run.startedAt(), run.model(), false)
                            .withKnowledge(knowledgeRun(run)));
        }
    }
    private record TrackedGame(GameEngine.GameSession session, long seed, List<String> agentTypes,
                                String startedAt, ModelSpec model, Mode knowledgeMode, Snapshot knowledgeSnapshot,
                                List<Usage> usages) { }
    private Run knowledgeRun(TrackedGame game) {
        return game.knowledgeMode() == null ? null : new Run(game.knowledgeMode(), game.knowledgeSnapshot(), game.usages());
    }
    public record RecordData(GameConfig config, List<String> agentTypes, GameSnapshot game) { }

    private GameSummary summary(GameState state) {
        return new GameSummary(state.getGameId(), state.getResult(), state.getDayNumber(),
                state.getEvents().size(), state.getConfig().playerCount(), state.getPhase());
    }

    private GameView view(GameEngine.GameSession session, boolean manual) {
        GameState state = session.getState();
        boolean complete = state.getResult() != GameResult.ONGOING;
        List<PlayerView> players = state.getPlayers().stream().map(p ->
                new PlayerView(p.getId(), p.getName(), complete ? p.getRole() : null, p.getStatus())).toList();
        List<GameEvent> events = complete ? state.getEvents() : state.getEvents().stream().map(this::publicEvent).toList();
        // 夜间行动者的 ID 本身会暴露身份；只在白天公开下一位玩家。
        boolean publicActor = state.getPhase() == GamePhase.DAY_DISCUSSION || state.getPhase() == GamePhase.DAY_VOTE;
        return new GameView(summary(state), state.getConfig(), players, events,
                manual, session.getAvailableCommand(), publicActor ? session.getNextActorId() : null);
    }

    private GameEvent publicEvent(GameEvent event) {
        // 未结束的对局不能通过接口泄露真实身份、夜间目标或查验结果。
        String text = switch (event.type()) {
            case "ROLE_ASSIGNED" -> event.actorId() + " 已收到身份牌";
            case "WOLF_VOTE" -> "一名狼人已提交夜间行动";
            case "WOLF_TARGET" -> "狼人目标已确定";
            case "SEER_CHECK" -> "预言家已完成查验";
            case "WITCH_ACTION" -> "女巫已完成夜间行动";
            default -> event.text();
        };
        boolean privateAction = Set.of("WOLF_VOTE", "WOLF_TARGET", "SEER_CHECK", "WITCH_ACTION").contains(event.type());
        return new GameEvent(event.day(), event.phase(), event.type(),
                privateAction ? null : event.actorId(), privateAction ? null : event.targetId(), text);
    }

    public record CreateGameRequest(GameConfig config, List<String> agentTypes, Long seed,
                                    LlmConfig llm, Boolean manual, Mode knowledgeMode,
                                    String experimentName, String experimentGroup, String experimentNotes) {
        public CreateGameRequest(GameConfig config, List<String> agentTypes, Long seed, LlmConfig llm, Boolean manual) {
            this(config, agentTypes, seed, llm, manual, Mode.NONE, null, null, null);
        }
        public CreateGameRequest(GameConfig config, List<String> agentTypes, Long seed, LlmConfig llm, Boolean manual, Mode knowledgeMode) {
            this(config, agentTypes, seed, llm, manual, knowledgeMode, null, null, null);
        }
    }
    public record GameSummary(String gameId, GameResult result, int days, int eventCount,
                              int playerCount, GamePhase phase) { }
    public record PlayerView(String id, String name, Role role, PlayerStatus status) { }
    public record GameView(GameSummary summary, GameConfig config, List<PlayerView> players,
                           List<GameEvent> events, boolean manual,
                           AdvanceCommand nextCommand, String nextActorId) { }
    public record ObserverView(List<PlayerView> players, List<ObserverNote> notes, Run knowledge, List<com.example.werewolf.game.ActionRecord> actions) { }
}
