package com.wekaapi.service;

import com.wekaapi.dto.EvaluateRequest;
import com.wekaapi.dto.LearningCurveRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.util.SamplingUtil;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.CostMatrix;
import weka.classifiers.Evaluation;
import weka.core.Attribute;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class EvaluationService {

    public record EvaluationResult(Evaluation evaluation, ModelService.LoadedModel loaded, Instances test) {}

    private final DatasetService datasetService;
    private final ModelService modelService;

    public EvaluationService(DatasetService datasetService, ModelService modelService) {
        this.datasetService = datasetService;
        this.modelService = modelService;
    }

    public Map<String, Object> evaluate(EvaluateRequest req) {
        if (req == null || req.model == null || req.model.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "model is required");
        }
        if (req.dataset == null || req.dataset.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");
        }

        String method = (req.method == null || req.method.isBlank()) ? "test_set" : req.method;
        long seed = (req.seed == null) ? SamplingUtil.DEFAULT_SEED : req.seed;

        switch (method) {
            case "test_set": {
                EvaluationResult result = runEvaluation(req.model, req.dataset);
                Map<String, Object> out = summarize(result, req.model, req.dataset);
                out.put("method", "test_set");
                if (req.costMatrix != null) {
                    addCost(out, result, req.costMatrix);
                }
                return out;
            }
            case "cross_validation": {
                int folds = (req.folds == null) ? 10 : req.folds;
                EvaluationResult result = runCrossValidation(req.model, req.dataset, folds, seed);
                Map<String, Object> out = summarize(result, req.model, req.dataset);
                out.put("method", "cross_validation");
                out.put("folds", Math.min(folds, (int) result.evaluation().numInstances()));
                out.put("seed", seed);
                return out;
            }
            case "percentage_split": {
                double trainPercent = (req.trainPercent == null) ? 66.0 : req.trainPercent;
                boolean preserveOrder = req.preserveOrder != null && req.preserveOrder;
                EvaluationResult result = runPercentageSplit(req.model, req.dataset, trainPercent, seed, preserveOrder);
                Map<String, Object> out = summarize(result, req.model, req.dataset);
                out.put("method", "percentage_split");
                out.put("trainPercent", trainPercent);
                out.put("seed", seed);
                out.put("preserveOrder", preserveOrder);
                return out;
            }
            default:
                throw new ApiException(400, "INVALID_EVAL_METHOD",
                        "method must be one of: test_set, cross_validation, percentage_split; got: " + method);
        }
    }

    /**
     * k-fold cross-validation of the stored model's <em>configuration</em>. Weka re-trains a fresh
     * copy of the classifier on each fold, so this evaluates the algorithm + options, not the
     * already-fitted parameters of the saved model.
     */
    public EvaluationResult runCrossValidation(String modelName, String datasetName, int folds, long seed) {
        ModelService.LoadedModel loaded = modelService.load(modelName);
        Instances data = datasetService.load(datasetName);
        data.setClassIndex(loaded.header().classIndex());

        if (folds < 2) {
            throw new ApiException(400, "BAD_REQUEST", "folds must be >= 2; got: " + folds);
        }
        int effectiveFolds = Math.min(folds, data.numInstances());
        if (effectiveFolds < 2) {
            throw new ApiException(400, "BAD_REQUEST",
                    "dataset has too few instances for cross-validation: " + data.numInstances());
        }
        try {
            Classifier fresh = AbstractClassifier.makeCopy(loaded.classifier());
            Evaluation eval = new Evaluation(data);
            eval.crossValidateModel(fresh, data, effectiveFolds, new Random(seed));
            return new EvaluationResult(eval, loaded, data);
        } catch (Exception e) {
            throw new ApiException(422, "EVALUATION_FAILED",
                    "cross-validation failed: " + e.getMessage(), e);
        }
    }

    /** Shuffle (unless {@code preserveOrder}), slice into train/test by {@code trainPercent},
     *  build a fresh classifier, evaluate on the held-out split. */
    public EvaluationResult runPercentageSplit(String modelName, String datasetName, double trainPercent, long seed) {
        return runPercentageSplit(modelName, datasetName, trainPercent, seed, false);
    }

    public EvaluationResult runPercentageSplit(
            String modelName, String datasetName, double trainPercent, long seed, boolean preserveOrder) {
        ModelService.LoadedModel loaded = modelService.load(modelName);
        Instances data = datasetService.load(datasetName);
        data.setClassIndex(loaded.header().classIndex());

        if (trainPercent <= 0.0 || trainPercent >= 100.0) {
            throw new ApiException(400, "BAD_REQUEST",
                    "trainPercent must be in (0, 100); got: " + trainPercent);
        }
        try {
            Instances shuffled = new Instances(data);
            if (!preserveOrder) {
                shuffled.randomize(new Random(seed));
            }
            int trainSize = (int) Math.round(shuffled.numInstances() * trainPercent / 100.0);
            int testSize = shuffled.numInstances() - trainSize;
            if (trainSize <= 0 || testSize <= 0) {
                throw new ApiException(400, "BAD_REQUEST",
                        "trainPercent yields an empty train or test split for " + shuffled.numInstances() + " instances");
            }
            Instances train = new Instances(shuffled, 0, trainSize);
            Instances test = new Instances(shuffled, trainSize, testSize);

            Classifier fresh = AbstractClassifier.makeCopy(loaded.classifier());
            fresh.buildClassifier(train);
            Evaluation eval = new Evaluation(train);
            eval.evaluateModel(fresh, test);
            return new EvaluationResult(eval, loaded, test);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(422, "EVALUATION_FAILED",
                    "percentage split failed: " + e.getMessage(), e);
        }
    }

    public EvaluationResult runEvaluation(String modelName, String datasetName) {
        ModelService.LoadedModel loaded = modelService.load(modelName);
        Instances test = datasetService.load(datasetName);
        test.setClassIndex(loaded.header().classIndex());
        try {
            Evaluation eval = new Evaluation(loaded.header());
            eval.evaluateModel(loaded.classifier(), test);
            return new EvaluationResult(eval, loaded, test);
        } catch (Exception e) {
            throw new ApiException(422, "EVALUATION_FAILED",
                    "evaluation failed: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> summarize(EvaluationResult r, String modelName, String datasetName) {
        Evaluation eval = r.evaluation();
        Attribute classAttr = r.loaded().header().classAttribute();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", modelName);
        out.put("dataset", datasetName);
        out.put("numInstances", (int) eval.numInstances());
        out.put("correct", (int) eval.correct());
        out.put("incorrect", (int) eval.incorrect());
        out.put("accuracy", round(eval.pctCorrect() / 100.0));

        if (classAttr.isNominal()) {
            out.put("kappa", round(eval.kappa()));
            out.put("weightedFMeasure", round(eval.weightedFMeasure()));
            try {
                double[][] matrix = eval.confusionMatrix();
                int[][] intMatrix = new int[matrix.length][];
                for (int i = 0; i < matrix.length; i++) {
                    intMatrix[i] = new int[matrix[i].length];
                    for (int j = 0; j < matrix[i].length; j++) {
                        intMatrix[i][j] = (int) Math.round(matrix[i][j]);
                    }
                }
                out.put("confusionMatrix", intMatrix);
            } catch (Exception ignored) {}
            List<String> labels = new ArrayList<>(classAttr.numValues());
            for (int i = 0; i < classAttr.numValues(); i++) labels.add(classAttr.value(i));
            out.put("classLabels", labels);

            List<Map<String, Object>> perClass = new ArrayList<>(classAttr.numValues());
            for (int i = 0; i < classAttr.numValues(); i++) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("label", classAttr.value(i));
                m.put("tpRate", round(eval.truePositiveRate(i)));
                m.put("fpRate", round(eval.falsePositiveRate(i)));
                m.put("precision", round(eval.precision(i)));
                m.put("recall", round(eval.recall(i)));
                m.put("fMeasure", round(eval.fMeasure(i)));
                m.put("mcc", round(eval.matthewsCorrelationCoefficient(i)));
                m.put("rocArea", round(eval.areaUnderROC(i)));
                m.put("prcArea", round(eval.areaUnderPRC(i)));
                perClass.add(m);
            }
            out.put("perClass", perClass);
        } else {
            // numeric (regression) class metrics
            try {
                out.put("rmse", round(eval.rootMeanSquaredError()));
                out.put("mae", round(eval.meanAbsoluteError()));
                out.put("relativeAbsoluteError", round(eval.relativeAbsoluteError() / 100.0));
                out.put("rootRelativeSquaredError", round(eval.rootRelativeSquaredError() / 100.0));
                out.put("correlationCoefficient", round(eval.correlationCoefficient()));
            } catch (Exception ignored) {}
        }

        try {
            out.put("summary", eval.toSummaryString());
        } catch (Exception ignored) {}

        return out;
    }

    /**
     * Learning curve: for each training-set fraction, cross-validates a fresh copy of the model's
     * classifier on a stratified subsample and reports the chosen metric.
     */
    public Map<String, Object> learningCurve(LearningCurveRequest req) {
        if (req == null || req.model == null || req.model.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "model is required");
        if (req.dataset == null || req.dataset.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");

        ModelService.LoadedModel loaded = modelService.load(req.model);
        Instances full = datasetService.load(req.dataset);
        full.setClassIndex(loaded.header().classIndex());
        boolean nominal = full.classAttribute().isNominal();

        List<Double> fractions = (req.fractions == null || req.fractions.isEmpty())
                ? List.of(0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1.0)
                : req.fractions;
        int folds = (req.folds == null) ? 10 : req.folds;
        long seed = (req.seed == null) ? SamplingUtil.DEFAULT_SEED : req.seed;

        List<Map<String, Object>> curve = new ArrayList<>(fractions.size());
        try {
            for (double fraction : fractions) {
                if (fraction <= 0.0 || fraction > 1.0) {
                    throw new ApiException(400, "BAD_REQUEST", "fractions must be in (0, 1]; got: " + fraction);
                }
                Instances shuffled = new Instances(full);
                shuffled.randomize(new Random(seed));
                if (nominal) shuffled.stratify(folds);
                int subSize = Math.max(folds, (int) Math.round(shuffled.numInstances() * fraction));
                subSize = Math.min(subSize, shuffled.numInstances());
                Instances sub = new Instances(shuffled, 0, subSize);

                int effectiveFolds = Math.min(folds, sub.numInstances());
                if (effectiveFolds < 2) continue;

                Classifier fresh = AbstractClassifier.makeCopy(loaded.classifier());
                Evaluation eval = new Evaluation(sub);
                eval.crossValidateModel(fresh, sub, effectiveFolds, new Random(seed));

                Map<String, Object> point = new LinkedHashMap<>();
                point.put("fraction", round(fraction));
                point.put("trainSize", subSize);
                point.put("metric", nominal ? round(eval.pctCorrect() / 100.0) : round(eval.rootMeanSquaredError()));
                curve.add(point);
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(422, "LEARNING_CURVE_FAILED",
                    "learning curve failed: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", req.model);
        out.put("dataset", req.dataset);
        out.put("metric", nominal ? "accuracy" : "rmse");
        out.put("folds", folds);
        out.put("seed", seed);
        out.put("curve", curve);
        return out;
    }

    /** Re-evaluates the held-out set with a cost matrix and appends totalCost / avgCost. */
    private void addCost(Map<String, Object> out, EvaluationResult result, double[][] costMatrix) {
        Attribute classAttr = result.loaded().header().classAttribute();
        if (!classAttr.isNominal()) {
            throw new ApiException(400, "NOT_NOMINAL_CLASS",
                    "costMatrix requires a nominal class");
        }
        int n = classAttr.numValues();
        if (costMatrix.length != n) {
            throw new ApiException(400, "BAD_REQUEST",
                    "costMatrix must be " + n + "x" + n + " to match the class values");
        }
        CostMatrix cm = new CostMatrix(n);
        for (int i = 0; i < n; i++) {
            if (costMatrix[i] == null || costMatrix[i].length != n) {
                throw new ApiException(400, "BAD_REQUEST",
                        "costMatrix must be square (" + n + "x" + n + ")");
            }
            for (int j = 0; j < n; j++) cm.setElement(i, j, costMatrix[i][j]);
        }
        try {
            Evaluation costEval = new Evaluation(result.loaded().header(), cm);
            costEval.evaluateModel(result.loaded().classifier(), result.test());
            out.put("totalCost", round(costEval.totalCost()));
            out.put("avgCost", round(costEval.avgCost()));
        } catch (Exception e) {
            throw new ApiException(422, "EVALUATION_FAILED",
                    "cost evaluation failed: " + e.getMessage(), e);
        }
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
