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

public class AttributeSelectionIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void infogain_ranker_ranks_all_attributes() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);

            String json = "{\"dataset\":\"iris\","
                    + "\"evaluator\":\"weka.attributeSelection.InfoGainAttributeEval\","
                    + "\"search\":\"weka.attributeSelection.Ranker\"}";
            Request req = new Request.Builder()
                    .url(client.getOrigin() + "/attribute-selection")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(req).execute()) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                JsonNode ranking = body.get("ranking");
                assertNotNull(ranking, text);
                assertEquals(4, ranking.size(), "iris has 4 predictor attributes");
                // merits should be in non-increasing order
                double prev = Double.MAX_VALUE;
                for (JsonNode entry : ranking) {
                    double merit = entry.get("merit").asDouble();
                    assertTrue(merit <= prev, "ranking not descending: " + merit + " > " + prev);
                    prev = merit;
                }
            }
        });
    }

    @Test
    public void cfs_bestfirst_returns_a_subset() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);

            String json = "{\"dataset\":\"iris\","
                    + "\"evaluator\":\"weka.attributeSelection.CfsSubsetEval\","
                    + "\"search\":\"weka.attributeSelection.BestFirst\"}";
            Request req = new Request.Builder()
                    .url(client.getOrigin() + "/attribute-selection")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(req).execute()) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertTrue(body.get("selectedAttributes").size() > 0, text);
            }
        });
    }

    @Test
    public void ranker_with_subset_evaluator_is_rejected() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);

            String json = "{\"dataset\":\"iris\","
                    + "\"evaluator\":\"weka.attributeSelection.CfsSubsetEval\","
                    + "\"search\":\"weka.attributeSelection.Ranker\"}";
            Request req = new Request.Builder()
                    .url(client.getOrigin() + "/attribute-selection")
                    .post(RequestBody.create(json, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(req).execute()) {
                String text = r.body().string();
                assertEquals(400, r.code(), text);
                assertEquals("INCOMPATIBLE_SEARCH", MAPPER.readTree(text).get("code").asText());
            }
        });
    }

    @Test
    public void discovery_lists_evaluators_and_searches() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            try (Response r = client.get("/attribute-selection/evaluators")) {
                JsonNode body = MAPPER.readTree(r.body().string());
                assertTrue(body.get("evaluators").size() > 0);
            }
            try (Response r = client.get("/attribute-selection/searches")) {
                JsonNode body = MAPPER.readTree(r.body().string());
                assertTrue(body.get("searches").size() > 0);
            }
        });
    }

    private void uploadIris(HttpClient client) throws Exception {
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
        Request upload = new Request.Builder()
                .url(client.getOrigin() + "/datasets")
                .post(body)
                .build();
        try (Response r = client.getOkHttp().newCall(upload).execute()) {
            assertEquals(201, r.code(), r.body().string());
        }
    }
}
