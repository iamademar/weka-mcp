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

public class AssociationIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void apriori_on_nominal_data_produces_rules() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "weather.nominal.arff", "weather");

            String json = "{\"dataset\":\"weather\",\"algorithm\":\"weka.associations.Apriori\"}";
            try (Response r = post(client, "/associate", json)) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                JsonNode rules = body.get("rules");
                assertNotNull(rules, text);
                assertTrue(rules.size() > 0, "expected at least one rule");
                assertTrue(rules.get(0).has("premise"));
                assertTrue(rules.get(0).has("consequence"));
            }
        });
    }

    @Test
    public void apriori_on_numeric_data_is_rejected() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");

            String json = "{\"dataset\":\"iris\",\"algorithm\":\"weka.associations.Apriori\"}";
            try (Response r = post(client, "/associate", json)) {
                String text = r.body().string();
                assertEquals(422, r.code(), text);
                assertEquals("REQUIRES_NOMINAL", MAPPER.readTree(text).get("code").asText());
            }
        });
    }

    @Test
    public void associators_discovery_is_non_empty() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            try (Response r = client.get("/associators")) {
                JsonNode body = MAPPER.readTree(r.body().string());
                assertTrue(body.get("associators").size() > 0);
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
