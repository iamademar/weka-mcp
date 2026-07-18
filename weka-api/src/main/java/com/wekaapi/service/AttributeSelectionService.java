package com.wekaapi.service;

import com.wekaapi.dto.AttributeSelectionRequest;
import com.wekaapi.error.ApiException;
import weka.attributeSelection.ASEvaluation;
import weka.attributeSelection.ASSearch;
import weka.attributeSelection.AttributeEvaluator;
import weka.attributeSelection.AttributeSelection;
import weka.attributeSelection.Ranker;
import weka.attributeSelection.SubsetEvaluator;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AttributeSelectionService {

    private static final String EVAL_PREFIX = "weka.attributeSelection.";

    private final DatasetService datasetService;

    public AttributeSelectionService(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public Map<String, Object> select(AttributeSelectionRequest req) {
        if (req == null) throw new ApiException(400, "BAD_REQUEST", "missing request body");
        if (req.dataset == null || req.dataset.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");
        if (req.evaluator == null || req.evaluator.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "evaluator is required");
        if (req.search == null || req.search.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "search is required");
        if (!req.evaluator.startsWith(EVAL_PREFIX))
            throw new ApiException(400, "INVALID_EVALUATOR",
                    "evaluator must start with '" + EVAL_PREFIX + "': " + req.evaluator);
        if (!req.search.startsWith(EVAL_PREFIX))
            throw new ApiException(400, "INVALID_SEARCH",
                    "search must start with '" + EVAL_PREFIX + "': " + req.search);

        Instances data = datasetService.load(req.dataset);
        int classIndex = (req.classIndex == null || req.classIndex == -1)
                ? data.numAttributes() - 1
                : req.classIndex;
        if (classIndex < 0 || classIndex >= data.numAttributes()) {
            throw new ApiException(400, "BAD_REQUEST", "classIndex out of range: " + classIndex);
        }
        data.setClassIndex(classIndex);

        ASEvaluation evaluator;
        try {
            evaluator = ASEvaluation.forName(req.evaluator, toArray(req.evaluatorOptions));
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_EVALUATOR",
                    "failed to instantiate evaluator: " + e.getMessage(), e);
        }
        ASSearch search;
        try {
            search = ASSearch.forName(req.search, toArray(req.searchOptions));
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_SEARCH",
                    "failed to instantiate search: " + e.getMessage(), e);
        }

        // Ranker only works with attribute (single) evaluators; subset evaluators need a subset search.
        boolean isRanker = search instanceof Ranker;
        boolean isSubsetEval = evaluator instanceof SubsetEvaluator;
        boolean isAttributeEval = evaluator instanceof AttributeEvaluator;
        if (isRanker && isSubsetEval) {
            throw new ApiException(400, "INCOMPATIBLE_SEARCH",
                    "Ranker requires an attribute evaluator (e.g. InfoGainAttributeEval), not a subset evaluator");
        }
        if (!isRanker && isAttributeEval && !isSubsetEval) {
            throw new ApiException(400, "INCOMPATIBLE_SEARCH",
                    "an attribute evaluator requires the Ranker search");
        }

        AttributeSelection as = new AttributeSelection();
        as.setEvaluator(evaluator);
        as.setSearch(search);
        try {
            as.SelectAttributes(data);
        } catch (Exception e) {
            throw new ApiException(422, "SELECTION_FAILED",
                    "attribute selection failed: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", req.dataset);
        out.put("evaluator", req.evaluator);
        out.put("search", req.search);

        try {
            int[] selected = as.selectedAttributes();
            List<Integer> indices = new ArrayList<>(selected.length);
            List<String> names = new ArrayList<>(selected.length);
            for (int idx : selected) {
                indices.add(idx);
                names.add(data.attribute(idx).name());
            }
            out.put("selectedIndices", indices);
            out.put("selectedAttributes", names);
        } catch (Exception ignored) {
            // Ranker without a cutoff exposes rankings rather than a selected subset.
        }

        if (isRanker) {
            try {
                double[][] ranked = as.rankedAttributes();
                List<Map<String, Object>> ranking = new ArrayList<>(ranked.length);
                for (double[] row : ranked) {
                    int attrIdx = (int) row[0];
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("index", attrIdx);
                    entry.put("attribute", data.attribute(attrIdx).name());
                    entry.put("merit", round(row[1]));
                    ranking.add(entry);
                }
                out.put("ranking", ranking);
            } catch (Exception ignored) {}
        }

        try {
            out.put("summary", as.toResultsString());
        } catch (Exception ignored) {}

        return out;
    }

    private static String[] toArray(List<String> opts) {
        return (opts == null) ? new String[0] : opts.toArray(new String[0]);
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
