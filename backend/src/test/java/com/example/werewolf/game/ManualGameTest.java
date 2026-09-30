package com.example.werewolf.game;

import com.example.werewolf.agent.ActionType;
import com.example.werewolf.agent.Agent;
import com.example.werewolf.agent.AgentResponse;
import com.example.werewolf.agent.RandomAgent;
import com.example.werewolf.player.Role;
import java.util.Collections;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 可配置对局与手动状态机的行为测试。 */
class ManualGameTest {
    @Test
    void invalidRoleSumAndOpeningParityAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new GameConfig(9, 2, 3, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new GameConfig(6, 3, 3, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> new GameConfig(21, 4, 17, 0, 0));
        assertEquals(9, new GameConfig(9, 2, 4, 1, 2).roles().size());
    }

    @Test
    void eachAdvancePerformsOnlyOneActionAndRequiresPhaseCompletion() {
        AtomicInteger calls = new AtomicInteger();
        Agent agent = context -> {
            calls.incrementAndGet();
            if (context.phase() == GamePhase.DAY_DISCUSSION) return AgentResponse.speech("测试发言");
            if (context.availableActions().contains(ActionType.VOTE))
                return AgentResponse.action(ActionType.VOTE, context.legalTargets().get(ActionType.VOTE).get(0));
            if (context.availableActions().contains(ActionType.CHECK))
                return AgentResponse.action(ActionType.CHECK, context.legalTargets().get(ActionType.CHECK).get(0));
            return AgentResponse.action(ActionType.PASS, null);
        };
        GameConfig config = new GameConfig(9, 2, 4, 1, 2);
        GameEngine.GameSession session = new GameEngine(new Random(12), s -> { })
                .newSession(config, Collections.nCopies(9, agent));
        assertEquals(0, calls.get());
        assertEquals(GamePhase.NIGHT_WEREWOLF, session.getState().getPhase());
        assertEquals(AdvanceCommand.NEXT_ACTION, session.getAvailableCommand());
        assertThrows(IllegalArgumentException.class, () -> session.advance(AdvanceCommand.COMPLETE_PHASE));
        session.advance(AdvanceCommand.NEXT_ACTION);
        assertEquals(1, calls.get());
        session.advance(AdvanceCommand.NEXT_ACTION);
        assertEquals(AdvanceCommand.COMPLETE_PHASE, session.getAvailableCommand());
        session.advance(AdvanceCommand.COMPLETE_PHASE);
        assertEquals(GamePhase.NIGHT_SEER, session.getState().getPhase());
        session.advance(AdvanceCommand.NEXT_ACTION);
        session.advance(AdvanceCommand.COMPLETE_PHASE);
        assertEquals(GamePhase.NIGHT_WITCH, session.getState().getPhase());
        session.advance(AdvanceCommand.NEXT_ACTION);
        session.advance(AdvanceCommand.NEXT_ACTION);
        assertEquals(5, calls.get());
        session.advance(AdvanceCommand.COMPLETE_PHASE);
        assertEquals(GamePhase.NIGHT_RESOLVE, session.getState().getPhase());
        session.advance(AdvanceCommand.COMPLETE_PHASE);
        assertEquals(GamePhase.DAY_ANNOUNCEMENT, session.getState().getPhase());
        session.advance(AdvanceCommand.COMPLETE_PHASE);
        assertEquals(GamePhase.DAY_DISCUSSION, session.getState().getPhase());
        for (int i = 0; i < 9; i++) session.advance(AdvanceCommand.NEXT_ACTION);
        assertEquals(14, calls.get());
        session.advance(AdvanceCommand.COMPLETE_PHASE);
        assertEquals(GamePhase.DAY_VOTE, session.getState().getPhase());
        for (int i = 0; i < 9; i++) session.advance(AdvanceCommand.NEXT_ACTION);
        assertEquals(23, calls.get());
        session.advance(AdvanceCommand.COMPLETE_PHASE);
        session.advance(AdvanceCommand.COMPLETE_PHASE);
        assertEquals(AdvanceCommand.END_DAY, session.getAvailableCommand());
        session.advance(AdvanceCommand.END_DAY);
        assertEquals(2, session.getState().getDayNumber());
        assertEquals(GamePhase.NIGHT_WEREWOLF, session.getState().getPhase());
        assertEquals(2, session.getState().getPlayers().stream().filter(p -> p.getRole() == Role.WITCH).count());
    }

    @Test
    void optionalRolesCanBeZeroAndAutomaticGameStillFinishes() {
        GameConfig config = new GameConfig(5, 1, 4, 0, 0);
        GameState game = new GameEngine(new Random(4), s -> { }).start(config,
                java.util.stream.IntStream.range(0, 5).mapToObj(i ->
                        (Agent) context -> {
                            if (context.availableActions().contains(ActionType.SPEAK)) return AgentResponse.speech("发言");
                            ActionType action = context.availableActions().get(0);
                            return AgentResponse.action(action, context.legalTargets().getOrDefault(action, java.util.List.of())
                                    .stream().findFirst().orElse(null));
                        }).toList());
        assertNotEquals(GameResult.ONGOING, game.getResult());
        assertEquals(5, game.getPlayers().size());
    }

    @Test
    void eachWitchHasIndependentPotions() {
        GameConfig config = new GameConfig(9, 2, 4, 1, 2);
        Agent passive = context -> AgentResponse.action(ActionType.PASS, null);
        GameState state = new GameEngine(new Random(3), s -> { })
                .newSession(config, Collections.nCopies(9, passive)).getState();
        var witches = state.getPlayers().stream().filter(p -> p.getRole() == Role.WITCH).toList();
        state.useAntidote(witches.get(0).getId());
        state.usePoison(witches.get(0).getId());
        assertFalse(state.isAntidoteAvailable(witches.get(0).getId()));
        assertFalse(state.isPoisonAvailable(witches.get(0).getId()));
        assertTrue(state.isAntidoteAvailable(witches.get(1).getId()));
        assertTrue(state.isPoisonAvailable(witches.get(1).getId()));
    }

    @Test
    void customNinePlayerGamesAlsoReachAResult() {
        GameConfig config = new GameConfig(9, 2, 4, 1, 2);
        for (int seed = 0; seed < 10; seed++) {
            Random random = new Random(seed);
            GameState game = new GameEngine(random, s -> { }).start(config,
                    java.util.stream.IntStream.range(0, 9).mapToObj(i -> (Agent) new RandomAgent(random)).toList());
            assertEquals(9, game.getPlayers().size());
            assertNotEquals(GameResult.ONGOING, game.getResult());
            assertEquals(GamePhase.GAME_OVER, game.getPhase());
        }
    }
}
