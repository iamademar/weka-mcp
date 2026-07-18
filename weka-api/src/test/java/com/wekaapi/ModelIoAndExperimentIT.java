package com.wekaapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.testtools.HttpClient;
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

/** Covers model export/import (B8) and the experimenter (A5). */
public class ModelIoAndExperimentIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void download_then_reimport_model_round_trips() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");
            train(client, "iris", "weka.classifiers.trees.J48", "iris-j48");

            byte[] modelBytes;
            try (Response r = client.get("/models/iris-j48/download")) {
                assertEquals(200, r.code());
                assertEquals("application/octet-stream", r.header("Content-Type"));
                modelBytes = r.body().bytes();
                assertTrue(modelBytes.length > 0);
            }

            // re-import under a new name, supplying the dataset for the header
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("dataset", "iris")
                    .addFormDataPart("file", "iris-j48.model",
                            RequestBody.create(modelBytes, MediaType.parse("application/octet-stream")))
                    .build();
            Request imp = new Request.Builder()
                    .url(client.getOrigin() + "/models/iris-imported/import")
                    .post(body)
                    .build();
            try (Response r = client.getOkHttp().newCall(imp).execute()) {
                String text = r.body().string();
                assertEquals(201, r.code(), text);
                assertEquals("weka.classifiers.trees.J48", MAPPER.readTree(text).get("algorithm").asText());
            }

            // imported model should predict
            try (Response r = post(client, "/predict/dataset", "{\"model\":\"iris-imported\",\"dataset\":\"iris\"}")) {
                assertEquals(200, r.code(), r.body().string());
            }
        });
    }

    @Test
    public void import_rejects_non_model_file() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");

            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("dataset", "iris")
                    .addFormDataPart("file", "junk.model",
                            RequestBody.create("not a model".getBytes(), MediaType.parse("application/octet-stream")))
                    .build();
            Request imp = new Request.Builder()
                    .url(client.getOrigin() + "/models/junk/import")
                    .post(body)
                    .build();
            try (Response r = client.getOkHttp().newCall(imp).execute()) {
                String text = r.body().string();
                assertEquals(400, r.code(), text);
                assertEquals("INVALID_MODEL_FILE", MAPPER.readTree(text).get("code").asText());
            }
        });
    }

    @Test
    public void experiment_compares_j48_against_zeror() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");

            String json = "{\"datasets\":[\"iris\"],"
                    + "\"algorithms\":[{\"algorithm\":\"weka.classifiers.rules.ZeroR\"},"
                    + "{\"algorithm\":\"weka.classifiers.trees.J48\"}],"
                    + "\"runs\":2,\"folds\":5,\"baselineIndex\":0}";
            try (Response r = post(client, "/experiment", json)) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                JsonNode results = body.get("results");
                assertEquals(2, results.size());
                // find J48 row, expect higher mean accuracy than ZeroR baseline
                double j48 = 0, zeroR = 0;
                for (JsonNode row : results) {
                    if (row.get("algorithm").asText().endsWith("J48")) j48 = row.get("mean").asDouble();
                    else zeroR = row.get("mean").asDouble();
                }
                assertTrue(j48 > zeroR, "J48 (" + j48 + ") should beat ZeroR (" + zeroR + ")");
                // every row carries a significance label
                for (JsonNode row : results) assertTrue(row.has("significance"));
            }
        });
    }

    private Response post(HttpClient client, String path, String json) throws Exception {
        Request req = new Request.Builder()
                .url(client.getOrigin() + path)
                .post(RequestBody.create(json, JSON))
                .build();
        return client.getOkHttp().newCall(req).execute();
    }

    private void train(HttpClient client, String dataset, String algorithm, String modelName) throws Exception {
        String json = "{\"dataset\":\"" + dataset + "\",\"algorithm\":\"" + algorithm
                + "\",\"modelName\":\"" + modelName + "\"}";
        try (Response r = post(client, "/train", json)) {
            assertEquals(201, r.code(), r.body().string());
        }
    }

    private void upload(HttpClient client, String resource, String name) throws Exception {
        byte[] bytes;
        try (InputStream in = getClass().getResourceAsStream("/" + resource)) {
            bytes = in.readAllBytes();
        }
        MultipartBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("name", name)
                .addFormDataPart("file", resource,
                        RequestBody.create(bytes, MediaType.parse("text/plain")))
                .build();
        Request upload = new Request.Builder()
                .url(client.getOrigin() + "/datasets")
                .post(body)
                .build();
        try (Response r = client.getOkHttp().newCall(upload).execute()) {
            assertEquals(201, r.code(), r.body().string());
        }
    }
}
