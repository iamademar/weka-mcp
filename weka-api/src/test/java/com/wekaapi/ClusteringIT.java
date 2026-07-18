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

public class ClusteringIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void kmeans_train_assign_and_classes_to_clusters() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);

            // train SimpleKMeans with k=3, class ignored
            String trainJson = "{\"dataset\":\"iris\",\"algorithm\":\"weka.clusterers.SimpleKMeans\","
                    + "\"options\":[\"-N\",\"3\"],\"modelName\":\"iris-km\"}";
            try (Response r = post(client, "/cluster/train", trainJson)) {
                String text = r.body().string();
                assertEquals(201, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertEquals(3, body.get("numClusters").asInt());
            }

            // assign clusters for the dataset
            try (Response r = post(client, "/cluster/assign", "{\"model\":\"iris-km\",\"dataset\":\"iris\"}")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertEquals(150, body.get("assignments").size());
                assertTrue(body.get("assignments").get(0).has("cluster"));
            }

            // classes-to-clusters evaluation (iris has a nominal class)
            try (Response r = post(client, "/cluster/evaluate", "{\"model\":\"iris-km\",\"dataset\":\"iris\"}")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertEquals(3, body.get("numClusters").asInt());
                assertNotNull(body.get("summary"));
            }
        });
    }

    @Test
    public void invalid_clusterer_prefix_is_rejected() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);
            String trainJson = "{\"dataset\":\"iris\",\"algorithm\":\"java.lang.String\",\"modelName\":\"bad\"}";
            try (Response r = post(client, "/cluster/train", trainJson)) {
                String text = r.body().string();
                assertEquals(400, r.code(), text);
                assertEquals("INVALID_CLUSTERER", MAPPER.readTree(text).get("code").asText());
            }
        });
    }

    @Test
    public void clusterers_discovery_is_non_empty() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            try (Response r = client.get("/clusterers")) {
                JsonNode body = MAPPER.readTree(r.body().string());
                assertTrue(body.get("clusterers").size() > 0);
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
