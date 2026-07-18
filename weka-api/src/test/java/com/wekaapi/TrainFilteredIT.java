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

public class TrainFilteredIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void train_with_filters_uses_FilteredClassifier_under_the_hood() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);

            String trainJson = "{\"dataset\":\"iris\","
                    + "\"algorithm\":\"weka.classifiers.trees.J48\","
                    + "\"modelName\":\"iris-fc-j48\","
                    + "\"filters\":[{\"filter\":\"weka.filters.unsupervised.attribute.Normalize\",\"options\":[]}]}";

            try (Response r = post(client, "/train", trainJson)) {
                String text = r.body().string();
                assertEquals(201, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertEquals("iris-fc-j48", j.get("modelName").asText());
                JsonNode filters = j.get("filters");
                assertNotNull(filters);
                assertEquals(1, filters.size());
                assertEquals("weka.filters.unsupervised.attribute.Normalize", filters.get(0).asText());
            }

            // /predict accepts RAW features — FilteredClassifier transforms internally
            String predictJson = "{\"model\":\"iris-fc-j48\",\"instances\":[{\"sepallength\":5.1,\"sepalwidth\":3.5,\"petallength\":1.4,\"petalwidth\":0.2}]}";
            try (Response r = post(client, "/predict", predictJson)) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                String predicted = j.get("predictions").get(0).get("predictedClass").asText();
                assertTrue(predicted.startsWith("Iris-"),
                        "expected iris label, got: " + predicted);
            }

            // /evaluate also accepts the raw dataset
            try (Response r = post(client, "/evaluate", "{\"model\":\"iris-fc-j48\",\"dataset\":\"iris\"}")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                double accuracy = j.get("accuracy").asDouble();
                assertTrue(accuracy > 0.9, "expected accuracy > 0.9, got " + accuracy);
            }

            // Drawable delegates through FilteredClassifier → J48 tree
            try (Response r = get(client, "/models/iris-fc-j48/tree")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertEquals("dot", j.get("format").asText());
                assertFalse(j.get("graph").asText().isBlank());
            }
        });
    }

    @Test
    public void train_rejects_non_weka_filter() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);
            String body = "{\"dataset\":\"iris\","
                    + "\"algorithm\":\"weka.classifiers.trees.J48\","
                    + "\"modelName\":\"iris-bad-fc\","
                    + "\"filters\":[{\"filter\":\"java.lang.Runtime\",\"options\":[]}]}";
            try (Response r = post(client, "/train", body)) {
                assertEquals(400, r.code());
                assertTrue(r.body().string().contains("INVALID_FILTER"));
            }
        });
    }

    @Test
    public void chain_of_two_filters_uses_MultiFilter() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);
            String body = "{\"dataset\":\"iris\","
                    + "\"algorithm\":\"weka.classifiers.trees.J48\","
                    + "\"modelName\":\"iris-norm-pca-j48\","
                    + "\"filters\":["
                    + "  {\"filter\":\"weka.filters.unsupervised.attribute.Normalize\",\"options\":[]},"
                    + "  {\"filter\":\"weka.filters.unsupervised.attribute.RemoveUseless\",\"options\":[]}"
                    + "]}";
            try (Response r = post(client, "/train", body)) {
                String text = r.body().string();
                assertEquals(201, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertEquals(2, j.get("filters").size());
            }
        });
    }

    @Test
    public void numeric_to_nominal_cast_scopes_by_R_and_keeps_predict_parity() {
        // Mirrors the /domain type override: cast one numeric column to nominal via
        // NumericToNominal -R <idx> (exactly what app.services.type_override emits), baked
        // into the FilteredClassifier. Training must succeed and /predict must still accept
        // the RAW numeric value for that column (parity — the cast runs inside the model).
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIris(client);

            // petalwidth is the 4th attribute; -R 4 casts only it to nominal.
            String trainJson = "{\"dataset\":\"iris\","
                    + "\"algorithm\":\"weka.classifiers.trees.J48\","
                    + "\"modelName\":\"iris-cast-j48\","
                    + "\"filters\":[{\"filter\":\"weka.filters.unsupervised.attribute.NumericToNominal\","
                    + "\"options\":[\"-R\",\"4\"]}]}";
            try (Response r = post(client, "/train", trainJson)) {
                String text = r.body().string();
                assertEquals(201, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertEquals("weka.filters.unsupervised.attribute.NumericToNominal",
                        j.get("filters").get(0).asText());
            }

            // Raw numeric petalwidth still accepted — the model casts it internally.
            String predictJson = "{\"model\":\"iris-cast-j48\",\"instances\":[{\"sepallength\":5.1,"
                    + "\"sepalwidth\":3.5,\"petallength\":1.4,\"petalwidth\":0.2}]}";
            try (Response r = post(client, "/predict", predictJson)) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertTrue(j.get("predictions").get(0).get("predictedClass").asText().startsWith("Iris-"),
                        "expected iris label, got: " + text);
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
