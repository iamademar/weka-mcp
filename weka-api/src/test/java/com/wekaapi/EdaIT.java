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

public class EdaIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void all_eda_endpoints_work_on_iris() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);

            // attribute-stats numeric
            try (Response r = get(client, "/datasets/iris/attribute-stats?attribute=petallength")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals("petallength", j.get("name").asText());
                assertEquals("numeric", j.get("type").asText());
                assertEquals(150, j.get("count").asInt());
                JsonNode numeric = j.get("numeric");
                assertNotNull(numeric);
                assertTrue(numeric.get("max").asDouble() > numeric.get("min").asDouble());
                // Full-dataset quartiles + outlier count (computed over all 150 rows).
                double q1 = numeric.get("q1").asDouble();
                double median = numeric.get("median").asDouble();
                double q3 = numeric.get("q3").asDouble();
                assertTrue(q1 <= median && median <= q3, "expected q1 <= median <= q3");
                assertTrue(q1 >= numeric.get("min").asDouble() && q3 <= numeric.get("max").asDouble());
                assertNotNull(numeric.get("outlierCount"));
                assertTrue(numeric.get("outlierCount").asInt() >= 0);
            }

            // attribute-stats nominal
            try (Response r = get(client, "/datasets/iris/attribute-stats?attribute=class")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals("nominal", j.get("type").asText());
                JsonNode counts = j.get("nominalCounts");
                assertEquals(50, counts.get("Iris-setosa").asInt());
            }

            // summary
            try (Response r = get(client, "/datasets/iris/summary")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals(150, j.get("numInstances").asInt());
                assertEquals(5, j.get("attributes").size());
            }

            // histogram numeric with byClass
            try (Response r = get(client, "/datasets/iris/histogram?attribute=petallength&bins=5&groupBy=class")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals(5, j.get("bins").size());
                JsonNode firstBin = j.get("bins").get(0);
                assertNotNull(firstBin.get("byClass"));
                int total = 0;
                for (JsonNode b : j.get("bins")) total += b.get("count").asInt();
                assertEquals(150, total);
            }

            // histogram nominal
            try (Response r = get(client, "/datasets/iris/histogram?attribute=class")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals(3, j.get("bins").size());
            }

            // scatter
            try (Response r = get(client, "/datasets/iris/scatter?x=petallength&y=petalwidth&sample=200")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals(150, j.get("totalInstances").asInt());
                assertTrue(j.get("points").size() > 0);
                assertNotNull(j.get("points").get(0).get("class"));
            }

            // scatter-matrix
            try (Response r = get(client, "/datasets/iris/scatter-matrix?attributes=sepallength,sepalwidth,petallength&sample=100")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals(3, j.get("pairs").size());
            }

            // invalid attribute
            try (Response r = get(client, "/datasets/iris/attribute-stats?attribute=nonexistent")) {
                assertEquals(400, r.code());
                String body = r.body().string();
                assertTrue(body.contains("INVALID_ATTRIBUTE"));
            }
        });
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
