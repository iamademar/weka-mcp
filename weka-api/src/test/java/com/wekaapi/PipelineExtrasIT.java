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

/** Covers batch prediction (B1), cost matrix (B3), and regression diagnostics + PR curve (B5). */
public class PipelineExtrasIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void batch_predict_over_dataset_scores_every_row() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");
            train(client, "iris", "weka.classifiers.trees.J48", "iris-j48");

            String json = "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"includeDistribution\":true}";
            try (Response r = post(client, "/predict/dataset", json)) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertEquals(150, body.get("numInstances").asInt());
                assertEquals(150, body.get("predictions").size());
                assertTrue(body.get("predictions").get(0).has("distribution"));
            }
        });
    }

    @Test
    public void cost_matrix_yields_total_cost() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");
            train(client, "iris", "weka.classifiers.trees.J48", "iris-j48");

            // 3x3 cost matrix, zero diagonal
            String json = "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"method\":\"test_set\","
                    + "\"costMatrix\":[[0,1,2],[1,0,1],[2,1,0]]}";
            try (Response r = post(client, "/evaluate", json)) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertTrue(body.has("totalCost"), text);
                assertTrue(body.has("avgCost"), text);
            }
        });
    }

    @Test
    public void cost_matrix_wrong_size_is_rejected() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");
            train(client, "iris", "weka.classifiers.trees.J48", "iris-j48");

            String json = "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"costMatrix\":[[0,1],[1,0]]}";
            try (Response r = post(client, "/evaluate", json)) {
                String text = r.body().string();
                assertEquals(400, r.code(), text);
                assertEquals("BAD_REQUEST", MAPPER.readTree(text).get("code").asText());
            }
        });
    }

    @Test
    public void residuals_on_regression_model() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "cpu.numeric.arff", "cpu");
            train(client, "cpu", "weka.classifiers.trees.REPTree", "cpu-rep");

            try (Response r = post(client, "/diagnostics/residuals", "{\"model\":\"cpu-rep\",\"dataset\":\"cpu\"}")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertTrue(body.get("points").size() > 0);
                assertTrue(body.get("points").get(0).has("residual"));
            }
        });
    }

    @Test
    public void residuals_rejects_nominal_class() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");
            train(client, "iris", "weka.classifiers.trees.J48", "iris-j48");

            try (Response r = post(client, "/diagnostics/residuals", "{\"model\":\"iris-j48\",\"dataset\":\"iris\"}")) {
                String text = r.body().string();
                assertEquals(400, r.code(), text);
                assertEquals("NOT_NUMERIC_CLASS", MAPPER.readTree(text).get("code").asText());
            }
        });
    }

    @Test
    public void pr_curve_on_iris() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");
            train(client, "iris", "weka.classifiers.trees.J48", "iris-j48");

            String json = "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"classValue\":\"Iris-setosa\"}";
            try (Response r = post(client, "/diagnostics/pr-curve", json)) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertTrue(body.has("auprc"), text);
                assertTrue(body.get("points").size() > 0);
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
