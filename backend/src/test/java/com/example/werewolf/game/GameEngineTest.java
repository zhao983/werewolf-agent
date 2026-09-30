package com.example.werewolf.game;

import com.example.werewolf.agent.*;
import com.example.werewolf.player.Player;
import com.example.werewolf.player.Role;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 经典局回归测试：验证原 Milestone 1 的规则不会被可配置模式破坏。 */
class GameEngineTest {
    private final Agent passive = c -> AgentResponse.action(ActionType.PASS, null);

    @Test
    void roleAllocationAndAutomaticGame() {
        for (int seed = 0; seed < 50; seed++) {
            GameState state = new GameEngine(new Random(seed), s -> { }).start();
            assertEquals(7, state.getPlayers().size());
            assertEquals(2, count(state, Role.WEREWOLF));
            assertEquals(3, count(state, Role.VILLAGER));
            assertEquals(1, count(state, Role.SEER));
            assertEquals(1, count(state, Role.WITCH));
            assertNotEquals(GameResult.ONGOING, state.getResult());
            assertEquals(GamePhase.GAME_OVER, state.getPhase());
            assertTrue(state.getEvents().stream().anyMatch(e -> e.type().equals("PLAYER_SPEAK")));
            assertTrue(state.getEvents().stream().anyMatch(e -> e.type().equals("VOTE")));
            assertEquals(state.getResult(), GameEngine.winner(state.getPlayers()));
        }
    }

    @Test
    void bothWinConditions() {
        List<Player> players = players(Role.WEREWOLF, Role.WEREWOLF, Role.VILLAGER, Role.VILLAGER,
                Role.VILLAGER, Role.SEER, Role.WITCH);
        players.get(0).die();
        players.get(1).die();
        assertEquals(GameResult.VILLAGER_WIN, GameEngine.winner(players));

        players = players(Role.WEREWOLF, Role.WEREWOLF, Role.VILLAGER, Role.VILLAGER,
                Role.VILLAGER, Role.SEER, Role.WITCH);
        players.get(2).die(); players.get(3).die(); players.get(4).die();
        assertEquals(GameResult.WEREWOLF_WIN, GameEngine.winner(players));
    }

    @Test
    void illegalWolfKillAndSeerCheckAreRejected() {
        AgentContext wolf = context(Role.WEREWOLF, GamePhase.NIGHT_WEREWOLF,
                List.of(ActionType.KILL, ActionType.PASS), Map.of(ActionType.KILL, List.of("player3")));
        assertThrows(IllegalArgumentException.class,
                () -> ActionValidator.validate(wolf, AgentResponse.action(ActionType.KILL, "player2")));
        AgentContext seer = context(Role.SEER, GamePhase.NIGHT_SEER,
                List.of(ActionType.CHECK), Map.of(ActionType.CHECK, List.of("player2")));
        assertThrows(IllegalArgumentException.class,
                () -> ActionValidator.validate(seer, AgentResponse.action(ActionType.CHECK, "player3")));
    }

    @Test
    void witchCannotReusePotionOrPoisonDeadPlayer() {
        AgentContext witch = context(Role.WITCH, GamePhase.NIGHT_WITCH,
                List.of(ActionType.PASS, ActionType.POISON), Map.of(ActionType.POISON, List.of("player2")));
        assertThrows(IllegalArgumentException.class,
                () -> ActionValidator.validate(witch, AgentResponse.action(ActionType.SAVE, "player2")));
        assertThrows(IllegalArgumentException.class,
                () -> ActionValidator.validate(witch, AgentResponse.action(ActionType.POISON, "player3")));
        GameState state = new GameState("test", players(Role.WEREWOLF, Role.WEREWOLF, Role.VILLAGER,
                Role.VILLAGER, Role.VILLAGER, Role.SEER, Role.WITCH), s -> { });
        state.useAntidote("player6");
        assertFalse(state.isAntidoteAvailable("player6"));
        assertThrows(IllegalStateException.class, () -> state.useAntidote("player6"));
        state.usePoison("player6");
        assertFalse(state.isPoisonAvailable("player6"));
        assertThrows(IllegalStateException.class, () -> state.usePoison("player6"));
    }

    @Test
    void deadPlayerCannotActAfterResolutionAndSecretsStayPrivate() {
        List<AgentContext> contexts = new ArrayList<>();
        Agent observer = c -> {
            contexts.add(c);
            if (c.phase() == GamePhase.DAY_DISCUSSION) return AgentResponse.speech("hello");
            ActionType action = c.availableActions().get(0);
            List<String> targets = c.legalTargets().getOrDefault(action, List.of());
            return AgentResponse.action(action, targets.isEmpty() ? null : targets.get(0));
        };
        GameState state = new GameEngine(new Random(2), s -> { }).start(Collections.nCopies(7, observer));
        List<GameEvent> events = state.getEvents();
        for (int i = 0; i < events.size(); i++) {
            GameEvent death = events.get(i);
            if (!death.type().equals("PLAYER_EXILED") && !death.type().equals("PLAYER_DIED")) continue;
            assertTrue(events.subList(i + 1, events.size()).stream()
                    .noneMatch(e -> death.targetId().equals(e.actorId())));
        }
        for (AgentContext context : contexts) {
            if (context.role() != Role.WEREWOLF)
                assertTrue(context.privateInformation().stream().noneMatch(s -> s.contains("Wolf teammate")));
            if (context.role() != Role.SEER)
                assertTrue(context.privateInformation().stream().noneMatch(s -> s.contains(" is WEREWOLF") || s.contains(" is GOOD")));
            if (context.role() != Role.WITCH) assertNull(context.attackedPlayerId());
        }
    }

    @Test
    void observerDecisionNoteNeverEntersAnotherAgentContext() {
        List<AgentContext> contexts = new ArrayList<>();
        Agent agent = c -> {
            contexts.add(c);
            if (c.availableActions().contains(ActionType.SPEAK))
                return AgentResponse.explained("仅观众可见的决策说明", "公开发言", ActionType.SPEAK, null);
            if (c.availableActions().contains(ActionType.VOTE))
                return AgentResponse.action(ActionType.VOTE, c.legalTargets().get(ActionType.VOTE).get(0));
            return AgentResponse.action(ActionType.PASS, null);
        };
        GameEngine.GameSession session = new GameEngine(new Random(4), s -> { })
                .newSession(new GameConfig(5, 1, 4, 0, 0), Collections.nCopies(5, agent));
        while (session.getState().getPhase() != GamePhase.DAY_VOTE)
            session.advance(session.getAvailableCommand());
        assertTrue(session.getState().getObserverNotes().stream()
                .anyMatch(n -> n.kind().equals("DECISION") && n.text().contains("仅观众可见的决策说明")));
        assertTrue(session.getState().getObserverNotes().stream()
                .allMatch(n -> n.eventIndex() >= 0 && n.eventIndex() < session.getState().getEvents().size()));
        assertTrue(contexts.stream().allMatch(c -> c.privateInformation().stream()
                .noneMatch(s -> s.contains("仅观众可见的决策说明"))));
        assertTrue(contexts.stream().allMatch(c -> c.visibleMessages().stream()
                .noneMatch(m -> m.content().contains("仅观众可见的决策说明"))));
    }

    private long count(GameState state, Role role) {
        return state.getPlayers().stream().filter(p -> p.getRole() == role).count();
    }
    private List<Player> players(Role... roles) {
        List<Player> result = new ArrayList<>();
        for (int i = 0; i < roles.length; i++) result.add(new Player("player" + i, "Player" + i, roles[i], passive));
        return result;
    }
    private AgentContext context(Role role, GamePhase phase, List<ActionType> actions, Map<ActionType, List<String>> targets) {
        return new AgentContext("player1", role, phase, 1, List.of("player1", "player2"),
                List.of(), List.of(), actions, targets, null);
    }
}
