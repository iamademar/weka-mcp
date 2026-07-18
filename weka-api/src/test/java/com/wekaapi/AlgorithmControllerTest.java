package com.wekaapi;

import io.javalin.testtools.JavalinTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class AlgorithmControllerTest {

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void algorithms_lists_j48() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            var resp = client.get("/algorithms");
            assertEquals(200, resp.code());
            String body = resp.body().string();
            assertTrue(body.contains("weka.classifiers.trees.J48"),
                    "expected J48 in response, got: " + body);
        });
    }
}
