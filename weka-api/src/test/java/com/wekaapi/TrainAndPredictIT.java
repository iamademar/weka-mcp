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

public class TrainAndPredictIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void full_train_and_predict_happy_path() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            byte[] iris;
            try (InputStream in = getClass().getResourceAsStream("/iris.arff")) {
                assertNotNull(in, "iris.arff classpath resource missing");
                iris = in.readAllBytes();
            }

            MultipartBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("name", "iris")
                    .addFormDataPart("file", "iris.arff",
                            RequestBody.create(iris, MediaType.parse("text/plain")))
                    .build();

            Request uploadReq = new Request.Builder()
                    .url(client.getOrigin() + "/datasets")
                    .post(body)
                    .build();
            try (Response upload = client.getOkHttp().newCall(uploadReq).execute()) {
                assertEquals(201, upload.code(), "upload failed: " + upload.body().string());
            }

            String trainBody = """
                    {"dataset":"iris","algorithm":"weka.classifiers.trees.J48","modelName":"iris-j48"}
                    """;
            Request trainReq = new Request.Builder()
                    .url(client.getOrigin() + "/train")
                    .post(RequestBody.create(trainBody, MediaType.parse("application/json")))
                    .build();
            try (Response train = client.getOkHttp().newCall(trainReq).execute()) {
                String text = train.body().string();
                assertEquals(201, train.code(), "train failed: " + text);
                JsonNode tj = MAPPER.readTree(text);
                assertEquals("iris-j48", tj.get("modelName").asText());
            }

            String predictBody = """
                    {"model":"iris-j48","instances":[
                      {"sepallength":5.1,"sepalwidth":3.5,"petallength":1.4,"petalwidth":0.2},
                      {"sepallength":6.7,"sepalwidth":3.0,"petallength":5.2,"petalwidth":2.3}
                    ]}
                    """;
            Request predictReq = new Request.Builder()
                    .url(client.getOrigin() + "/predict")
                    .post(RequestBody.create(predictBody, MediaType.parse("application/json")))
                    .build();
            try (Response predict = client.getOkHttp().newCall(predictReq).execute()) {
                assertEquals(200, predict.code());
                JsonNode pj = MAPPER.readTree(predict.body().string());
                JsonNode preds = pj.get("predictions");
                assertEquals(2, preds.size());
                for (JsonNode p : preds) {
                    String predicted = p.get("predictedClass").asText();
                    assertTrue(predicted.startsWith("Iris-"),
                            "expected iris label, got: " + predicted);
                    double sum = 0.0;
                    JsonNode dist = p.get("distribution");
                    assertNotNull(dist);
                    var it = dist.fields();
                    while (it.hasNext()) sum += it.next().getValue().asDouble();
                    assertEquals(1.0, sum, 0.01, "distribution should sum to ~1.0");
                }
            }
        });
    }
}
