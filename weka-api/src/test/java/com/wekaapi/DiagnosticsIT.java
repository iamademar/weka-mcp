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

public class DiagnosticsIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void diagnostics_endpoints_work_on_j48_iris() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);
            trainModel(client, "weka.classifiers.trees.J48", "iris-j48");

            // errors
            try (Response r = post(client, "/diagnostics/errors",
                    "{\"model\":\"iris-j48\",\"dataset\":\"iris\"}")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertEquals("nominal", j.get("classType").asText());
                assertEquals(150, j.get("totalInstances").asInt());
                assertTrue(j.get("points").size() > 0);
                JsonNode first = j.get("points").get(0);
                assertNotNull(first.get("actual"));
                assertNotNull(first.get("predicted"));
                assertNotNull(first.get("correct"));
            }

            // threshold curve
            try (Response r = post(client, "/diagnostics/threshold-curve",
                    "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"classValue\":\"Iris-setosa\"}")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertEquals("Iris-setosa", j.get("classValue").asText());
                double auc = j.get("auc").asDouble();
                assertTrue(auc > 0.9, "expected high AUC on iris, got " + auc);
                assertTrue(j.get("points").size() > 0);
            }

            // margin curve
            try (Response r = post(client, "/diagnostics/margin-curve",
                    "{\"model\":\"iris-j48\",\"dataset\":\"iris\"}")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertTrue(j.get("points").size() > 0);
            }

            // cost curve
            try (Response r = post(client, "/diagnostics/cost-curve",
                    "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"classValue\":\"Iris-versicolor\"}")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertTrue(j.get("points").size() > 0);
            }

            // calibration
            try (Response r = post(client, "/diagnostics/calibration",
                    "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"classValue\":\"Iris-versicolor\",\"bins\":10}")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertEquals(10, j.get("bins").size());
                assertNotNull(j.get("brierScore"));
            }

            // invalid class value
            try (Response r = post(client, "/diagnostics/threshold-curve",
                    "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"classValue\":\"NotAClass\"}")) {
                assertEquals(400, r.code());
                assertTrue(r.body().string().contains("INVALID_CLASS_VALUE"));
            }
        });
    }

    private static Response post(io.javalin.testtools.HttpClient client, String path, String body) {
        Request req = new Request.Builder()
                .url(client.getOrigin() + path)
                .post(RequestBody.create(body, MediaType.parse("application/json")))
                .build();
        try {
            return client.getOkHttp().newCall(req).execute();
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static void trainModel(io.javalin.testtools.HttpClient client, String algorithm, String name) throws java.io.IOException {
        String json = "{\"dataset\":\"iris\",\"algorithm\":\"" + algorithm + "\",\"modelName\":\"" + name + "\"}";
        try (Response r = post(client, "/train", json)) {
            assertEquals(201, r.code(), r.body().string());
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
