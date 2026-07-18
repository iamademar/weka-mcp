package com.wekaapi.service;

import com.wekaapi.dto.FilterSpec;
import com.wekaapi.dto.SearchTrainRequest;
import com.wekaapi.dto.TrainRequest;
import com.wekaapi.dto.UpdateRequest;
import com.wekaapi.error.ApiException;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.UpdateableClassifier;
import weka.classifiers.meta.CVParameterSelection;
import weka.classifiers.meta.FilteredClassifier;
import weka.core.Instance;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.MultiFilter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class TrainingService {

    private static final String ALLOWED_PREFIX = "weka.classifiers.";

    private final DatasetService datasetService;
    private final ModelService modelService;

    public TrainingService(DatasetService datasetService, ModelService modelService) {
        this.datasetService = datasetService;
        this.modelService = modelService;
    }

    public Map<String, Object> train(TrainRequest req) {
        if (req == null) throw new ApiException(400, "BAD_REQUEST", "missing request body");
        if (req.dataset == null || req.dataset.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");
        if (req.algorithm == null || req.algorithm.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "algorithm is required");
        if (req.modelName == null || req.modelName.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "modelName is required");

        DatasetService.validateName(req.modelName);

        if (!req.algorithm.startsWith(ALLOWED_PREFIX)) {
            throw new ApiException(400, "INVALID_ALGORITHM",
                    "algorithm must start with '" + ALLOWED_PREFIX + "': " + req.algorithm);
        }

        Class<?> raw;
        try {
            raw = Class.forName(req.algorithm);
        } catch (ClassNotFoundException e) {
            throw new ApiException(400, "INVALID_ALGORITHM", "unknown algorithm: " + req.algorithm);
        }
        if (!Classifier.class.isAssignableFrom(raw)) {
            throw new ApiException(400, "INVALID_ALGORITHM",
                    "algorithm is not a Weka Classifier: " + req.algorithm);
        }

        String[] options = (req.options == null) ? new String[0] : req.options.toArray(new String[0]);

        Classifier baseClassifier;
        try {
            baseClassifier = AbstractClassifier.forName(req.algorithm, options);
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_ALGORITHM",
                    "failed to instantiate classifier: " + e.getMessage(), e);
        }

        Instances data = datasetService.load(req.dataset);
        int classIndex = (req.classIndex == null || req.classIndex == -1)
                ? data.numAttributes() - 1
                : req.classIndex;
        if (classIndex < 0 || classIndex >= data.numAttributes()) {
            throw new ApiException(400, "BAD_REQUEST", "classIndex out of range: " + classIndex);
        }
        data.setClassIndex(classIndex);

        Classifier classifier = baseClassifier;
        List<String> appliedFilters = new ArrayList<>();
        if (req.filters != null && !req.filters.isEmpty()) {
            classifier = wrapWithFilters(baseClassifier, req.filters, appliedFilters);
        }

        long start = System.currentTimeMillis();
        try {
            classifier.buildClassifier(data);
        } catch (Exception e) {
            throw new ApiException(422, "TRAINING_FAILED",
                    "training failed: " + e.getMessage(), e);
        }
        long elapsed = System.currentTimeMillis() - start;

        modelService.save(req.modelName, classifier, data);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("modelName", req.modelName);
        response.put("algorithm", req.algorithm);
        response.put("trainedOn", req.dataset);
        response.put("trainingTimeMs", elapsed);
        response.put("summary", classifier.toString());
        if (!appliedFilters.isEmpty()) {
            response.put("filters", appliedFilters);
        }
        return response;
    }

    /** Trains a classifier wrapped in CVParameterSelection to tune hyperparameters, then persists the tuned model. */
    public Map<String, Object> searchTrain(SearchTrainRequest req) {
        if (req == null) throw new ApiException(400, "BAD_REQUEST", "missing request body");
        if (req.dataset == null || req.dataset.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");
        if (req.algorithm == null || req.algorithm.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "algorithm is required");
        if (req.modelName == null || req.modelName.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "modelName is required");
        if (req.cvParameters == null || req.cvParameters.isEmpty())
            throw new ApiException(400, "BAD_REQUEST", "cvParameters must be a non-empty array");
        if (!req.algorithm.startsWith(ALLOWED_PREFIX))
            throw new ApiException(400, "INVALID_ALGORITHM",
                    "algorithm must start with '" + ALLOWED_PREFIX + "': " + req.algorithm);
        DatasetService.validateName(req.modelName);

        String[] options = (req.options == null) ? new String[0] : req.options.toArray(new String[0]);
        Classifier base;
        try {
            base = AbstractClassifier.forName(req.algorithm, options);
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_ALGORITHM",
                    "failed to instantiate classifier: " + e.getMessage(), e);
        }

        Instances data = datasetService.load(req.dataset);
        int classIndex = (req.classIndex == null || req.classIndex == -1)
                ? data.numAttributes() - 1
                : req.classIndex;
        if (classIndex < 0 || classIndex >= data.numAttributes())
            throw new ApiException(400, "BAD_REQUEST", "classIndex out of range: " + classIndex);
        data.setClassIndex(classIndex);

        CVParameterSelection search = new CVParameterSelection();
        search.setClassifier(base);
        if (req.folds != null) {
            try {
                search.setNumFolds(req.folds);
            } catch (Exception e) {
                throw new ApiException(400, "BAD_REQUEST", "invalid folds: " + e.getMessage(), e);
            }
        }
        try {
            for (String p : req.cvParameters) search.addCVParameter(p);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid cvParameter spec: " + e.getMessage(), e);
        }

        long start = System.currentTimeMillis();
        try {
            search.buildClassifier(data);
        } catch (Exception e) {
            throw new ApiException(422, "SEARCH_FAILED", "hyperparameter search failed: " + e.getMessage(), e);
        }
        long elapsed = System.currentTimeMillis() - start;

        modelService.save(req.modelName, search, data);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelName", req.modelName);
        out.put("algorithm", req.algorithm);
        out.put("trainedOn", req.dataset);
        out.put("trainingTimeMs", elapsed);
        try {
            out.put("bestOptions", List.of(search.getBestClassifierOptions()));
        } catch (Exception ignored) {}
        out.put("summary", search.toString());
        return out;
    }

    /** Incrementally updates a stored UpdateableClassifier with new instances, then re-persists it. */
    public Map<String, Object> update(UpdateRequest req) {
        if (req == null || req.model == null || req.model.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "model is required");
        if (req.instances == null || req.instances.isEmpty())
            throw new ApiException(400, "BAD_REQUEST", "instances must be a non-empty array");

        ModelService.LoadedModel loaded = modelService.load(req.model);
        Classifier classifier = loaded.classifier();
        if (!(classifier instanceof UpdateableClassifier updateable)) {
            throw new ApiException(422, "NOT_UPDATEABLE",
                    classifier.getClass().getName() + " is not an UpdateableClassifier");
        }
        Instances header = loaded.header();

        int updated = 0;
        try {
            for (Map<String, Object> input : req.instances) {
                Instance inst = PredictionService.buildInstance(header, input);
                updateable.updateClassifier(inst);
                updated++;
            }
        } catch (Exception e) {
            throw new ApiException(422, "TRAINING_FAILED",
                    "incremental update failed after " + updated + " instances: " + e.getMessage(), e);
        }

        modelService.save(req.model, classifier, header);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", req.model);
        out.put("instancesApplied", updated);
        out.put("summary", classifier.toString());
        return out;
    }

    private static Classifier wrapWithFilters(Classifier base, List<FilterSpec> specs, List<String> applied) {
        Filter filter;
        if (specs.size() == 1) {
            filter = TransformService.buildFilter(specs.get(0));
            applied.add(specs.get(0).filter);
        } else {
            Filter[] chain = new Filter[specs.size()];
            for (int i = 0; i < specs.size(); i++) {
                chain[i] = TransformService.buildFilter(specs.get(i));
                applied.add(specs.get(i).filter);
            }
            MultiFilter mf = new MultiFilter();
            mf.setFilters(chain);
            filter = mf;
        }
        FilteredClassifier fc = new FilteredClassifier();
        fc.setFilter(filter);
        fc.setClassifier(base);
        return fc;
    }
}
