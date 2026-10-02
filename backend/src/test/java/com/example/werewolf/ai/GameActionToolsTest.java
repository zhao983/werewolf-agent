package com.example.werewolf.ai;

import com.example.werewolf.agent.*;
import com.example.werewolf.game.GamePhase;
import com.example.werewolf.player.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 不依赖真实模型，验证阶段白名单、严格参数与越权拒绝。 */
class GameActionToolsTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private AgentContext context(ActionType action) {
        return new AgentContext("player1", Role.WITCH, GamePhase.NIGHT_WITCH, 2,
                List.of("player1", "player2"), List.of(), List.of(), List.of(action),
                Map.of(action, List.of("player2")), "player2");
    }
    private LlmClient.ToolCall call(String name, String arguments) { return new LlmClient.ToolCall("function", name, arguments); }

    @Test
    void allActionToolsUseOnlyPermittedTargetsAndRequiredFields() throws Exception {
        for (ActionType action : ActionType.values()) {
            var context = context(action);
            var definition = mapper.valueToTree(GameActionTools.definitions(context, true)).get(0).get("function");
            assertEquals(GameActionTools.name(action), definition.path("name").asText());
            assertTrue(definition.path("strict").asBoolean());
            assertFalse(definition.path("parameters").path("additionalProperties").asBoolean());
            if (action != ActionType.PASS && action != ActionType.SPEAK)
                assertEquals("player2", definition.path("parameters").path("properties").path("targetPlayerId").path("enum").get(0).asText());
            var arguments = new LinkedHashMap<String, String>(); arguments.put("reasoning", "简短说明");
            if (action == ActionType.SPEAK) arguments.put("speech", "公开发言");
            else if (action != ActionType.PASS) arguments.put("targetPlayerId", "player2");
            AgentResponse response = GameActionTools.parse(mapper, context, List.of(call(GameActionTools.name(action), mapper.writeValueAsString(arguments))));
            assertEquals(action, response.action());
            assertEquals("简短说明", response.reasoning());
        }
        assertFalse(mapper.valueToTree(GameActionTools.definitions(context(ActionType.VOTE), false)).get(0).get("function").has("strict"));
    }

    @Test
    void rejectsUnknownWrongPhaseAndMultipleToolsAsWholeBatch() {
        var context = context(ActionType.SAVE);
        var save = call("use_antidote", "{\"targetPlayerId\":\"player2\",\"reasoning\":\"\"}");
        assertThrows(IllegalArgumentException.class, () -> GameActionTools.parse(mapper, context, List.of()));
        assertThrows(IllegalArgumentException.class, () -> GameActionTools.parse(mapper, context, List.of(save, save)));
        for (String name : List.of("use_poison", "wolf_attack", "read_all_roles", "exec"))
            assertThrows(IllegalArgumentException.class, () -> GameActionTools.parse(mapper, context, List.of(call(name, "{}"))));
        assertThrows(IllegalArgumentException.class, () -> GameActionTools.parse(mapper, context, List.of(new LlmClient.ToolCall("custom", "use_antidote", save.arguments()))));
    }

    @Test
    void rejectsMalformedAmbiguousAndIllegalArgumentsWithoutEchoingModelText() {
        var context = context(ActionType.SAVE);
        for (String arguments : List.of("{}", "null", "[]", "```json\n{}\n```", "{\"reasoning\":\"\"} {}",
                "{\"reasoning\":\"\",\"targetPlayerId\":2}",
                "{\"reasoning\":\"\",\"targetPlayerId\":\"player2\",\"extra\":true}",
                "{\"reasoning\":\"\",\"reasoning\":\"重复\",\"targetPlayerId\":\"player2\"}",
                "{\"reasoning\":\"\",\"targetPlayerId\":\"private-invalid-target\"}")) {
            var error = assertThrows(IllegalArgumentException.class, () -> GameActionTools.parse(mapper, context, List.of(call("use_antidote", arguments))));
            assertFalse(error.getMessage().contains("private-invalid-target"));
        }
    }
}
