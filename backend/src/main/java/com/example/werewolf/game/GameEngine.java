package com.example.werewolf.game;

import com.example.werewolf.agent.ActionType;
import com.example.werewolf.agent.Agent;
import com.example.werewolf.agent.AgentContext;
import com.example.werewolf.agent.AgentResponse;
import com.example.werewolf.agent.RandomAgent;
import com.example.werewolf.message.GameMessage;
import com.example.werewolf.player.Player;
import com.example.werewolf.player.Role;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** 纯 Java 游戏引擎：负责规则、状态和 Agent 调度，不依赖 Spring、前端或模型接口。 */
public final class GameEngine {
    private final Random random;
    private final Consumer<String> logger;

    public GameEngine(Random random) { this(random, System.out::println); }
    public GameEngine(Random random, Consumer<String> logger) {
        this.random = Objects.requireNonNull(random);
        this.logger = Objects.requireNonNull(logger);
    }

    /** 兼容 Milestone 1：经典 7 人、7 个 RandomAgent，一次运行至结束。 */
    public GameState start() {
        List<Agent> agents = new ArrayList<>();
        for (int i = 0; i < GameConfig.classicSeven().playerCount(); i++) agents.add(new RandomAgent(random));
        return start(agents);
    }

    public GameState start(List<Agent> agents) { return start(GameConfig.classicSeven(), agents); }

    public GameState start(GameConfig config, List<Agent> agents) {
        GameSession session = newSession(config, agents);
        // 自动模式和手动模式共用同一条状态机路径，避免两套规则逐渐产生差异。
        while (session.state.getResult() == GameResult.ONGOING) session.advance(session.getAvailableCommand());
        return session.state;
    }

    /** 创建可逐步推进的对局；此方法只洗牌并进入第一个夜间阶段，不调用 Agent。 */
    public GameSession newSession(GameConfig config, List<Agent> agents) {
        Objects.requireNonNull(config);
        if (agents == null || agents.size() != config.playerCount() || agents.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Agent 数量必须与玩家总数相同");
        List<Role> roles = config.roles();
        Collections.shuffle(roles, random);
        List<Player> players = new ArrayList<>();
        for (int i = 0; i < roles.size(); i++)
            players.add(new Player("player" + (i + 1), "Player" + (i + 1), roles.get(i), agents.get(i)));
        GameState state = new GameState(UUID.randomUUID().toString(), config, players, logger);
        state.event("GAME_START", null, null, "======== GAME START ========");
        for (Player player : players)
            state.event("ROLE_ASSIGNED", player.getId(), null, player.getName() + " -> " + player.getRole());
        // 狼队友信息仅写入对应狼人的私有信息，不能进入公共发言。
        for (Player wolf : players.stream().filter(p -> p.getRole() == Role.WEREWOLF).toList()) {
            List<String> teammates = players.stream().filter(p -> p.getRole() == Role.WEREWOLF && p != wolf)
                    .map(Player::getId).toList();
            state.privateInfo(wolf.getId(), "Wolf teammate: " + String.join(", ", teammates));
        }
        GameSession session = new GameSession(state);
        session.enterPhase(GamePhase.NIGHT_WEREWOLF);
        return session;
    }

    /** 存活狼人归零时好人胜；否则狼人达到人数优势时狼人胜。 */
    public static GameResult winner(List<Player> players) {
        long wolves = players.stream().filter(p -> p.isAlive() && p.getRole() == Role.WEREWOLF).count();
        long good = players.stream().filter(p -> p.isAlive() && p.getRole() != Role.WEREWOLF).count();
        if (wolves == 0) return GameResult.VILLAGER_WIN;
        if (wolves >= good) return GameResult.WEREWOLF_WIN;
        return GameResult.ONGOING;
    }

    private String choosePlurality(List<String> votes) {
        if (votes.isEmpty()) return null;
        Map<String, Long> counts = votes.stream().collect(Collectors.groupingBy(v -> v, Collectors.counting()));
        long max = counts.values().stream().mapToLong(Long::longValue).max().orElseThrow();
        List<String> tied = counts.entrySet().stream().filter(e -> e.getValue() == max)
                .map(Map.Entry::getKey).sorted().toList();
        // 排序后再随机，固定种子下即使 Map 遍历顺序变化也能复现平票结果。
        return tied.get(random.nextInt(tied.size()));
    }

    private List<Player> alive(GameState state) { return state.getPlayers().stream().filter(Player::isAlive).toList(); }
    private List<String> otherAliveIds(GameState state, Player actor) {
        return alive(state).stream().filter(p -> p != actor).map(Player::getId).toList();
    }
    private Player player(GameState state, String id) {
        return state.getPlayers().stream().filter(p -> p.getId().equals(id)).findFirst().orElseThrow();
    }

    /** 每个请求只推进一个玩家行动或一个明确的阶段边界。 */
    public final class GameSession {
        private final GameState state;
        private List<Player> actors = List.of();
        private int actorIndex;
        private final List<String> wolfVotes = new ArrayList<>();
        private final List<String> dayVotes = new ArrayList<>();
        private final Set<String> savedTargets = new LinkedHashSet<>();
        private final Set<String> poisonedTargets = new LinkedHashSet<>();
        private String wolfTarget;
        private boolean dayResolved;

        private GameSession(GameState state) { this.state = state; }
        public GameState getState() { return state; }

        public synchronized String getNextActorId() {
            return actorIndex < actors.size() ? actors.get(actorIndex).getId() : null;
        }

        public synchronized AdvanceCommand getAvailableCommand() {
            if (state.getResult() != GameResult.ONGOING) return AdvanceCommand.NONE;
            if (state.getPhase() == GamePhase.DAY_RESOLVE && dayResolved) return AdvanceCommand.END_DAY;
            return actorIndex < actors.size() ? AdvanceCommand.NEXT_ACTION : AdvanceCommand.COMPLETE_PHASE;
        }

        public synchronized void advance(AdvanceCommand command) {
            if (command == null || command != getAvailableCommand())
                throw new IllegalArgumentException("当前阶段不允许该推进指令；应使用 " + getAvailableCommand());
            switch (command) {
                case NEXT_ACTION -> actNext();
                case COMPLETE_PHASE -> completePhase();
                case END_DAY -> endDay();
                default -> throw new IllegalArgumentException("对局已结束");
            }
        }

        private void enterPhase(GamePhase phase) {
            state.phase(phase);
            actorIndex = 0;
            actors = switch (phase) {
                case NIGHT_WEREWOLF -> alive(state).stream().filter(p -> p.getRole() == Role.WEREWOLF).toList();
                case NIGHT_SEER -> alive(state).stream().filter(p -> p.getRole() == Role.SEER).toList();
                case NIGHT_WITCH -> alive(state).stream().filter(p -> p.getRole() == Role.WITCH).toList();
                case DAY_DISCUSSION, DAY_VOTE -> alive(state);
                default -> List.of();
            };
            state.event("PHASE_CHANGED", null, null, "进入 " + phase + "，第 " + state.getDayNumber() + " 天");
            // 没有该身份时直接跳过空阶段，按钮始终对应一个真实可执行的步骤。
            if (actors.isEmpty() && phase == GamePhase.NIGHT_SEER) enterPhase(GamePhase.NIGHT_WITCH);
            else if (actors.isEmpty() && phase == GamePhase.NIGHT_WITCH) enterPhase(GamePhase.NIGHT_RESOLVE);
        }

        private void actNext() {
            Player actor = actors.get(actorIndex);
            if (!actor.isAlive()) throw new IllegalStateException("死亡玩家不能行动");
            switch (state.getPhase()) {
                case NIGHT_WEREWOLF -> actWolf(actor);
                case NIGHT_SEER -> actSeer(actor);
                case NIGHT_WITCH -> actWitch(actor);
                case DAY_DISCUSSION -> actSpeaker(actor);
                case DAY_VOTE -> actVoter(actor);
                default -> throw new IllegalStateException("当前阶段没有玩家行动");
            }
            actorIndex++;
        }

        private void actWolf(Player actor) {
            List<String> targets = alive(state).stream().filter(p -> p.getRole() != Role.WEREWOLF)
                    .map(Player::getId).toList();
            AgentResponse response = act(actor, List.of(ActionType.KILL, ActionType.PASS),
                    Map.of(ActionType.KILL, targets), null);
            if (response.action() == ActionType.KILL) wolfVotes.add(response.targetPlayerId());
            state.event("WOLF_VOTE", actor.getId(), response.targetPlayerId(),
                    actor.getName() + " -> " + response.action() + " " + response.targetPlayerId());
        }

        private void actSeer(Player actor) {
            AgentResponse response = act(actor, List.of(ActionType.CHECK),
                    Map.of(ActionType.CHECK, otherAliveIds(state, actor)), null);
            Player target = player(state, response.targetPlayerId());
            String result = target.getRole() == Role.WEREWOLF ? "WEREWOLF" : "GOOD";
            state.privateInfo(actor.getId(), "Night " + state.getDayNumber() + ": " + target.getId() + " is " + result);
            state.event("SEER_CHECK", actor.getId(), target.getId(),
                    actor.getName() + " checks " + target.getName() + ": " + result);
        }

        private void actWitch(Player actor) {
            List<ActionType> actions = new ArrayList<>(List.of(ActionType.PASS));
            Map<ActionType, List<String>> targets = new EnumMap<>(ActionType.class);
            if (state.isAntidoteAvailable(actor.getId()) && wolfTarget != null
                    && (GameRule.ALLOW_WITCH_SELF_SAVE || !wolfTarget.equals(actor.getId()))) {
                actions.add(ActionType.SAVE);
                targets.put(ActionType.SAVE, List.of(wolfTarget));
            }
            if (state.isPoisonAvailable(actor.getId())) {
                List<String> poisonTargets = otherAliveIds(state, actor);
                if (!poisonTargets.isEmpty()) {
                    actions.add(ActionType.POISON);
                    targets.put(ActionType.POISON, poisonTargets);
                }
            }
            AgentResponse response = act(actor, actions, targets, wolfTarget);
            if (response.action() == ActionType.SAVE) {
                state.useAntidote(actor.getId());
                savedTargets.add(response.targetPlayerId());
            } else if (response.action() == ActionType.POISON) {
                state.usePoison(actor.getId());
                poisonedTargets.add(response.targetPlayerId());
            }
            state.event("WITCH_ACTION", actor.getId(), response.targetPlayerId(),
                    actor.getName() + " chooses " + response.action() +
                            (response.targetPlayerId() == null ? "" : " " + response.targetPlayerId()));
        }

        private void actSpeaker(Player actor) {
            AgentResponse response = act(actor, List.of(ActionType.SPEAK), Map.of(), null);
            String speech = response.speech().strip();
            state.publicMessage(new GameMessage(state.getDayNumber(), actor.getId(), speech));
            state.event("PLAYER_SPEAK", actor.getId(), null, actor.getName() + ": " + speech);
        }

        private void actVoter(Player actor) {
            AgentResponse response = act(actor, List.of(ActionType.VOTE),
                    Map.of(ActionType.VOTE, otherAliveIds(state, actor)), null);
            dayVotes.add(response.targetPlayerId());
            state.event("VOTE", actor.getId(), response.targetPlayerId(),
                    actor.getName() + " -> " + response.targetPlayerId());
        }

        private AgentResponse act(Player actor, List<ActionType> actions,
                                  Map<ActionType, List<String>> targets, String attack) {
            // 构造不可变信息视图；绝不把完整 GameState 交给 Agent 或模型。
            AgentContext context = new AgentContext(actor.getId(), actor.getRole(), state.getPhase(),
                    state.getDayNumber(), alive(state).stream().map(Player::getId).toList(),
                    state.getPublicMessages(), state.getPrivateInformation(actor.getId()), actions, targets,
                    attack, state.getConfig());
            AgentResponse response = actor.getAgent().act(context);
            ActionValidator.validate(context, response);
            // 决策说明与私密行动仅进入观战记录，不能进入公共发言或其他 Agent 的输入。
            String choice = response.action() + (response.targetPlayerId() == null ? "" : " → " + response.targetPlayerId());
            String reason = response.reasoning() == null ? "" : response.reasoning().strip();
            if (reason.length() > 300) reason = reason.substring(0, 300) + "…";
            state.observerNote(actor.getId(), "DECISION", choice + (reason.isEmpty() ? "" : "｜" + reason));
            return response;
        }

        private void completePhase() {
            switch (state.getPhase()) {
                case NIGHT_WEREWOLF -> {
                    wolfTarget = choosePlurality(wolfVotes);
                    state.event("WOLF_TARGET", null, wolfTarget,
                            "Werewolves choose: " + (wolfTarget == null ? "nobody" : wolfTarget));
                    enterPhase(GamePhase.NIGHT_SEER);
                }
                case NIGHT_SEER -> enterPhase(GamePhase.NIGHT_WITCH);
                case NIGHT_WITCH -> enterPhase(GamePhase.NIGHT_RESOLVE);
                case NIGHT_RESOLVE -> resolveNight();
                case DAY_ANNOUNCEMENT -> enterPhase(GamePhase.DAY_DISCUSSION);
                case DAY_DISCUSSION -> enterPhase(GamePhase.DAY_VOTE);
                case DAY_VOTE -> enterPhase(GamePhase.DAY_RESOLVE);
                case DAY_RESOLVE -> resolveVote();
                default -> throw new IllegalStateException("无法完成当前阶段");
            }
        }

        private void resolveNight() {
            // 夜间死亡统一结算：解药抵消狼刀，毒药死亡集合再合并去重。
            Set<String> deaths = new LinkedHashSet<>();
            if (wolfTarget != null && !savedTargets.contains(wolfTarget)) deaths.add(wolfTarget);
            deaths.addAll(poisonedTargets);
            for (String id : deaths) player(state, id).die();
            state.lastNightDeaths(new ArrayList<>(deaths));
            state.event("NIGHT_RESULT", null, null,
                    "Night deaths: " + (deaths.isEmpty() ? "none" : String.join(", ", deaths)));
            for (String id : deaths) state.event("PLAYER_DIED", null, id, id + " died last night");
            if (!checkWinner()) {
                enterPhase(GamePhase.DAY_ANNOUNCEMENT);
                state.event("DAY_ANNOUNCEMENT", null, null, "Last night: " +
                        (deaths.isEmpty() ? "no deaths" : String.join(", ", deaths)));
            }
        }

        private void resolveVote() {
            String exiled = choosePlurality(dayVotes);
            if (exiled != null) {
                player(state, exiled).die();
                state.event("PLAYER_EXILED", null, exiled, exiled + " is exiled");
            }
            dayResolved = true;
            checkWinner();
        }

        private void endDay() {
            state.nextDay();
            wolfVotes.clear();
            dayVotes.clear();
            savedTargets.clear();
            poisonedTargets.clear();
            wolfTarget = null;
            dayResolved = false;
            enterPhase(GamePhase.NIGHT_WEREWOLF);
        }

        private boolean checkWinner() {
            GameResult result = winner(state.getPlayers());
            state.result(result);
            state.event("WIN_CHECK", null, null,
                    result == GameResult.ONGOING ? "Game continues" : "Winner: " + result);
            if (result != GameResult.ONGOING) {
                state.phase(GamePhase.GAME_OVER);
                state.event("GAME_OVER", null, null, "======== GAME OVER: " + result + " ========");
                return true;
            }
            return false;
        }
    }
}
