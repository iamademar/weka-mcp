package com.wekaapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.testtools.JavalinTest;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class ModelGraphIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void j48_returns_dot_tree() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);
            trainJ48(client);

            try (Response r = get(client, "/models/iris-j48/drawable-type")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals("tree", j.get("type").asText());
            }

            try (Response r = get(client, "/models/iris-j48/tree")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals("dot", j.get("format").asText());
                String dot = j.get("graph").asText();
                assertFalse(dot.isBlank());
                assertTrue(dot.toLowerCase().contains("digraph") || dot.contains("graph "),
                        "expected DOT, got: " + dot.substring(0, Math.min(200, dot.length())));
            }

            try (Response r = get(client, "/models/iris-j48/graph")) {
                assertEquals(400, r.code());
                assertTrue(r.body().string().contains("NOT_DRAWABLE"));
            }
        });
    }

    @Test
    public void logistic_is_not_drawable() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);
            trainModel(client, "weka.classifiers.functions.Logistic", "iris-logit");

            try (Response r = get(client, "/models/iris-logit/drawable-type")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals("none", j.get("type").asText());
            }

            try (Response r = get(client, "/models/iris-logit/tree")) {
                assertEquals(400, r.code());
                assertTrue(r.body().string().contains("NOT_DRAWABLE"));
            }
        });
    }

    private static void trainJ48(io.javalin.testtools.HttpClient client) {
        trainModel(client, "weka.classifiers.trees.J48", "iris-j48");
    }

    private static void trainModel(io.javalin.testtools.HttpClient client, String algorithm, String name) {
        String json = "{\"dataset\":\"iris\",\"algorithm\":\"" + algorithm + "\",\"modelName\":\"" + name + "\"}";
        Request req = new Request.Builder()
                .url(client.getOrigin() + "/train")
                .post(RequestBody.create(json, MediaType.parse("application/json")))
                .build();
        try (Response r = client.getOkHttp().newCall(req).execute()) {
            assertEquals(201, r.code(), r.body().string());
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static Response get(io.javalin.testtools.HttpClient client, String path) {
        Request req = new Request.Builder().url(client.getOrigin() + path).get().build();
        try {
            return client.getOkHttp().newCall(req).execute();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void uploadIris(io.javalin.testtools.HttpClient client) throws java.io.IOException {
        byte[] iris;
        try (InputStream in = getClass().getResourceAsStream("/iris.arff")) {
            iris = in.readAllBytes();
        }
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", "iris")
                .addFormDataPart("file", "iris.arff",
                        RequestBody.create(iris, MediaType.parse("text/plain")))
                .build();
        Request req = new Request.Builder().url(client.getOrigin() + "/datasets").post(body).build();
        try (Response r = client.getOkHttp().newCall(req).execute()) {
            assertEquals(201, r.code(), r.body().string());
        }
    }
}
