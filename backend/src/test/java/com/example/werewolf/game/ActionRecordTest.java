package com.example.werewolf.game;

import com.example.werewolf.agent.*;
import com.example.werewolf.ai.LlmConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collections;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 失败尝试留下实验证据，但不能改变行动顺序或泄露原始模型回复。 */
class ActionRecordTest {
    @Test
    void rejectedActionIsRecordedAndDoesNotConsumeTheTurn() {
        Agent illegal = context -> new AgentResponse("private-reason", null, ActionType.VOTE, context.playerId());
        var session = new GameEngine(new Random(1), ignored -> { }).newSession(GameConfig.classicSeven(), Collections.nCopies(7, illegal));
        String actor = session.getNextActorId();
        assertThrows(IllegalArgumentException.class, () -> session.advance(AdvanceCommand.NEXT_ACTION));
        assertEquals(actor, session.getNextActorId());
        var record = session.getState().getActionRecords().getFirst();
        assertEquals("INVALID", record.status());
        assertEquals(actor, record.playerId());
        assertEquals(0, record.metrics().apiCalls());
    }

    @Test
    void terminalLlmParsingFailureIncludesRetryCountersWithoutRawOutput() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        LlmAgent agent = new LlmAgent((config, prompt) -> "private-raw-output",
                new LlmConfig("http://127.0.0.1:12345/v1", "key-not-for-record", "mock", 0.7, 100), mapper);
        var session = new GameEngine(new Random(1), ignored -> { }).newSession(GameConfig.classicSeven(), Collections.nCopies(7, agent));
        assertThrows(IllegalStateException.class, () -> session.advance(AdvanceCommand.NEXT_ACTION));
        var actions = session.getState().getActionRecords();
        assertEquals(1, actions.size());
        assertEquals("ERROR", actions.getFirst().status());
        assertEquals(2, actions.getFirst().metrics().apiCalls());
        assertEquals(2, actions.getFirst().metrics().invalidReplies());
        assertFalse(mapper.writeValueAsString(actions).contains("private-raw-output"));
        assertFalse(mapper.writeValueAsString(actions).contains("key-not-for-record"));
    }
}
