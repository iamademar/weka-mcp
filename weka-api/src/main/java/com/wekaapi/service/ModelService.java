package com.wekaapi.service;

import com.wekaapi.config.Config;
import com.wekaapi.error.ApiException;
import weka.classifiers.Classifier;
import weka.clusterers.Clusterer;
import weka.core.Drawable;
import weka.core.Instances;
import weka.core.SerializationHelper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class ModelService {

    public static final String KIND_CLASSIFIER = "classifier";
    public static final String KIND_CLUSTERER = "clusterer";

    public record LoadedModel(Classifier classifier, Instances header) {}

    public record LoadedClusterer(Clusterer clusterer, Instances header) {}

    private final Config config;
    private final Map<String, LoadedModel> cache = new ConcurrentHashMap<>();
    private final Map<String, LoadedClusterer> clustererCache = new ConcurrentHashMap<>();

    public ModelService(Config config) {
        this.config = config;
        try {
            Files.createDirectories(config.modelsDir);
        } catch (IOException e) {
            throw new RuntimeException("could not create models dir: " + config.modelsDir, e);
        }
    }

    public void save(String name, Classifier classifier, Instances header) {
        persist(name, classifier, header, KIND_CLASSIFIER);
        cache.put(name, new LoadedModel(classifier, header));
    }

    public void saveClusterer(String name, Clusterer clusterer, Instances header) {
        persist(name, clusterer, header, KIND_CLUSTERER);
        clustererCache.put(name, new LoadedClusterer(clusterer, header));
    }

    private void persist(String name, Object model, Instances header, String kind) {
        DatasetService.validateName(name);
        try {
            SerializationHelper.write(modelPath(name).toString(), model);
            Instances emptyHeader = new Instances(header, 0);
            emptyHeader.setClassIndex(header.classIndex());
            SerializationHelper.write(headerPath(name).toString(), emptyHeader);
            Files.writeString(kindPath(name), kind, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new ApiException(500, "INTERNAL_ERROR", "failed to persist model: " + e.getMessage(), e);
        }
    }

    public LoadedModel load(String name) {
        DatasetService.validateName(name);
        LoadedModel cached = cache.get(name);
        if (cached != null) return cached;

        Path modelPath = modelPath(name);
        if (!Files.isRegularFile(modelPath)) {
            throw new ApiException(404, "MODEL_NOT_FOUND", "model not found: " + name);
        }
        String kind = kindOf(name);
        if (!KIND_CLASSIFIER.equals(kind)) {
            throw new ApiException(400, "WRONG_MODEL_KIND",
                    "'" + name + "' is a " + kind + ", not a classifier");
        }
        try {
            Object obj = SerializationHelper.read(modelPath.toString());
            if (!(obj instanceof Classifier classifier)) {
                throw new ApiException(400, "WRONG_MODEL_KIND",
                        "'" + name + "' is not a classifier");
            }
            Instances header = loadHeader(name);
            LoadedModel loaded = new LoadedModel(classifier, header);
            cache.put(name, loaded);
            return loaded;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "INTERNAL_ERROR", "failed to load model: " + e.getMessage(), e);
        }
    }

    public LoadedClusterer loadClusterer(String name) {
        DatasetService.validateName(name);
        LoadedClusterer cached = clustererCache.get(name);
        if (cached != null) return cached;

        Path modelPath = modelPath(name);
        if (!Files.isRegularFile(modelPath)) {
            throw new ApiException(404, "MODEL_NOT_FOUND", "model not found: " + name);
        }
        String kind = kindOf(name);
        if (!KIND_CLUSTERER.equals(kind)) {
            throw new ApiException(400, "WRONG_MODEL_KIND",
                    "'" + name + "' is a " + kind + ", not a clusterer");
        }
        try {
            Object obj = SerializationHelper.read(modelPath.toString());
            if (!(obj instanceof Clusterer clusterer)) {
                throw new ApiException(400, "WRONG_MODEL_KIND",
                        "'" + name + "' is not a clusterer");
            }
            Instances header = loadHeader(name);
            LoadedClusterer loaded = new LoadedClusterer(clusterer, header);
            clustererCache.put(name, loaded);
            return loaded;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(500, "INTERNAL_ERROR", "failed to load clusterer: " + e.getMessage(), e);
        }
    }

    private Instances loadHeader(String name) throws Exception {
        Path headerPath = headerPath(name);
        if (!Files.isRegularFile(headerPath)) {
            throw new ApiException(500, "INTERNAL_ERROR", "model header missing for: " + name);
        }
        return (Instances) SerializationHelper.read(headerPath.toString());
    }

    /** Reads the kind marker; defaults to classifier for models saved before kind markers existed. */
    private String kindOf(String name) {
        Path kindPath = kindPath(name);
        if (!Files.isRegularFile(kindPath)) return KIND_CLASSIFIER;
        try {
            String kind = Files.readString(kindPath, StandardCharsets.UTF_8).trim();
            return kind.isEmpty() ? KIND_CLASSIFIER : kind;
        } catch (IOException e) {
            return KIND_CLASSIFIER;
        }
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(config.modelsDir)) return out;
        try (Stream<Path> stream = Files.list(config.modelsDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".model"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String name = fileName.substring(0, fileName.length() - ".model".length());
                        try {
                            out.add(Map.of("name", name, "sizeBytes", Files.size(p), "kind", kindOf(name)));
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", e.getMessage(), e);
        }
        return out;
    }

    public Map<String, Object> describe(String name) {
        LoadedModel m = load(name);
        return Map.of(
                "name", name,
                "algorithm", m.classifier().getClass().getName(),
                "summary", m.classifier().toString()
        );
    }

    public Map<String, Object> drawableType(String name) {
        LoadedModel m = load(name);
        return Map.of(
                "name", name,
                "type", drawableTypeOf(m.classifier())
        );
    }

    public Map<String, Object> graph(String name, String expectedType) {
        LoadedModel m = load(name);
        Classifier classifier = m.classifier();
        if (!(classifier instanceof Drawable d)) {
            throw new ApiException(400, "NOT_DRAWABLE",
                    classifier.getClass().getName() + " does not implement Drawable");
        }
        String actual = drawableTypeOf(classifier);
        if (expectedType != null && !expectedType.equalsIgnoreCase(actual)) {
            throw new ApiException(400, "NOT_DRAWABLE",
                    "classifier graph type is '" + actual + "', not '" + expectedType + "'");
        }
        String graph;
        try {
            graph = d.graph();
        } catch (Exception e) {
            throw new ApiException(400, "NOT_DRAWABLE",
                    "failed to draw classifier: " + e.getMessage());
        }
        return Map.of(
                "name", name,
                "type", actual,
                "format", graphFormat(actual),
                "graph", graph
        );
    }

    private static String drawableTypeOf(Classifier classifier) {
        if (!(classifier instanceof Drawable d)) return "none";
        try {
            int t = d.graphType();
            return switch (t) {
                case Drawable.TREE -> "tree";
                case Drawable.BayesNet -> "graph";
                case Drawable.Newick -> "newick";
                default -> "none";
            };
        } catch (Exception e) {
            return "none";
        }
    }

    private static String graphFormat(String type) {
        return switch (type) {
            case "tree", "graph" -> "dot";
            case "newick" -> "newick";
            default -> "unknown";
        };
    }

    /** Raw bytes of the serialized {@code .model} file, for download/export. */
    public byte[] modelBytes(String name) {
        DatasetService.validateName(name);
        Path modelPath = modelPath(name);
        if (!Files.isRegularFile(modelPath)) {
            throw new ApiException(404, "MODEL_NOT_FOUND", "model not found: " + name);
        }
        try {
            return Files.readAllBytes(modelPath);
        } catch (IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", "failed to read model: " + e.getMessage(), e);
        }
    }

    /**
     * Imports an externally-trained classifier from serialized bytes, capturing the training header
     * from an already-uploaded dataset so the model can predict/evaluate through the normal flow.
     */
    public Map<String, Object> importModel(String name, byte[] modelData, Instances header) {
        DatasetService.validateName(name);
        Classifier classifier;
        try (java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(modelData)) {
            Object obj = SerializationHelper.read(in);
            if (!(obj instanceof Classifier c)) {
                throw new ApiException(400, "INVALID_MODEL_FILE",
                        "uploaded file does not deserialize to a Weka Classifier");
            }
            classifier = c;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_MODEL_FILE",
                    "failed to read model file: " + e.getMessage(), e);
        }
        save(name, classifier, header);
        return Map.of(
                "name", name,
                "algorithm", classifier.getClass().getName(),
                "kind", KIND_CLASSIFIER
        );
    }

    public void delete(String name) {
        DatasetService.validateName(name);
        Path modelPath = modelPath(name);
        if (!Files.isRegularFile(modelPath)) {
            throw new ApiException(404, "MODEL_NOT_FOUND", "model not found: " + name);
        }
        try {
            Files.deleteIfExists(modelPath);
            Files.deleteIfExists(headerPath(name));
            Files.deleteIfExists(kindPath(name));
        } catch (IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", "failed to delete model: " + e.getMessage(), e);
        }
        cache.remove(name);
        clustererCache.remove(name);
    }

    private Path modelPath(String name) {
        return config.modelsDir.resolve(name + ".model");
    }

    private Path headerPath(String name) {
        return config.modelsDir.resolve(name + ".header");
    }

    private Path kindPath(String name) {
        return config.modelsDir.resolve(name + ".kind");
    }
}
