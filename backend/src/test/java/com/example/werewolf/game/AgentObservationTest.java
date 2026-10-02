package com.example.werewolf.game;

import com.example.werewolf.agent.*;
import com.example.werewolf.player.Role;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 复现“查验后遗忘、救人后误称未用药、死亡后仍喊话”，同时检查跨角色私有信息隔离。 */
class AgentObservationTest {
    @Test void exposesPublicFactsAndOnlyOwnCompletedActions() {
        List<AgentContext> observations = new ArrayList<>();
        Agent agent = c -> {
            observations.add(c);
            if (c.availableActions().contains(ActionType.KILL)) return AgentResponse.action(ActionType.KILL, "player3");
            if (c.availableActions().contains(ActionType.CHECK)) return AgentResponse.action(ActionType.CHECK, "player1");
            if (c.availableActions().contains(ActionType.SAVE) && c.dayNumber() == 1) return AgentResponse.action(ActionType.SAVE, "player3");
            if (c.availableActions().contains(ActionType.PASS)) return AgentResponse.action(ActionType.PASS, null);
            if (c.availableActions().contains(ActionType.SPEAK)) return AgentResponse.speech("按公开事实发言");
            return AgentResponse.action(ActionType.VOTE, c.playerId().equals("player5") ? "player4" : "player5");
        };
        // 洗牌交换自身，固定座位：1/2 狼，3/4/5 村民，6 预言家，7 女巫。
        Random noShuffle = new Random(1) { @Override public int nextInt(int bound) { return bound - 1; } };
        var session = new GameEngine(noShuffle, s -> { }).newSession(GameConfig.classicSeven(), Collections.nCopies(7, agent));
        int steps = 0;
        while (!(session.getState().getDayNumber() == 2 && session.getState().getPhase() == GamePhase.DAY_VOTE)) {
            assertTrue(steps++ < 70);
            session.advance(session.getAvailableCommand());
        }
        AgentContext seer = observation(observations, "player6", 1);
        AgentContext wolf = observation(observations, "player1", 1);
        assertTrue(wolf.privateInformation().stream().anyMatch(s -> s.equals("Wolf team night 1 final target: player3; individual KILL is only a proposal")));
        assertEquals(List.of(new PersonalAction(1, GamePhase.NIGHT_SEER, ActionType.CHECK, "player1")), seer.ownActionHistory());
        assertTrue(seer.privateInformation().contains("Night 1: player1 is WEREWOLF"));
        AgentContext witch = observation(observations, "player7", 1);
        assertFalse(witch.antidoteAvailable()); assertTrue(witch.poisonAvailable());
        assertEquals(ActionType.SAVE, witch.ownActionHistory().getFirst().action());
        AgentContext villager = observation(observations, "player4", 2);
        assertEquals(Set.of("player3", "player5"), new HashSet<>(villager.deadPlayerIds()));
        assertTrue(villager.publicFacts().stream().anyMatch(e -> e.type().equals("PLAYER_EXILED") && "player5".equals(e.targetId())));
        assertTrue(villager.publicFacts().stream().anyMatch(e -> e.type().equals("PLAYER_DIED") && "player3".equals(e.targetId())));
        assertTrue(villager.publicFacts().stream().anyMatch(e -> e.type().equals("VOTE")));
        assertTrue(villager.privateInformation().isEmpty());
        assertNull(villager.antidoteAvailable()); assertNull(villager.poisonAvailable());
        for (AgentContext c : observations) {
            assertTrue(c.publicFacts().stream().allMatch(e -> Set.of("DAY_ANNOUNCEMENT", "PLAYER_EXILED", "PLAYER_DIED", "VOTE").contains(e.type())));
            if (c.role() != Role.SEER) assertTrue(c.privateInformation().stream().noneMatch(s -> s.startsWith("Night ")));
            if (c.role() != Role.WEREWOLF) assertTrue(c.privateInformation().stream().noneMatch(s -> s.startsWith("Wolf teammate:")));
            if (c.role() != Role.WEREWOLF) assertTrue(c.privateInformation().stream().noneMatch(s -> s.startsWith("Wolf team night ")));
        }
        assertThrows(UnsupportedOperationException.class, () -> villager.publicFacts().clear());
        assertThrows(UnsupportedOperationException.class, () -> witch.ownActionHistory().clear());
    }
    @Test void failedDecisionDoesNotBecomePersonalMemoryOrAdvanceActor() {
        List<AgentContext> observations = new ArrayList<>();
        Agent failOnce = c -> {
            observations.add(c);
            if (observations.size() == 1) throw new IllegalStateException("模拟失败");
            return AgentResponse.action(ActionType.KILL, c.legalTargets().get(ActionType.KILL).getFirst());
        };
        var session = new GameEngine(new Random(2), s -> { }).newSession(GameConfig.classicSeven(), Collections.nCopies(7, failOnce));
        String actor = session.getNextActorId();
        assertThrows(IllegalStateException.class, () -> session.advance(AdvanceCommand.NEXT_ACTION));
        assertEquals(actor, session.getNextActorId());
        session.advance(AdvanceCommand.NEXT_ACTION);
        assertEquals(actor, observations.getLast().playerId());
        assertTrue(observations.getLast().ownActionHistory().isEmpty());
        assertEquals(List.of("ERROR", "VALID"), session.getState().getActionRecords().stream().map(ActionRecord::status).toList());
    }
    private AgentContext observation(List<AgentContext> values, String player, int day) {
        return values.stream().filter(c -> c.playerId().equals(player) && c.dayNumber() == day && c.phase() == GamePhase.DAY_DISCUSSION).findFirst().orElseThrow();
    }
}
