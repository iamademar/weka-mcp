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

public class TransformIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void filters_listed_and_normalize_works() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            try (Response r = get(client, "/filters")) {
                assertEquals(200, r.code());
                String body = r.body().string();
                assertTrue(body.contains("weka.filters.unsupervised.attribute.Normalize"),
                        "expected Normalize in /filters, got: " + body);
            }

            uploadIris(client);

            String body = "{\"dataset\":\"iris\",\"filters\":[{\"filter\":\"weka.filters.unsupervised.attribute.Normalize\",\"options\":[]}],\"outputName\":\"iris-norm\"}";
            Request req = new Request.Builder()
                    .url(client.getOrigin() + "/transform")
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(req).execute()) {
                String text = r.body().string();
                assertEquals(201, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertEquals("iris-norm", j.get("name").asText());
                assertEquals(150, j.get("numInstances").asInt());
                assertEquals(5, j.get("numAttributes").asInt());
            }

            // verify the new dataset is loadable
            try (Response r = get(client, "/datasets/iris-norm")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                assertEquals(150, j.get("numInstances").asInt());
            }

            // stats should show normalized range
            try (Response r = get(client, "/datasets/iris-norm/attribute-stats?attribute=petallength")) {
                assertEquals(200, r.code());
                JsonNode j = MAPPER.readTree(r.body().string());
                double max = j.get("numeric").get("max").asDouble();
                double min = j.get("numeric").get("min").asDouble();
                assertTrue(max <= 1.0001 && min >= -0.0001,
                        "normalized range expected [0,1], got [" + min + "," + max + "]");
            }
        });
    }

    @Test
    public void rejects_non_weka_filter() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);
            String body = "{\"dataset\":\"iris\",\"filters\":[{\"filter\":\"java.lang.Runtime\",\"options\":[]}],\"outputName\":\"x\"}";
            Request req = new Request.Builder()
                    .url(client.getOrigin() + "/transform")
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(req).execute()) {
                assertEquals(400, r.code());
                assertTrue(r.body().string().contains("INVALID_FILTER"));
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
