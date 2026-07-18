package com.wekaapi.service;

import com.wekaapi.dto.ExperimentRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.util.SamplingUtil;
import weka.classifiers.AbstractClassifier;
import weka.classifiers.Classifier;
import weka.classifiers.Evaluation;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Lightweight Experimenter: runs a k-fold cross-validation grid over datasets × algorithms and
 * compares each algorithm against a baseline with a corrected resampled paired t-test
 * (Nadeau &amp; Bengio), the same correction Weka's PairedCorrectedTTester applies.
 */
public class ExperimentService {

    private static final String ALLOWED_PREFIX = "weka.classifiers.";
    private static final double SIGNIFICANCE = 0.05;

    private final DatasetService datasetService;

    public ExperimentService(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public Map<String, Object> run(ExperimentRequest req) {
        if (req == null) throw new ApiException(400, "BAD_REQUEST", "missing request body");
        if (req.datasets == null || req.datasets.isEmpty())
            throw new ApiException(400, "BAD_REQUEST", "datasets must be a non-empty array");
        if (req.algorithms == null || req.algorithms.isEmpty())
            throw new ApiException(400, "BAD_REQUEST", "algorithms must be a non-empty array");

        String metric = (req.metric == null || req.metric.isBlank()) ? "accuracy" : req.metric;
        if (!metric.equals("accuracy") && !metric.equals("rmse"))
            throw new ApiException(400, "BAD_REQUEST", "metric must be 'accuracy' or 'rmse'");
        int folds = (req.folds == null) ? 10 : req.folds;
        int runs = (req.runs == null) ? 10 : req.runs;
        long seed = (req.seed == null) ? SamplingUtil.DEFAULT_SEED : req.seed;
        int baselineIndex = (req.baselineIndex == null) ? 0 : req.baselineIndex;
        if (baselineIndex < 0 || baselineIndex >= req.algorithms.size())
            throw new ApiException(400, "BAD_REQUEST", "baselineIndex out of range: " + baselineIndex);
        if (folds < 2) throw new ApiException(400, "BAD_REQUEST", "folds must be >= 2");
        if (runs < 1) throw new ApiException(400, "BAD_REQUEST", "runs must be >= 1");

        List<Map<String, Object>> results = new ArrayList<>();
        try {
            for (String datasetName : req.datasets) {
                Instances data = datasetService.load(datasetName);
                data.setClassIndex(data.numAttributes() - 1);

                // per-algorithm vector of fold metrics (runs * folds entries), used for the t-test
                List<double[]> perAlgoMetrics = new ArrayList<>();
                List<double[]> perAlgoMeanStd = new ArrayList<>();
                for (ExperimentRequest.AlgorithmSpec spec : req.algorithms) {
                    double[] foldMetrics = collectFoldMetrics(spec, data, folds, runs, seed, metric);
                    perAlgoMetrics.add(foldMetrics);
                    perAlgoMeanStd.add(new double[]{mean(foldMetrics), stdDev(foldMetrics)});
                }

                double[] baseline = perAlgoMetrics.get(baselineIndex);
                double trainTestRatio = 1.0 / (folds - 1); // n2/n1 for k-fold CV
                for (int a = 0; a < req.algorithms.size(); a++) {
                    ExperimentRequest.AlgorithmSpec spec = req.algorithms.get(a);
                    double[] meanStd = perAlgoMeanStd.get(a);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("dataset", datasetName);
                    row.put("algorithm", spec.algorithm);
                    row.put("mean", round(meanStd[0]));
                    row.put("stdDev", round(meanStd[1]));
                    if (a == baselineIndex) {
                        row.put("significance", "baseline");
                    } else {
                        row.put("significance", significance(
                                baseline, perAlgoMetrics.get(a), trainTestRatio, metric));
                    }
                    results.add(row);
                }
            }
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(422, "EXPERIMENT_FAILED", "experiment failed: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("metric", metric);
        out.put("folds", folds);
        out.put("runs", runs);
        out.put("baseline", req.algorithms.get(baselineIndex).algorithm);
        out.put("results", results);
        return out;
    }

    private double[] collectFoldMetrics(ExperimentRequest.AlgorithmSpec spec, Instances data,
                                        int folds, int runs, long seed, String metric) throws Exception {
        if (spec.algorithm == null || !spec.algorithm.startsWith(ALLOWED_PREFIX))
            throw new ApiException(400, "INVALID_ALGORITHM",
                    "algorithm must start with '" + ALLOWED_PREFIX + "': " + spec.algorithm);
        String[] options = (spec.options == null) ? new String[0] : spec.options.toArray(new String[0]);

        List<Double> metrics = new ArrayList<>();
        for (int run = 0; run < runs; run++) {
            Instances randData = new Instances(data);
            Random rand = new Random(seed + run);
            randData.randomize(rand);
            if (randData.classAttribute().isNominal()) randData.stratify(folds);
            for (int f = 0; f < folds; f++) {
                Instances train = randData.trainCV(folds, f, rand);
                Instances test = randData.testCV(folds, f);
                Classifier c = AbstractClassifier.forName(spec.algorithm, options.clone());
                c.buildClassifier(train);
                Evaluation eval = new Evaluation(train);
                eval.evaluateModel(c, test);
                metrics.add(metric.equals("accuracy")
                        ? eval.pctCorrect() / 100.0
                        : eval.rootMeanSquaredError());
            }
        }
        double[] arr = new double[metrics.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = metrics.get(i);
        return arr;
    }

    /** Corrected resampled paired t-test; returns win/loss/tie relative to the baseline. */
    private String significance(double[] baseline, double[] other, double trainTestRatio, String metric) {
        int n = Math.min(baseline.length, other.length);
        if (n < 2) return "tie";
        double[] diff = new double[n];
        double meanDiff = 0.0;
        for (int i = 0; i < n; i++) {
            diff[i] = other[i] - baseline[i];
            meanDiff += diff[i];
        }
        meanDiff /= n;
        double var = 0.0;
        for (double d : diff) var += (d - meanDiff) * (d - meanDiff);
        var /= (n - 1);
        if (var <= 0.0) return "tie";
        // Nadeau & Bengio correction: variance scaled by (1/n + n2/n1)
        double corrected = var * (1.0 / n + trainTestRatio);
        double t = meanDiff / Math.sqrt(corrected);
        // two-sided ~95% critical value for moderate df
        double critical = 2.045;
        if (Math.abs(t) < critical) return "tie";
        boolean otherBetter = metric.equals("accuracy") ? (meanDiff > 0) : (meanDiff < 0);
        return otherBetter ? "win" : "loss";
    }

    private static double mean(double[] a) {
        double s = 0.0;
        for (double v : a) s += v;
        return a.length == 0 ? 0.0 : s / a.length;
    }

    private static double stdDev(double[] a) {
        if (a.length < 2) return 0.0;
        double m = mean(a);
        double s = 0.0;
        for (double v : a) s += (v - m) * (v - m);
        return Math.sqrt(s / (a.length - 1));
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
