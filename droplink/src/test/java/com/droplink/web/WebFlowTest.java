package com.droplink.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole thing through the web layer, in local mode: make a link, upload, open, preview, download, limit, password, manage, delete. */
@SpringBootTest
@AutoConfigureMockMvc
class WebFlowTest {

    @TempDir
    static Path dir;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("droplink.mode", () -> "local");
        registry.add("droplink.local-dir", () -> dir.toString());
        registry.add("droplink.secret", () -> "test-secret-test-secret-test-secret");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode create(String name, String type, int size, String extra) throws Exception {
        String body = "{\"fileName\":\"" + name + "\",\"contentType\":\"" + type + "\",\"size\":" + size + ",\"expiresInHours\":24" + extra + "}";
        MvcResult result = mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void uploadAndComplete(JsonNode created, byte[] bytes) throws Exception {
        URI url = URI.create(created.at("/upload/url").asText());
        mvc.perform(put(url.getPath()).contentType(created.at("/upload/headers/Content-Type").asText()).content(bytes)).andExpect(status().isOk());
        mvc.perform(post("/api/links/" + created.get("id").asText() + "/complete").header("X-Manage-Token", created.get("manageToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void startPageAndSecurityHeaders() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.containsString("Create link")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().exists("Content-Security-Policy"));
    }

    @Test
    void textFileFlowWithOneTimeDownload() throws Exception {
        byte[] bytes = "hello <b>world</b>".getBytes();
        JsonNode created = create("notes.txt", "text/plain", bytes.length, ",\"maxDownloads\":1");
        String id = created.get("id").asText();

        // not active before the upload is completed
        mvc.perform(get("/d/" + id)).andExpect(status().isNotFound());
        uploadAndComplete(created, bytes);

        mvc.perform(get("/d/" + id)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("notes.txt")));
        mvc.perform(get("/d/" + id + "/preview").header("Accept", "application/json")).andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("TEXT")).andExpect(jsonPath("$.text").value("hello <b>world</b>"));
        mvc.perform(get("/d/" + id + "/qr")).andExpect(status().isOk()).andExpect(content().contentType(MediaType.IMAGE_PNG));

        MvcResult download = mvc.perform(post("/d/" + id + "/download")).andExpect(status().isOk()).andReturn();
        String fileUrl = json.readTree(download.getResponse().getContentAsString()).get("url").asText();
        MvcResult file = mvc.perform(get(URI.create(fileUrl).getPath())).andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment"))).andReturn();
        assertEquals("hello <b>world</b>", file.getResponse().getContentAsString());

        // one-time: the second try is refused
        mvc.perform(post("/d/" + id + "/download").header("Accept", "application/json")).andExpect(status().isGone());

        // the creator sees one download with a masked address
        MvcResult activity = mvc.perform(get("/api/links/" + id + "/activity").header("X-Manage-Token", created.get("manageToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.downloads").value(1)).andReturn();
        assertTrue(activity.getResponse().getContentAsString().contains(".x"));
    }

    @Test
    void passwordProtectsPreviewAndDownload() throws Exception {
        byte[] bytes = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
        JsonNode created = create("pic.png", "image/png", bytes.length, ",\"password\":\"secret1\"");
        String id = created.get("id").asText();
        uploadAndComplete(created, bytes);

        mvc.perform(get("/d/" + id + "/preview").header("Accept", "application/json")).andExpect(status().isForbidden());
        mvc.perform(post("/d/" + id + "/download").header("Accept", "application/json")).andExpect(status().isForbidden());
        mvc.perform(post("/d/" + id + "/unlock").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized());

        MvcResult unlocked = mvc.perform(post("/d/" + id + "/unlock").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"secret1\"}"))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = unlocked.getResponse().getCookie("dl_u_" + id);
        assertTrue(cookie != null && cookie.isHttpOnly());

        MvcResult preview = mvc.perform(get("/d/" + id + "/preview").cookie(cookie).header("Accept", "application/json"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.kind").value("IMAGE")).andReturn();
        String url = json.readTree(preview.getResponse().getContentAsString()).get("url").asText();
        mvc.perform(get(URI.create(url).getPath())).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")));
        // the preview is not a download
        mvc.perform(get("/api/links/" + id + "/activity").header("X-Manage-Token", created.get("manageToken").asText()))
                .andExpect(jsonPath("$.downloads").value(0));
    }

    @Test
    void fifthWrongPasswordIsRateLimited() throws Exception {
        JsonNode created = create("a.txt", "text/plain", 3, ",\"password\":\"secret1\"");
        String id = created.get("id").asText();
        uploadAndComplete(created, "abc".getBytes());
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/d/" + id + "/unlock").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"bad" + i + "\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/d/" + id + "/unlock").contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"secret1\"}"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void htmlFileIsShownAsTextNeverServedInline() throws Exception {
        byte[] bytes = "<script>alert(1)</script>".getBytes();
        JsonNode created = create("page.html", "text/html", bytes.length, "");
        String id = created.get("id").asText();
        uploadAndComplete(created, bytes);
        mvc.perform(get("/d/" + id + "/preview").header("Accept", "application/json"))
                .andExpect(jsonPath("$.kind").value("TEXT")).andExpect(jsonPath("$.url").doesNotExist());
    }

    @Test
    void manageNeedsTheKeyAndDeleteRemovesEverything() throws Exception {
        JsonNode created = create("b.txt", "text/plain", 3, "");
        String id = created.get("id").asText();
        uploadAndComplete(created, "abc".getBytes());
        mvc.perform(get("/api/links/" + id + "/activity")).andExpect(status().isForbidden());
        mvc.perform(get("/api/links/" + id + "/activity").header("X-Manage-Token", "wrong")).andExpect(status().isForbidden());
        mvc.perform(delete("/api/links/" + id).header("X-Manage-Token", created.get("manageToken").asText())).andExpect(status().isNoContent());
        mvc.perform(get("/d/" + id)).andExpect(status().isNotFound());
        assertFalse(java.nio.file.Files.exists(dir.resolve("files").resolve(id)));
    }

    @Test
    void refusalsAreClear() throws Exception {
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"x\",\"contentType\":\"a/b\",\"size\":0,\"expiresInHours\":24}")).andExpect(status().isBadRequest());
        mvc.perform(post("/api/links").contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileName\":\"x\",\"contentType\":\"a/b\",\"size\":5,\"expiresInHours\":5}")).andExpect(status().isBadRequest());
        mvc.perform(get("/d/abc")).andExpect(status().isNotFound());
        mvc.perform(get("/local-store/download/garbage")).andExpect(status().isForbidden());
        mvc.perform(put("/local-store/upload/garbage").content("x")).andExpect(status().isForbidden());
    }
}
