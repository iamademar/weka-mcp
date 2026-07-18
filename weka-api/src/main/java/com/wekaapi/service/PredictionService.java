package com.wekaapi.service;

import com.wekaapi.dto.PredictDatasetRequest;
import com.wekaapi.dto.PredictRequest;
import com.wekaapi.error.ApiException;
import weka.classifiers.Classifier;
import weka.core.Attribute;
import weka.core.DenseInstance;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class PredictionService {

    private final ModelService modelService;
    private final DatasetService datasetService;

    public PredictionService(ModelService modelService, DatasetService datasetService) {
        this.modelService = modelService;
        this.datasetService = datasetService;
    }

    public Map<String, Object> predict(PredictRequest req) {
        if (req == null || req.model == null || req.model.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "model is required");
        }
        if (req.instances == null || req.instances.isEmpty()) {
            throw new ApiException(400, "BAD_REQUEST", "instances must be a non-empty array");
        }

        ModelService.LoadedModel loaded = modelService.load(req.model);
        Classifier classifier = loaded.classifier();
        Instances header = loaded.header();
        Attribute classAttr = header.classAttribute();
        boolean numericClass = classAttr.isNumeric();

        List<Map<String, Object>> results = new ArrayList<>(req.instances.size());
        for (Map<String, Object> input : req.instances) {
            Instance inst = buildInstance(header, input);
            try {
                Map<String, Object> entry = new LinkedHashMap<>();
                if (numericClass) {
                    double pred = classifier.classifyInstance(inst);
                    entry.put("predictedClass", Utils.isMissingValue(pred) ? null : String.valueOf(pred));
                } else {
                    double[] dist = classifier.distributionForInstance(inst);
                    int bestIdx = Utils.maxIndex(dist);
                    entry.put("predictedClass", classAttr.value(bestIdx));
                    Map<String, Double> distribution = new LinkedHashMap<>();
                    for (int i = 0; i < dist.length; i++) {
                        distribution.put(classAttr.value(i), dist[i]);
                    }
                    entry.put("distribution", distribution);
                }
                results.add(entry);
            } catch (Exception e) {
                throw new ApiException(422, "PREDICTION_FAILED",
                        "prediction failed: " + e.getMessage(), e);
            }
        }

        return Map.of(
                "model", req.model,
                "predictions", results
        );
    }

    /** Scores every instance in a stored dataset against a model. */
    public Map<String, Object> predictDataset(PredictDatasetRequest req) {
        if (req == null || req.model == null || req.model.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "model is required");
        }
        if (req.dataset == null || req.dataset.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");
        }
        boolean includeDist = req.includeDistribution != null && req.includeDistribution;

        ModelService.LoadedModel loaded = modelService.load(req.model);
        Classifier classifier = loaded.classifier();
        Instances header = loaded.header();
        Attribute classAttr = header.classAttribute();
        boolean numericClass = classAttr.isNumeric();

        Instances data = datasetService.load(req.dataset);
        data.setClassIndex(header.classIndex());
        if (!header.equalHeaders(data)) {
            throw new ApiException(422, "HEADER_MISMATCH",
                    "dataset schema is incompatible with the model: " + header.equalHeadersMsg(data));
        }

        List<Map<String, Object>> results = new ArrayList<>(data.numInstances());
        for (int i = 0; i < data.numInstances(); i++) {
            Instance inst = data.instance(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i);
            try {
                if (numericClass) {
                    double pred = classifier.classifyInstance(inst);
                    entry.put("predictedClass", Utils.isMissingValue(pred) ? null : pred);
                } else {
                    double[] dist = classifier.distributionForInstance(inst);
                    entry.put("predictedClass", classAttr.value(Utils.maxIndex(dist)));
                    if (includeDist) {
                        Map<String, Double> distribution = new LinkedHashMap<>();
                        for (int j = 0; j < dist.length; j++) distribution.put(classAttr.value(j), dist[j]);
                        entry.put("distribution", distribution);
                    }
                }
            } catch (Exception e) {
                throw new ApiException(422, "PREDICTION_FAILED",
                        "prediction failed at row " + i + ": " + e.getMessage(), e);
            }
            results.add(entry);
        }

        return Map.of(
                "model", req.model,
                "dataset", req.dataset,
                "numInstances", data.numInstances(),
                "predictions", results
        );
    }

    static Instance buildInstance(Instances header, Map<String, Object> input) {
        double[] values = new double[header.numAttributes()];
        for (int i = 0; i < header.numAttributes(); i++) {
            Attribute attr = header.attribute(i);
            Object raw = input.get(attr.name());
            if (raw == null) {
                values[i] = Utils.missingValue();
                continue;
            }
            if (attr.isNumeric()) {
                if (raw instanceof Number n) {
                    values[i] = n.doubleValue();
                } else {
                    try {
                        values[i] = Double.parseDouble(raw.toString());
                    } catch (NumberFormatException e) {
                        throw new ApiException(400, "BAD_REQUEST",
                                "attribute '" + attr.name() + "' expected numeric, got: " + raw);
                    }
                }
            } else if (attr.isNominal()) {
                int idx = attr.indexOfValue(raw.toString());
                if (idx < 0) {
                    throw new ApiException(400, "BAD_REQUEST",
                            "attribute '" + attr.name() + "' value not in domain: " + raw);
                }
                values[i] = idx;
            } else if (attr.isString()) {
                values[i] = attr.addStringValue(raw.toString());
            } else {
                values[i] = Utils.missingValue();
            }
        }
        DenseInstance inst = new DenseInstance(1.0, values);
        inst.setDataset(header);
        return inst;
    }
}
