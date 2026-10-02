package com.example.werewolf.agent;

import com.example.werewolf.game.GamePhase;
import com.example.werewolf.game.GameConfig;
import com.example.werewolf.message.GameMessage;
import com.example.werewolf.player.Role;
import java.util.List;
import java.util.Map;

/** Agent 可见信息的不可变副本；不暴露完整 GameState。 */
public record AgentContext(
        String playerId, Role role, GamePhase phase, int dayNumber,
        List<String> alivePlayerIds, List<GameMessage> visibleMessages,
        List<String> privateInformation, List<ActionType> availableActions,
        Map<ActionType, List<String>> legalTargets, String attackedPlayerId, GameConfig gameConfig,
        List<String> deadPlayerIds, List<com.example.werewolf.game.GameEvent> publicFacts,
        List<PersonalAction> ownActionHistory, Boolean antidoteAvailable, Boolean poisonAvailable) {
    public AgentContext {
        java.util.Objects.requireNonNull(gameConfig);
        // 防止 Agent 通过修改集合反向改变引擎状态或其他 Agent 的可见信息。
        alivePlayerIds = List.copyOf(alivePlayerIds);
        visibleMessages = List.copyOf(visibleMessages);
        privateInformation = List.copyOf(privateInformation);
        availableActions = List.copyOf(availableActions);
        deadPlayerIds = List.copyOf(deadPlayerIds);
        publicFacts = List.copyOf(publicFacts);
        ownActionHistory = List.copyOf(ownActionHistory);
        legalTargets = legalTargets.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())));
    }
    /** 旧调用没有新增观察字段，仍可用于独立 Agent 测试。 */
    public AgentContext(String playerId, Role role, GamePhase phase, int dayNumber,
                        List<String> alivePlayerIds, List<GameMessage> visibleMessages,
                        List<String> privateInformation, List<ActionType> availableActions,
                        Map<ActionType, List<String>> legalTargets, String attackedPlayerId, GameConfig gameConfig) {
        this(playerId, role, phase, dayNumber, alivePlayerIds, visibleMessages, privateInformation,
                availableActions, legalTargets, attackedPlayerId, gameConfig,
                java.util.stream.IntStream.rangeClosed(1, gameConfig.playerCount()).mapToObj(i -> "player" + i)
                        .filter(id -> !alivePlayerIds.contains(id)).toList(), List.of(), List.of(), null, null);
    }
    /** 保留旧测试和经典 7 人调用方式；正式对局应显式传入本局配置。 */
    public AgentContext(String playerId, Role role, GamePhase phase, int dayNumber,
                        List<String> alivePlayerIds, List<GameMessage> visibleMessages,
                        List<String> privateInformation, List<ActionType> availableActions,
                        Map<ActionType, List<String>> legalTargets, String attackedPlayerId) {
        this(playerId, role, phase, dayNumber, alivePlayerIds, visibleMessages,
                privateInformation, availableActions, legalTargets, attackedPlayerId, GameConfig.classicSeven());
    }
}
