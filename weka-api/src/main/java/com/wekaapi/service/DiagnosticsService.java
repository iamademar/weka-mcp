package com.wekaapi.service;

import com.wekaapi.dto.DiagnosticsRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.util.SamplingUtil;
import weka.classifiers.evaluation.CostCurve;
import weka.classifiers.evaluation.MarginCurve;
import weka.classifiers.evaluation.NominalPrediction;
import weka.classifiers.evaluation.NumericPrediction;
import weka.classifiers.evaluation.Prediction;
import weka.classifiers.evaluation.ThresholdCurve;
import weka.core.Attribute;
import weka.core.Instance;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class DiagnosticsService {

    private final EvaluationService evaluationService;

    public DiagnosticsService(EvaluationService evaluationService) {
        this.evaluationService = evaluationService;
    }

    public Map<String, Object> errors(DiagnosticsRequest req) {
        validate(req);
        EvaluationService.EvaluationResult r = evaluationService.runEvaluation(req.model, req.dataset);
        List<Prediction> preds = predictionsList(r);
        Attribute classAttr = r.loaded().header().classAttribute();
        boolean nominal = classAttr.isNominal();

        int total = preds.size();
        int sampleSize = SamplingUtil.clampSampleSize(req.sample == null ? SamplingUtil.DEFAULT_SAMPLE : req.sample);
        long seed = req.seed == null ? SamplingUtil.DEFAULT_SEED : req.seed;
        int[] indices = SamplingUtil.sampleIndices(total, sampleSize, seed);

        List<Map<String, Object>> points = new ArrayList<>(indices.length);
        for (int idx : indices) {
            Prediction p = preds.get(idx);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("index", idx);
            if (nominal) {
                double actual = p.actual();
                double predicted = p.predicted();
                point.put("actual", labelOrMissing(classAttr, actual));
                point.put("predicted", labelOrMissing(classAttr, predicted));
                point.put("correct", actual == predicted);
            } else {
                point.put("actual", p.actual());
                point.put("predicted", p.predicted());
                point.put("error", Math.abs(p.predicted() - p.actual()));
            }
            points.add(point);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", req.model);
        out.put("dataset", req.dataset);
        out.put("classType", nominal ? "nominal" : "numeric");
        out.put("totalInstances", total);
        out.put("sampled", points.size());
        out.put("points", points);
        return out;
    }

    public Map<String, Object> thresholdCurve(DiagnosticsRequest req) {
        validate(req);
        EvaluationService.EvaluationResult r = evaluationService.runEvaluation(req.model, req.dataset);
        Attribute classAttr = r.loaded().header().classAttribute();
        requireNominal(classAttr);
        int classIndex = resolveClassValueIndex(classAttr, req.classValue);

        ThresholdCurve tc = new ThresholdCurve();
        Instances curve;
        try {
            curve = tc.getCurve(predictionsVector(r), classIndex);
        } catch (Exception e) {
            throw new ApiException(422, "EVALUATION_FAILED",
                    "threshold curve failed: " + e.getMessage(), e);
        }

        List<String> wanted = List.of("Threshold", "True Positive Rate", "False Positive Rate",
                "Precision", "Recall", "F-Measure", "True Positives", "False Positives",
                "True Negatives", "False Negatives");
        List<Map<String, Object>> points = projectCurve(curve, wanted);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", req.model);
        out.put("dataset", req.dataset);
        out.put("classValue", classAttr.value(classIndex));
        out.put("auc", round(ThresholdCurve.getROCArea(curve)));
        out.put("points", points);
        return out;
    }

    public Map<String, Object> marginCurve(DiagnosticsRequest req) {
        validate(req);
        EvaluationService.EvaluationResult r = evaluationService.runEvaluation(req.model, req.dataset);
        Attribute classAttr = r.loaded().header().classAttribute();
        requireNominal(classAttr);

        MarginCurve mc = new MarginCurve();
        Instances curve;
        try {
            curve = mc.getCurve(predictionsVector(r));
        } catch (Exception e) {
            throw new ApiException(422, "EVALUATION_FAILED",
                    "margin curve failed: " + e.getMessage(), e);
        }
        List<Map<String, Object>> points = projectCurve(curve,
                List.of("Margin", "Current", "Cumulative"));
        return Map.of(
                "model", req.model,
                "dataset", req.dataset,
                "points", points
        );
    }

    public Map<String, Object> costCurve(DiagnosticsRequest req) {
        validate(req);
        EvaluationService.EvaluationResult r = evaluationService.runEvaluation(req.model, req.dataset);
        Attribute classAttr = r.loaded().header().classAttribute();
        requireNominal(classAttr);
        int classIndex = resolveClassValueIndex(classAttr, req.classValue);

        CostCurve cc = new CostCurve();
        Instances curve;
        try {
            curve = cc.getCurve(predictionsVector(r), classIndex);
        } catch (Exception e) {
            throw new ApiException(422, "EVALUATION_FAILED",
                    "cost curve failed: " + e.getMessage(), e);
        }
        List<Map<String, Object>> points = projectCurve(curve,
                List.of("Probability Cost Function", "Normalized Expected Cost"));
        return Map.of(
                "model", req.model,
                "dataset", req.dataset,
                "classValue", classAttr.value(classIndex),
                "points", points
        );
    }

    public Map<String, Object> calibration(DiagnosticsRequest req) {
        validate(req);
        EvaluationService.EvaluationResult r = evaluationService.runEvaluation(req.model, req.dataset);
        Attribute classAttr = r.loaded().header().classAttribute();
        requireNominal(classAttr);
        int classIndex = resolveClassValueIndex(classAttr, req.classValue);
        int bins = (req.bins == null || req.bins <= 0) ? 10 : Math.min(req.bins, 100);

        int[] count = new int[bins];
        double[] sumPred = new double[bins];
        double[] sumActual = new double[bins];
        double brierSum = 0.0;
        int total = 0;

        List<Prediction> preds = predictionsList(r);
        for (Prediction p : preds) {
            if (!(p instanceof NominalPrediction np)) continue;
            double[] dist = np.distribution();
            if (dist == null || dist.length <= classIndex) continue;
            double predProb = dist[classIndex];
            double actualOutcome = (np.actual() == classIndex) ? 1.0 : 0.0;
            int bin = (int) Math.floor(predProb * bins);
            if (bin >= bins) bin = bins - 1;
            if (bin < 0) bin = 0;
            count[bin]++;
            sumPred[bin] += predProb;
            sumActual[bin] += actualOutcome;
            brierSum += (predProb - actualOutcome) * (predProb - actualOutcome);
            total++;
        }

        List<Map<String, Object>> binList = new ArrayList<>(bins);
        for (int i = 0; i < bins; i++) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("bin", i);
            b.put("lo", round((double) i / bins));
            b.put("hi", round((double) (i + 1) / bins));
            b.put("count", count[i]);
            b.put("predictedProb", count[i] == 0 ? null : round(sumPred[i] / count[i]));
            b.put("observedFraction", count[i] == 0 ? null : round(sumActual[i] / count[i]));
            binList.add(b);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", req.model);
        out.put("dataset", req.dataset);
        out.put("classValue", classAttr.value(classIndex));
        out.put("brierScore", total == 0 ? null : round(brierSum / total));
        out.put("totalInstances", total);
        out.put("bins", binList);
        return out;
    }

    public Map<String, Object> residuals(DiagnosticsRequest req) {
        validate(req);
        EvaluationService.EvaluationResult r = evaluationService.runEvaluation(req.model, req.dataset);
        Attribute classAttr = r.loaded().header().classAttribute();
        if (!classAttr.isNumeric()) {
            throw new ApiException(400, "NOT_NUMERIC_CLASS",
                    "residuals require a numeric class attribute");
        }

        List<Prediction> preds = predictionsList(r);
        int total = preds.size();
        int sampleSize = SamplingUtil.clampSampleSize(req.sample == null ? SamplingUtil.DEFAULT_SAMPLE : req.sample);
        long seed = req.seed == null ? SamplingUtil.DEFAULT_SEED : req.seed;
        int[] indices = SamplingUtil.sampleIndices(total, sampleSize, seed);

        List<Map<String, Object>> points = new ArrayList<>(indices.length);
        for (int idx : indices) {
            Prediction p = preds.get(idx);
            Map<String, Object> point = new LinkedHashMap<>();
            point.put("index", idx);
            point.put("actual", p.actual());
            point.put("predicted", p.predicted());
            point.put("residual", round(p.actual() - p.predicted()));
            points.add(point);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", req.model);
        out.put("dataset", req.dataset);
        out.put("totalInstances", total);
        out.put("sampled", points.size());
        out.put("points", points);
        return out;
    }

    public Map<String, Object> prCurve(DiagnosticsRequest req) {
        validate(req);
        EvaluationService.EvaluationResult r = evaluationService.runEvaluation(req.model, req.dataset);
        Attribute classAttr = r.loaded().header().classAttribute();
        requireNominal(classAttr);
        int classIndex = resolveClassValueIndex(classAttr, req.classValue);

        ThresholdCurve tc = new ThresholdCurve();
        Instances curve;
        try {
            curve = tc.getCurve(predictionsVector(r), classIndex);
        } catch (Exception e) {
            throw new ApiException(422, "EVALUATION_FAILED",
                    "precision-recall curve failed: " + e.getMessage(), e);
        }
        List<Map<String, Object>> points = projectCurve(curve,
                List.of("Recall", "Precision", "Threshold"));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", req.model);
        out.put("dataset", req.dataset);
        out.put("classValue", classAttr.value(classIndex));
        out.put("auprc", round(ThresholdCurve.getPRCArea(curve)));
        out.put("points", points);
        return out;
    }

    private static void validate(DiagnosticsRequest req) {
        if (req == null) throw new ApiException(400, "BAD_REQUEST", "missing request body");
        if (req.model == null || req.model.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "model is required");
        if (req.dataset == null || req.dataset.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");
    }

    private static void requireNominal(Attribute classAttr) {
        if (!classAttr.isNominal()) {
            throw new ApiException(400, "NOT_NOMINAL_CLASS",
                    "this diagnostic requires a nominal class attribute");
        }
    }

    private static int resolveClassValueIndex(Attribute classAttr, String classValue) {
        if (classValue == null || classValue.isBlank()) return 0;
        int idx = classAttr.indexOfValue(classValue);
        if (idx < 0) {
            throw new ApiException(400, "INVALID_CLASS_VALUE",
                    "class value not in domain: " + classValue);
        }
        return idx;
    }

    private static List<Prediction> predictionsList(EvaluationService.EvaluationResult r) {
        @SuppressWarnings("rawtypes")
        java.util.ArrayList raw = r.evaluation().predictions();
        if (raw == null) return List.of();
        List<Prediction> out = new ArrayList<>(raw.size());
        for (Object p : raw) out.add((Prediction) p);
        return out;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static java.util.ArrayList<Prediction> predictionsVector(EvaluationService.EvaluationResult r) {
        java.util.ArrayList raw = r.evaluation().predictions();
        if (raw == null) return new java.util.ArrayList<>();
        return (java.util.ArrayList<Prediction>) raw;
    }

    private static List<Map<String, Object>> projectCurve(Instances curve, List<String> wantedAttrs) {
        List<Integer> indices = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String name : wantedAttrs) {
            Attribute a = curve.attribute(name);
            if (a != null) {
                indices.add(a.index());
                names.add(name);
            }
        }
        List<Map<String, Object>> out = new ArrayList<>(curve.numInstances());
        for (int i = 0; i < curve.numInstances(); i++) {
            Instance row = curve.instance(i);
            Map<String, Object> point = new LinkedHashMap<>();
            for (int k = 0; k < indices.size(); k++) {
                point.put(camelCase(names.get(k)), round(row.value(indices.get(k))));
            }
            out.add(point);
        }
        return out;
    }

    private static String camelCase(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        boolean first = true;
        for (char c : s.toCharArray()) {
            if (c == ' ' || c == '-' || c == '_') {
                upper = true;
            } else if (first) {
                sb.append(Character.toLowerCase(c));
                first = false;
            } else if (upper) {
                sb.append(Character.toUpperCase(c));
                upper = false;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String labelOrMissing(Attribute classAttr, double v) {
        if (Double.isNaN(v)) return null;
        int idx = (int) v;
        if (idx < 0 || idx >= classAttr.numValues()) return null;
        return classAttr.value(idx);
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
