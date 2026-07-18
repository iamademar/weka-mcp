package com.wekaapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class HealthControllerTest {

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void health_returns_ok_with_version() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            var resp = client.get("/health");
            assertEquals(200, resp.code());
            JsonNode body = new ObjectMapper().readTree(resp.body().string());
            assertEquals("ok", body.get("status").asText());
            assertNotNull(body.get("wekaVersion"));
            assertFalse(body.get("wekaVersion").asText().isBlank());
        });
    }
}
