package com.example.werewolf.controller;

import com.example.werewolf.experiment.ExperimentAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 检查导出访问隔离、模型批量禁用和普通手动对局的持续记录。 */
@SpringBootTest
@AutoConfigureMockMvc
class ExperimentControllerTest {
    @TempDir static Path directory;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("werewolf.experiments.directory", () -> directory.toString());
        registry.add("werewolf.knowledge.directory", () -> directory.resolve("knowledge").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void jsonCanBeImportedByAnotherBrowserAndReplayIsOwnerRestricted() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var created = mvc.perform(post("/api/games").session(session).contentType(MediaType.APPLICATION_JSON)
                .content("{\"seed\":21,\"manual\":false}")).andExpect(status().isOk()).andReturn().getResponse();
        String originalId = mapper.readTree(created.getContentAsString()).path("summary").path("gameId").asText();
        Cookie originalCookie = created.getCookie(ExperimentAccess.COOKIE_NAME);
        byte[] json = mvc.perform(get("/api/experiments/" + originalId + "/export").cookie(originalCookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        var response = mvc.perform(multipart("/api/experiments/import").file(new MockMultipartFile("file", "../../export.json", "application/json", json)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.record.source").value("IMPORTED")).andReturn().getResponse();
        String importedId = mapper.readTree(response.getContentAsString()).path("record").path("id").asText();
        Cookie importedCookie = response.getCookie(ExperimentAccess.COOKIE_NAME);
        assertNotEquals(originalId, importedId);
        assertNotEquals(originalCookie.getValue(), importedCookie.getValue());
        String replay = "/api/experiments/" + importedId + "/games/" + originalId;
        mvc.perform(get(replay).cookie(importedCookie)).andExpect(status().isOk()).andExpect(jsonPath("$.seats.length()").value(7))
                .andExpect(jsonPath("$.observerNotes[0].kind").value("CLUE"));
        mvc.perform(post(replay + "/observer-notes").contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isForbidden());
        mvc.perform(post(replay + "/observer-notes").cookie(importedCookie).contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/games/" + originalId).session(session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.observerNotes").doesNotExist());
        mvc.perform(get(replay).cookie(originalCookie)).andExpect(status().isForbidden());
        mvc.perform(get(replay)).andExpect(status().isForbidden());
        mvc.perform(get("/api/experiments/games").cookie(importedCookie)).andExpect(status().isOk()).andExpect(jsonPath("$[0].experimentId").value(importedId));
        mvc.perform(get("/api/games").cookie(importedCookie)).andExpect(content().json("[]"));
        mvc.perform(multipart("/api/experiments/import").file(new MockMultipartFile("file", "invalid.json", "application/json", "{bad}".getBytes())))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").isString());
    }

    @Test
    void batchExportRequiresOriginalBrowserAndOmitsOwnershipCredential() throws Exception {
        var response = mvc.perform(post("/api/experiments").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"HTTP 基线\",\"runs\":2,\"startSeed\":42}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse();
        Cookie cookie = response.getCookie(ExperimentAccess.COOKIE_NAME);
        assertNotNull(cookie); assertTrue(cookie.isHttpOnly());
        assertTrue(response.getHeader("Set-Cookie").contains("SameSite=Strict"));
        String id = mapper.readTree(response.getContentAsString()).path("record").path("id").asText();
        mvc.perform(get("/api/experiments/" + id)).andExpect(status().isForbidden());
        mvc.perform(get("/api/experiments/" + id + "/export")).andExpect(status().isForbidden());
        mvc.perform(post("/api/experiments/" + id + "/cancel")).andExpect(status().isForbidden());
        assertEquals("[]", mvc.perform(get("/api/experiments")).andReturn().getResponse().getContentAsString());
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        JsonNode detail;
        do {
            detail = mapper.readTree(mvc.perform(get("/api/experiments/" + id).cookie(cookie))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
            if (!detail.path("record").path("status").asText().equals("RUNNING")) break;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        assertEquals(2, detail.path("summary").path("completedGames").asInt());
        for (String format : new String[]{"json", "csv", "actions"}) {
            var exported = mvc.perform(get("/api/experiments/" + id + "/export").cookie(cookie).param("format", format))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                    .andReturn().getResponse();
            assertTrue(exported.getHeader("Content-Disposition").contains("attachment"));
            assertFalse(exported.getContentAsString().contains(cookie.getValue()));
        }
        mvc.perform(get("/api/experiments/" + id + "/export").cookie(cookie).param("format", "bad"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/experiments").cookie(cookie).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"runs\":2,\"startSeed\":1,\"agentTypes\":[\"LLM\",\"LLM\",\"LLM\",\"LLM\",\"LLM\",\"LLM\",\"LLM\"]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ordinaryManualGameUpdatesRecordOnlyWhenUserAdvances() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var response = mvc.perform(post("/api/games").session(session).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"manual\":true,\"seed\":123}"))
                .andExpect(status().isOk()).andReturn().getResponse();
        String id = mapper.readTree(response.getContentAsString()).path("summary").path("gameId").asText();
        Cookie cookie = response.getCookie(ExperimentAccess.COOKIE_NAME);
        JsonNode before = mapper.readTree(mvc.perform(get("/api/experiments/" + id).cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(0, before.path("summary").path("actionAttempts").asInt());
        assertEquals(0, before.path("summary").path("completedGames").asInt());
        mvc.perform(post("/api/games/" + id + "/advance").session(session).cookie(cookie)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"command\":\"NEXT_ACTION\"}"))
                .andExpect(status().isOk());
        JsonNode after = mapper.readTree(mvc.perform(get("/api/experiments/" + id).cookie(cookie))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1, after.path("summary").path("actionAttempts").asInt());
        assertEquals(123, after.path("record").path("games").get(0).path("seed").asInt());
        mvc.perform(get("/api/games/" + id).session(session)).andExpect(jsonPath("$.players[0].role").isEmpty());
    }
}
