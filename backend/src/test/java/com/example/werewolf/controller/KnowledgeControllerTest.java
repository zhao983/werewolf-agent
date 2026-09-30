package com.example.werewolf.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** 用户管理接口需要同源自定义请求头；无效导入不改动已有知识。 */
@SpringBootTest @AutoConfigureMockMvc
class KnowledgeControllerTest {
    @TempDir static Path directory;
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("werewolf.experiments.directory", () -> directory.resolve("experiments").toString());
        r.add("werewolf.knowledge.directory", () -> directory.resolve("knowledge").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Test void userCanAddEditDisableExportImportAndDelete() throws Exception {
        String body = "{\"title\":\"测试建议\",\"scope\":\"SEER\",\"phases\":[\"NIGHT_SEER\"],\"content\":\"优先查验未知且有争议的玩家\",\"priority\":70,\"enabled\":true}";
        mvc.perform(post("/api/knowledge").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
        var added = mapper.readTree(mvc.perform(post("/api/knowledge").header("X-Werewolf-Knowledge", "1")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        String id = added.path("entries").get(added.path("entries").size() - 1).path("id").asText();
        var updated = mapper.readTree(mvc.perform(put("/api/knowledge/" + id).param("revision", added.path("revision").asText())
                .header("X-Werewolf-Knowledge", "1").contentType(MediaType.APPLICATION_JSON).content(body.replace("true", "false")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertFalse(updated.path("entries").get(updated.path("entries").size() - 1).path("enabled").asBoolean());
        byte[] exported = mvc.perform(get("/api/knowledge/export")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsByteArray();
        var imported = mapper.readTree(mvc.perform(multipart("/api/knowledge/import")
                .file(new MockMultipartFile("file", "../../knowledge.json", "application/json", exported)).header("X-Werewolf-Knowledge", "1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(updated.path("entries").size() * 2, imported.path("entries").size());
        mvc.perform(delete("/api/knowledge/" + id).param("revision", imported.path("revision").asText()).header("X-Werewolf-Knowledge", "1"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/knowledge")).andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    }
    @Test void crossSiteFormsAndInvalidImportsAreRejected() throws Exception {
        String before = mvc.perform(get("/api/knowledge")).andReturn().getResponse().getContentAsString();
        var file = new MockMultipartFile("file", "bad.json", "application/json", "{}".getBytes());
        mvc.perform(multipart("/api/knowledge/import").file(file)).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/knowledge/import").file(file).header("X-Werewolf-Knowledge", "1")).andExpect(status().isBadRequest());
        assertEquals(before, mvc.perform(get("/api/knowledge")).andReturn().getResponse().getContentAsString());
    }
}
