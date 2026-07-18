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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class TransformPreviewIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void preview_does_not_persist_and_returns_head() throws Exception {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);
            List<Path> beforeFiles = listFiles(dataDir);

            String body = "{\"dataset\":\"iris\",\"filters\":[{\"filter\":\"weka.filters.unsupervised.attribute.Normalize\",\"options\":[]}]}";
            Request req = new Request.Builder()
                    .url(client.getOrigin() + "/transform/preview?head=15")
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(req).execute()) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertEquals(150, j.get("numInstances").asInt());
                assertEquals(150, j.get("totalInstances").asInt());
                assertEquals(15, j.get("head").size());
                assertEquals(5, j.get("numAttributes").asInt());
                assertTrue(j.get("attributes").get(0).has("type"));
            }

            // no file should have been added
            List<Path> afterFiles = listFiles(dataDir);
            assertEquals(beforeFiles.size(), afterFiles.size(),
                    "preview must not write to DATA_DIR");
        });
    }

    @Test
    public void preview_caps_head_at_200() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);
            String body = "{\"dataset\":\"iris\",\"filters\":[{\"filter\":\"weka.filters.unsupervised.attribute.Normalize\",\"options\":[]}]}";
            Request req = new Request.Builder()
                    .url(client.getOrigin() + "/transform/preview?head=9999")
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(req).execute()) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                // iris is 150 rows; QueryParams caps head at 200; iris < 200 so we get all 150
                assertTrue(j.get("head").size() <= 200);
                assertEquals(150, j.get("head").size());
            }
        });
    }

    private static List<Path> listFiles(Path dir) throws java.io.IOException {
        try (var stream = Files.list(dir)) {
            return stream.toList();
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
