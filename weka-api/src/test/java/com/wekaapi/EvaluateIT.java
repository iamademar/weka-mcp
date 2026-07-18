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

public class EvaluateIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void evaluate_on_training_set_is_accurate() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
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

            String trainJson = "{\"dataset\":\"iris\",\"algorithm\":\"weka.classifiers.trees.J48\",\"modelName\":\"iris-j48\"}";
            Request train = new Request.Builder()
                    .url(client.getOrigin() + "/train")
                    .post(RequestBody.create(trainJson, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(train).execute()) {
                assertEquals(201, r.code(), r.body().string());
            }

            String evalJson = "{\"model\":\"iris-j48\",\"dataset\":\"iris\"}";
            Request eval = new Request.Builder()
                    .url(client.getOrigin() + "/evaluate")
                    .post(RequestBody.create(evalJson, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(eval).execute()) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body2 = MAPPER.readTree(text);
                double accuracy = body2.get("accuracy").asDouble();
                assertTrue(accuracy > 0.9, "expected accuracy > 0.9, got " + accuracy);
                assertEquals(150, body2.get("numInstances").asInt());
                assertEquals("test_set", body2.get("method").asText());
            }
        });
    }

    @Test
    public void evaluate_cross_validation_uses_all_instances() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIrisAndTrainJ48(client);

            String evalJson = "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"method\":\"cross_validation\",\"folds\":10}";
            Request eval = new Request.Builder()
                    .url(client.getOrigin() + "/evaluate")
                    .post(RequestBody.create(evalJson, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(eval).execute()) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertEquals("cross_validation", body.get("method").asText());
                assertEquals(10, body.get("folds").asInt());
                assertEquals(150, body.get("numInstances").asInt());
                assertTrue(body.get("accuracy").asDouble() > 0.8,
                        "expected CV accuracy > 0.8, got " + body.get("accuracy").asDouble());
            }
        });
    }

    @Test
    public void evaluate_percentage_split_holds_out_test_portion() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIrisAndTrainJ48(client);

            String evalJson = "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"method\":\"percentage_split\",\"trainPercent\":66.0}";
            Request eval = new Request.Builder()
                    .url(client.getOrigin() + "/evaluate")
                    .post(RequestBody.create(evalJson, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(eval).execute()) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertEquals("percentage_split", body.get("method").asText());
                // 150 * 66% = 99 train -> 51 test instances held out
                assertEquals(51, body.get("numInstances").asInt());
            }
        });
    }

    @Test
    public void evaluate_percentage_split_preserve_order_skips_shuffle() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIrisAndTrainJ48(client);

            // iris.arff is sorted by class (50 setosa, 50 versicolor, 50 virginica). With
            // preserveOrder=true the held-out 34% (last 51 rows) is almost entirely the
            // final class, so a model trained on the first 99 (which never see much of
            // that class) scores far worse than a normal (shuffled/stratified) split.
            String evalJson =
                    "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"method\":\"percentage_split\","
                            + "\"trainPercent\":66.0,\"preserveOrder\":true}";
            Request eval = new Request.Builder()
                    .url(client.getOrigin() + "/evaluate")
                    .post(RequestBody.create(evalJson, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(eval).execute()) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertEquals("percentage_split", body.get("method").asText());
                assertTrue(body.get("preserveOrder").asBoolean());
                assertEquals(51, body.get("numInstances").asInt());
                assertTrue(body.get("accuracy").asDouble() < 0.5,
                        "expected order-preserved split accuracy < 0.5 (unseen class), got "
                                + body.get("accuracy").asDouble());
            }
        });
    }

    @Test
    public void evaluate_rejects_unknown_method() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            uploadIrisAndTrainJ48(client);

            String evalJson = "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"method\":\"bogus\"}";
            Request eval = new Request.Builder()
                    .url(client.getOrigin() + "/evaluate")
                    .post(RequestBody.create(evalJson, MediaType.parse("application/json")))
                    .build();
            try (Response r = client.getOkHttp().newCall(eval).execute()) {
                String text = r.body().string();
                assertEquals(400, r.code(), text);
                assertEquals("INVALID_EVAL_METHOD", MAPPER.readTree(text).get("code").asText());
            }
        });
    }

    private void uploadIrisAndTrainJ48(io.javalin.testtools.HttpClient client) throws Exception {
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
        String trainJson = "{\"dataset\":\"iris\",\"algorithm\":\"weka.classifiers.trees.J48\",\"modelName\":\"iris-j48\"}";
        Request train = new Request.Builder()
                .url(client.getOrigin() + "/train")
                .post(RequestBody.create(trainJson, MediaType.parse("application/json")))
                .build();
        try (Response r = client.getOkHttp().newCall(train).execute()) {
            assertEquals(201, r.code(), r.body().string());
        }
    }
}
