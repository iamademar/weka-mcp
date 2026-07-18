package com.wekaapi.service;

import com.wekaapi.config.Config;
import com.wekaapi.error.ApiException;
import weka.core.Attribute;
import weka.core.Instances;
import weka.core.converters.ArffLoader;
import weka.core.converters.CSVLoader;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public class DatasetService {

    private final Config config;

    public DatasetService(Config config) {
        this.config = config;
        try {
            Files.createDirectories(config.dataDir);
        } catch (IOException e) {
            throw new RuntimeException("could not create data dir: " + config.dataDir, e);
        }
    }

    public record DatasetInfo(String name, String format, long sizeBytes, Path path) {}

    public Map<String, Object> upload(InputStream content, long size, String uploadedFilename, String desiredName) {
        if (size > config.maxUploadBytes) {
            throw new ApiException(413, "UPLOAD_TOO_LARGE",
                    "upload exceeds max size of " + (config.maxUploadBytes / (1024 * 1024)) + " MB");
        }
        String format = detectFormat(uploadedFilename);
        String base = (desiredName != null && !desiredName.isBlank()) ? desiredName : stripExt(uploadedFilename);
        validateName(base);

        Path target = config.dataDir.resolve(base + "." + format);
        try {
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", "failed to write dataset: " + e.getMessage(), e);
        }

        Instances instances = loadInstances(target, format);
        if (instances.classIndex() < 0) {
            instances.setClassIndex(instances.numAttributes() - 1);
        }

        return Map.of(
                "name", base,
                "path", target.getFileName().toString(),
                "format", format,
                "numInstances", instances.numInstances(),
                "numAttributes", instances.numAttributes(),
                "classAttribute", instances.classAttribute().name()
        );
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!Files.isDirectory(config.dataDir)) return out;
        try (Stream<Path> stream = Files.list(config.dataDir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .forEach(p -> {
                        String fileName = p.getFileName().toString();
                        String ext = extensionOf(fileName);
                        if (!ext.equals("arff") && !ext.equals("csv")) return;
                        try {
                            out.add(Map.of(
                                    "name", stripExt(fileName),
                                    "format", ext,
                                    "sizeBytes", Files.size(p)
                            ));
                        } catch (IOException ignored) {}
                    });
        } catch (IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", e.getMessage(), e);
        }
        return out;
    }

    public Map<String, Object> metadata(String name) {
        validateName(name);
        DatasetInfo info = locate(name).orElseThrow(() ->
                new ApiException(404, "DATASET_NOT_FOUND", "dataset not found: " + name));

        Instances instances = loadInstances(info.path(), info.format());
        if (instances.classIndex() < 0) {
            instances.setClassIndex(instances.numAttributes() - 1);
        }

        List<Map<String, Object>> attrs = new ArrayList<>();
        for (int i = 0; i < instances.numAttributes(); i++) {
            Attribute a = instances.attribute(i);
            Map<String, Object> attr;
            if (a.isNominal()) {
                List<String> values = new ArrayList<>(a.numValues());
                for (int v = 0; v < a.numValues(); v++) values.add(a.value(v));
                attr = Map.of("name", a.name(), "type", "nominal", "values", values);
            } else if (a.isNumeric()) {
                attr = Map.of("name", a.name(), "type", "numeric");
            } else if (a.isString()) {
                attr = Map.of("name", a.name(), "type", "string");
            } else if (a.isDate()) {
                attr = Map.of("name", a.name(), "type", "date");
            } else {
                attr = Map.of("name", a.name(), "type", "unknown");
            }
            attrs.add(attr);
        }

        return Map.of(
                "name", name,
                "format", info.format(),
                "numInstances", instances.numInstances(),
                "attributes", attrs,
                "classAttribute", instances.classAttribute().name()
        );
    }

    public void delete(String name) {
        validateName(name);
        DatasetInfo info = locate(name).orElseThrow(() ->
                new ApiException(404, "DATASET_NOT_FOUND", "dataset not found: " + name));
        try {
            Files.delete(info.path());
        } catch (IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", "failed to delete dataset: " + e.getMessage(), e);
        }
    }

    public Instances load(String name) {
        validateName(name);
        DatasetInfo info = locate(name).orElseThrow(() ->
                new ApiException(404, "DATASET_NOT_FOUND", "dataset not found: " + name));
        Instances instances = loadInstances(info.path(), info.format());
        if (instances.classIndex() < 0) {
            instances.setClassIndex(instances.numAttributes() - 1);
        }
        return instances;
    }

    public Optional<DatasetInfo> locate(String name) {
        for (String ext : new String[]{"arff", "csv"}) {
            Path p = config.dataDir.resolve(name + "." + ext);
            if (Files.isRegularFile(p)) {
                try {
                    return Optional.of(new DatasetInfo(name, ext, Files.size(p), p));
                } catch (IOException e) {
                    throw new ApiException(500, "INTERNAL_ERROR", e.getMessage(), e);
                }
            }
        }
        return Optional.empty();
    }

    private Instances loadInstances(Path path, String format) {
        try {
            if ("arff".equals(format)) {
                ArffLoader loader = new ArffLoader();
                loader.setFile(path.toFile());
                return loader.getDataSet();
            } else if ("csv".equals(format)) {
                CSVLoader loader = new CSVLoader();
                loader.setSource(path.toFile());
                return loader.getDataSet();
            } else {
                throw new ApiException(400, "INVALID_FORMAT", "unsupported format: " + format);
            }
        } catch (ApiException e) {
            throw e;
        } catch (IOException e) {
            throw new ApiException(400, "INVALID_FORMAT", "failed to parse dataset: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_FORMAT", "failed to parse dataset: " + e.getMessage(), e);
        }
    }

    public static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(400, "INVALID_NAME", "name must not be empty");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new ApiException(400, "INVALID_NAME", "name contains illegal characters");
        }
    }

    private static String detectFormat(String filename) {
        String ext = extensionOf(filename);
        if (!"arff".equals(ext) && !"csv".equals(ext)) {
            throw new ApiException(400, "INVALID_FORMAT", "unsupported file extension: " + ext);
        }
        return ext;
    }

    private static String extensionOf(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(idx + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String stripExt(String filename) {
        if (filename == null) return "";
        int idx = filename.lastIndexOf('.');
        return idx >= 0 ? filename.substring(0, idx) : filename;
    }
}
