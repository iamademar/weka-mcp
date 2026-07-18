package com.wekaapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.javalin.testtools.JavalinTest;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class FilterMetadataIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir Path modelsDir;
    @TempDir Path dataDir;

    @Test
    public void filters_listing_has_supervised_and_level_flags() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            try (Response r = get(client, "/filters")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                JsonNode group = j.get("filters").get("unsupervised.attribute");
                assertNotNull(group);

                JsonNode normalize = null;
                for (JsonNode entry : group) {
                    if ("weka.filters.unsupervised.attribute.Normalize".equals(entry.get("classname").asText())) {
                        normalize = entry;
                        break;
                    }
                }
                assertNotNull(normalize, "Normalize entry not in listing");
                assertFalse(normalize.get("supervised").asBoolean());
                assertEquals("attribute", normalize.get("level").asText());
            }
        });
    }

    @Test
    public void metadata_returns_description_and_options() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            try (Response r = get(client,
                    "/filters/metadata?filter=weka.filters.unsupervised.attribute.Normalize")) {
                String text = r.body().string();
                assertEquals(200, r.code(), text);
                JsonNode j = MAPPER.readTree(text);
                assertEquals("weka.filters.unsupervised.attribute.Normalize", j.get("classname").asText());
                assertFalse(j.get("supervised").asBoolean());
                assertEquals("attribute", j.get("level").asText());
                assertEquals("unsupervised.attribute", j.get("family").asText());
                assertNotNull(j.get("description"));
                assertFalse(j.get("description").asText().isBlank());

                JsonNode options = j.get("options");
                assertNotNull(options);
                assertTrue(options.size() > 0, "expected option entries");
                JsonNode first = options.get(0);
                assertNotNull(first.get("name"));
                assertNotNull(first.get("synopsis"));
                assertNotNull(first.get("numArguments"));
            }
        });
    }

    @Test
    public void metadata_rejects_non_weka_filter() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            try (Response r = get(client, "/filters/metadata?filter=java.lang.Runtime")) {
                assertEquals(400, r.code());
                assertTrue(r.body().string().contains("INVALID_FILTER"));
            }
        });
    }

    @Test
    public void metadata_requires_filter_param() {
        JavalinTest.test(TestSupport.app(modelsDir, dataDir), (server, client) -> {
            try (Response r = get(client, "/filters/metadata")) {
                assertEquals(400, r.code());
                assertTrue(r.body().string().contains("BAD_REQUEST"));
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
}
