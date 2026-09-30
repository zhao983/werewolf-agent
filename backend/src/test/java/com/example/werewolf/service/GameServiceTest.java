package com.example.werewolf.service;

import com.example.werewolf.ai.LlmConfig;
import com.example.werewolf.game.AdvanceCommand;
import com.example.werewolf.game.GameConfig;
import com.example.werewolf.game.GameResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 检查进行中接口的保密边界和每次推进的粒度。 */
class GameServiceTest {
    @Test
    void llmGameStartsPausedAndResponseDoesNotExposeRolesOrKey() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        GameService service = new GameService(mapper);
        LlmConfig llm = new LlmConfig("http://127.0.0.1:12345/v1", "secret-key", "test-model", 0.7, 100);
        GameService.GameView game = service.create(new GameService.CreateGameRequest(
                new GameConfig(5, 1, 4, 0, 0), List.of("RANDOM", "LLM", "RANDOM", "RANDOM", "RANDOM"),
                12L, llm, false));
        assertEquals(GameResult.ONGOING, game.summary().result());
        assertEquals(AdvanceCommand.NEXT_ACTION, game.nextCommand());
        assertNull(game.nextActorId());
        assertTrue(game.players().stream().allMatch(p -> p.role() == null));
        String json = mapper.writeValueAsString(game);
        assertFalse(json.contains("secret-key"));
        assertFalse(json.contains("\"role\":\"WEREWOLF\""));
        assertFalse(json.contains("\"role\":\"VILLAGER\""));
    }

    @Test
    void nightStepDoesNotRevealSpecialRoleActorOrTarget() {
        GameService service = new GameService(new ObjectMapper());
        GameService.GameView game = service.create(new GameService.CreateGameRequest(
                new GameConfig(5, 1, 4, 0, 0), List.of("RANDOM", "RANDOM", "RANDOM", "RANDOM", "RANDOM"),
                7L, null, true));
        GameService.GameView advanced = service.advance(game.summary().gameId(), AdvanceCommand.NEXT_ACTION);
        assertEquals(game.events().size() + 1, advanced.events().size());
        var event = advanced.events().getLast();
        assertEquals("WOLF_VOTE", event.type());
        assertNull(event.actorId());
        assertNull(event.targetId());
        assertNull(advanced.nextActorId());
    }

    @Test
    void observerSeesRolesWhileOrdinaryGameViewStillHidesThem() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        GameService service = new GameService(mapper);
        GameService.GameView game = service.create(new GameService.CreateGameRequest(
                GameConfig.classicSeven(), List.of("RANDOM", "RANDOM", "RANDOM", "RANDOM",
                        "RANDOM", "RANDOM", "RANDOM"), 9L, null, true));
        String id = game.summary().gameId();
        assertTrue(service.get(id).players().stream().allMatch(p -> p.role() == null));
        assertTrue(service.observe(id).players().stream().allMatch(p -> p.role() != null));
        assertTrue(service.observe(id).notes().stream().anyMatch(n -> n.kind().equals("CLUE")));
        String publicJson = mapper.writeValueAsString(service.get(id));
        assertFalse(publicJson.contains("Wolf teammate"));
        assertFalse(publicJson.contains("\"role\":\"WEREWOLF\""));
    }
}
