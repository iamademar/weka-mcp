package com.wekaapi;

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

public class SecurityTest {

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void rejects_non_weka_algorithm() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            String body = "{\"dataset\":\"iris\",\"algorithm\":\"java.lang.Runtime\",\"modelName\":\"x\"}";
            Request req = new Request.Builder()
                    .url(client.getOrigin() + "/train")
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(req).execute()) {
                assertEquals(400, r.code());
                String resp = r.body().string();
                assertTrue(resp.contains("INVALID_ALGORITHM"), "expected INVALID_ALGORITHM, got: " + resp);
            }
        });
    }

    @Test
    public void rejects_traversal_model_name() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            String body = "{\"dataset\":\"iris\",\"algorithm\":\"weka.classifiers.trees.J48\",\"modelName\":\"../escape\"}";
            Request req = new Request.Builder()
                    .url(client.getOrigin() + "/train")
                    .post(RequestBody.create(body, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(req).execute()) {
                assertEquals(400, r.code());
                String resp = r.body().string();
                assertTrue(resp.contains("INVALID_NAME"), "expected INVALID_NAME, got: " + resp);
            }
        });
    }

    @Test
    public void rejects_traversal_dataset_name() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            byte[] iris;
            try (InputStream in = getClass().getResourceAsStream("/iris.arff")) {
                iris = in.readAllBytes();
            }
            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("name", "../foo")
                    .addFormDataPart("file", "iris.arff",
                            RequestBody.create(iris, MediaType.parse("text/plain")))
                    .build();
            Request req = new Request.Builder()
                    .url(client.getOrigin() + "/datasets")
                    .post(body)
                    .build();
            try (Response r = client.getOkHttp().newCall(req).execute()) {
                assertEquals(400, r.code());
                String resp = r.body().string();
                assertTrue(resp.contains("INVALID_NAME"), "expected INVALID_NAME, got: " + resp);
            }
        });
    }
}
