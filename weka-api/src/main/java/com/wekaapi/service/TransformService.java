package com.wekaapi.service;

import com.wekaapi.config.Config;
import com.wekaapi.dto.FilterSpec;
import com.wekaapi.dto.TransformRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.util.AttributeTypes;
import com.wekaapi.util.SamplingUtil;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;
import weka.core.converters.ArffSaver;
import weka.core.converters.CSVSaver;
import weka.filters.Filter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TransformService {

    static final String ALLOWED_PREFIX = "weka.filters.";

    private final Config config;
    private final DatasetService datasetService;

    public TransformService(Config config, DatasetService datasetService) {
        this.config = config;
        this.datasetService = datasetService;
    }

    public Map<String, Object> transform(TransformRequest req) {
        if (req == null) throw new ApiException(400, "BAD_REQUEST", "missing request body");
        if (req.dataset == null || req.dataset.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");
        if (req.outputName == null || req.outputName.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "outputName is required");
        if (req.filters == null || req.filters.isEmpty())
            throw new ApiException(400, "BAD_REQUEST", "filters must be a non-empty array");

        DatasetService.validateName(req.outputName);

        String format = (req.format == null || req.format.isBlank())
                ? "arff"
                : req.format.toLowerCase(Locale.ROOT);
        if (!format.equals("arff") && !format.equals("csv")) {
            throw new ApiException(400, "INVALID_FORMAT", "format must be arff or csv");
        }

        Instances data = datasetService.load(req.dataset);
        List<String> applied = new ArrayList<>();
        data = applyFilters(data, req.filters, applied);

        Path target = config.dataDir.resolve(req.outputName + "." + format);
        try {
            if ("arff".equals(format)) {
                ArffSaver saver = new ArffSaver();
                saver.setInstances(data);
                saver.setFile(target.toFile());
                saver.writeBatch();
            } else {
                CSVSaver saver = new CSVSaver();
                saver.setInstances(data);
                saver.setFile(target.toFile());
                saver.writeBatch();
            }
        } catch (Exception e) {
            throw new ApiException(500, "INTERNAL_ERROR",
                    "failed to write transformed dataset: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", req.outputName);
        out.put("format", format);
        out.put("path", target.getFileName().toString());
        out.put("numInstances", data.numInstances());
        out.put("numAttributes", data.numAttributes());
        out.put("filtersApplied", applied);
        return out;
    }

    public Map<String, Object> preview(TransformRequest req, int head, long seed) {
        if (req == null) throw new ApiException(400, "BAD_REQUEST", "missing request body");
        if (req.dataset == null || req.dataset.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");
        if (req.filters == null || req.filters.isEmpty())
            throw new ApiException(400, "BAD_REQUEST", "filters must be a non-empty array");

        Instances data = datasetService.load(req.dataset);
        int totalInstances = data.numInstances();
        List<String> applied = new ArrayList<>();
        data = applyFilters(data, req.filters, applied);
        int afterTotal = data.numInstances();

        int wanted = (head <= 0) ? 20 : Math.min(head, 200);
        int s = SamplingUtil.clampSampleSize(wanted);
        int[] indices = SamplingUtil.sampleIndices(afterTotal, s, seed);

        List<Map<String, Object>> attrs = new ArrayList<>(data.numAttributes());
        for (int i = 0; i < data.numAttributes(); i++) {
            Attribute a = data.attribute(i);
            Map<String, Object> attr = new LinkedHashMap<>();
            attr.put("name", a.name());
            attr.put("type", AttributeTypes.of(a));
            if (a.isNominal()) {
                List<String> values = new ArrayList<>(a.numValues());
                for (int v = 0; v < a.numValues(); v++) values.add(a.value(v));
                attr.put("values", values);
            }
            attrs.add(attr);
        }

        List<Map<String, Object>> rows = new ArrayList<>(indices.length);
        for (int idx : indices) {
            Instance inst = data.instance(idx);
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 0; i < data.numAttributes(); i++) {
                Attribute a = data.attribute(i);
                if (inst.isMissing(i)) {
                    row.put(a.name(), null);
                } else if (a.isNominal()) {
                    row.put(a.name(), a.value((int) inst.value(i)));
                } else if (a.isString()) {
                    row.put(a.name(), inst.stringValue(i));
                } else {
                    row.put(a.name(), inst.value(i));
                }
            }
            rows.add(row);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", req.dataset);
        out.put("numInstances", afterTotal);
        out.put("totalInstances", totalInstances);
        out.put("sampled", rows.size());
        out.put("numAttributes", data.numAttributes());
        out.put("attributes", attrs);
        out.put("filtersApplied", applied);
        out.put("head", rows);
        return out;
    }

    private static Instances applyFilters(Instances data, List<FilterSpec> filters, List<String> applied) {
        for (FilterSpec spec : filters) {
            Filter filter = buildFilter(spec);
            try {
                filter.setInputFormat(data);
                data = Filter.useFilter(data, filter);
            } catch (Exception e) {
                throw new ApiException(422, "TRANSFORM_FAILED",
                        "filter " + spec.filter + " failed: " + e.getMessage(), e);
            }
            applied.add(spec.filter);
        }
        return data;
    }

    public static Filter buildFilter(FilterSpec spec) {
        if (spec == null || spec.filter == null || spec.filter.isBlank()) {
            throw new ApiException(400, "INVALID_FILTER", "filter classname is required");
        }
        if (!spec.filter.startsWith(ALLOWED_PREFIX)) {
            throw new ApiException(400, "INVALID_FILTER",
                    "filter must start with '" + ALLOWED_PREFIX + "': " + spec.filter);
        }
        String[] options = (spec.options == null) ? new String[0] : spec.options.toArray(new String[0]);
        try {
            return (Filter) Utils.forName(Filter.class, spec.filter, options);
        } catch (Exception e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof ClassNotFoundException) {
                throw new ApiException(400, "INVALID_FILTER", "unknown filter: " + spec.filter);
            }
            throw new ApiException(400, "INVALID_FILTER",
                    "failed to instantiate filter " + spec.filter + ": " + cause.getMessage(), e);
        }
    }
}
