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

/** Covers hyperparameter search (B7), incremental update (B9), and learning curve (B6). */
public class TrainingExtrasIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.parse("application/json");

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void cv_parameter_search_tunes_ibk_k() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");

            // search IBk's K from 1..5 in 5 steps
            String json = "{\"dataset\":\"iris\",\"algorithm\":\"weka.classifiers.lazy.IBk\","
                    + "\"modelName\":\"iris-ibk-tuned\",\"cvParameters\":[\"K 1 5 5\"],\"folds\":5}";
            try (Response r = post(client, "/train/search", json)) {
                String text = r.body().string();
                assertEquals(201, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertTrue(body.has("bestOptions"), text);
            }

            // tuned model should be usable for prediction
            try (Response r = post(client, "/predict/dataset", "{\"model\":\"iris-ibk-tuned\",\"dataset\":\"iris\"}")) {
                assertEquals(200, r.code(), r.body().string());
            }
        });
    }

    @Test
    public void incremental_update_on_naive_bayes_updateable() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");
            train(client, "iris", "weka.classifiers.bayes.NaiveBayesUpdateable", "iris-nbu");

            String json = "{\"model\":\"iris-nbu\",\"instances\":[{"
                    + "\"sepallength\":5.1,\"sepalwidth\":3.5,\"petallength\":1.4,\"petalwidth\":0.2,\"class\":\"Iris-setosa\"}]}";
            try (Response r = post(client, "/train/update", json)) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertEquals(1, body.get("instancesApplied").asInt());
            }
        });
    }

    @Test
    public void incremental_update_rejects_non_updateable() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");
            train(client, "iris", "weka.classifiers.trees.J48", "iris-j48");

            String json = "{\"model\":\"iris-j48\",\"instances\":[{"
                    + "\"sepallength\":5.1,\"sepalwidth\":3.5,\"petallength\":1.4,\"petalwidth\":0.2,\"class\":\"Iris-setosa\"}]}";
            try (Response r = post(client, "/train/update", json)) {
                String text = r.body().string();
                assertEquals(422, r.code(), text);
                assertEquals("NOT_UPDATEABLE", MAPPER.readTree(text).get("code").asText());
            }
        });
    }

    @Test
    public void learning_curve_returns_point_per_fraction() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");
            train(client, "iris", "weka.classifiers.trees.J48", "iris-j48");

            String json = "{\"model\":\"iris-j48\",\"dataset\":\"iris\",\"fractions\":[0.25,0.5,1.0],\"folds\":5}";
            try (Response r = post(client, "/diagnostics/learning-curve", json)) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode body = MAPPER.readTree(text);
                assertEquals("accuracy", body.get("metric").asText());
                assertEquals(3, body.get("curve").size());
                assertTrue(body.get("curve").get(0).has("trainSize"));
            }
        });
    }

    @Test
    public void num_slots_survives_training_into_cross_validation() {
        // The path that 504'd in production. RandomForest builds its trees single-threaded by
        // default, and cross-validation re-trains a fresh copy per fold, so a 10-fold CV is
        // ~1000 sequential tree builds in one blocking request. `-num-slots` parallelises it.
        // This asserts the option is ACCEPTED and the model it produces is still evaluable —
        // the option is serialized into the saved model, so AbstractClassifier.makeCopy
        // carries it through into the CV that Evaluation runs.
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");

            String json = "{\"dataset\":\"iris\",\"algorithm\":\"weka.classifiers.trees.RandomForest\","
                    + "\"modelName\":\"iris-rf-parallel\",\"options\":[\"-num-slots\",\"2\"]}";
            try (Response r = post(client, "/train", json)) {
                assertEquals(201, r.code(), r.body().string());
            }

            String evalJson = "{\"model\":\"iris-rf-parallel\",\"dataset\":\"iris\","
                    + "\"method\":\"cross_validation\",\"folds\":10}";
            try (Response r = post(client, "/evaluate", evalJson)) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                assertTrue(MAPPER.readTree(text).has("accuracy"), text);
            }
        });
    }

    @Test
    public void boosting_rejects_num_slots() {
        // Locks in why the caller's options map is keyed by WEKA CLASS, not by model family:
        // AdaBoostM1 sits in the same "ensemble" family as RandomForest but does NOT accept
        // `-num-slots`. Sending it anyway is a 400, not a slow model — so a family-keyed map
        // would fail every boosted candidate on every run.
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            upload(client, "iris.arff", "iris");

            String json = "{\"dataset\":\"iris\",\"algorithm\":\"weka.classifiers.meta.AdaBoostM1\","
                    + "\"modelName\":\"iris-adaboost\",\"options\":[\"-num-slots\",\"2\"]}";
            try (Response r = post(client, "/train", json)) {
                String text = r.body().string();
                assertEquals(400, r.code(), text);
                assertEquals("INVALID_ALGORITHM", MAPPER.readTree(text).get("code").asText(), text);
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
