package com.example.werewolf.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.junit.jupiter.api.Assertions.*;

/** 验证浏览器会话边界，避免凭公开的对局 ID 读取身份或代替用户推进。 */
@SpringBootTest
@AutoConfigureMockMvc
class GameControllerSecurityTest {
    @TempDir static Path directory;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("werewolf.experiments.directory", () -> directory.toString());
        registry.add("werewolf.knowledge.directory", () -> directory.resolve("knowledge").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void onlyCreatorSessionCanReadAndAdvanceGame() throws Exception {
        MockHttpSession owner = new MockHttpSession();
        MockHttpSession stranger = new MockHttpSession();
        String body = "{\"config\":{\"playerCount\":5,\"werewolves\":1,\"villagers\":4,\"seers\":0,\"witches\":0},"
                + "\"agentTypes\":[\"RANDOM\",\"RANDOM\",\"RANDOM\",\"RANDOM\",\"RANDOM\"],\"manual\":true}";
        String created = mvc.perform(post("/api/games").session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode game = mapper.readTree(created);
        String id = game.path("summary").path("gameId").asText();

        mvc.perform(get("/api/games/" + id).session(stranger)).andExpect(status().isForbidden());
        mvc.perform(get("/api/games/" + id + "/observer").session(stranger)).andExpect(status().isForbidden());
        mvc.perform(post("/api/games/" + id + "/advance").session(stranger)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"command\":\"NEXT_ACTION\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/games/" + id + "/retry").session(stranger)).andExpect(status().isForbidden());
        // 未触发保护时，即使是创建者也不能用恢复接口代替推进或跳过玩家。
        mvc.perform(post("/api/games/" + id + "/retry").session(owner)).andExpect(status().isBadRequest());
        assertEquals("[]", mvc.perform(get("/api/games").session(stranger))
                .andReturn().getResponse().getContentAsString());

        String observer = mvc.perform(get("/api/games/" + id + "/observer").session(owner))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
        assertTrue(mapper.readTree(observer).path("players").get(0).path("role").isTextual());
        mvc.perform(post("/api/games/" + id + "/advance").session(owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"command\":\"NEXT_ACTION\"}"))
                .andExpect(status().isOk());
    }
}
