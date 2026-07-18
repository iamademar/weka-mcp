package com.wekaapi.service;

import com.wekaapi.dto.AssociateRequest;
import com.wekaapi.error.ApiException;
import weka.associations.AbstractAssociator;
import weka.associations.AssociationRule;
import weka.associations.AssociationRulesProducer;
import weka.associations.Associator;
import weka.associations.Item;
import weka.core.Instances;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AssociationService {

    private static final String ALLOWED_PREFIX = "weka.associations.";

    private final DatasetService datasetService;

    public AssociationService(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public Map<String, Object> associate(AssociateRequest req) {
        if (req == null) throw new ApiException(400, "BAD_REQUEST", "missing request body");
        if (req.dataset == null || req.dataset.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");
        if (req.algorithm == null || req.algorithm.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "algorithm is required");
        if (!req.algorithm.startsWith(ALLOWED_PREFIX))
            throw new ApiException(400, "INVALID_ASSOCIATOR",
                    "algorithm must start with '" + ALLOWED_PREFIX + "': " + req.algorithm);

        String[] options = (req.options == null) ? new String[0] : req.options.toArray(new String[0]);
        Associator associator;
        try {
            associator = AbstractAssociator.forName(req.algorithm, options);
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_ASSOCIATOR",
                    "failed to instantiate associator: " + e.getMessage(), e);
        }

        Instances data = datasetService.load(req.dataset);
        data.setClassIndex(-1); // association mining is unsupervised
        // Most associators (Apriori) require all-nominal data.
        for (int i = 0; i < data.numAttributes(); i++) {
            if (data.attribute(i).isNumeric()) {
                throw new ApiException(422, "REQUIRES_NOMINAL",
                        "attribute '" + data.attribute(i).name() + "' is numeric; association mining needs nominal "
                                + "attributes. Discretize first via /transform "
                                + "(weka.filters.unsupervised.attribute.Discretize).");
            }
        }

        try {
            associator.buildAssociations(data);
        } catch (Exception e) {
            throw new ApiException(422, "ASSOCIATION_FAILED",
                    "association mining failed: " + e.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataset", req.dataset);
        out.put("algorithm", req.algorithm);

        if (associator instanceof AssociationRulesProducer producer && producer.canProduceRules()) {
            List<Map<String, Object>> rules = new ArrayList<>();
            for (AssociationRule rule : producer.getAssociationRules().getRules()) {
                Map<String, Object> r = new LinkedHashMap<>();
                r.put("premise", itemStrings(rule.getPremise()));
                r.put("consequence", itemStrings(rule.getConsequence()));
                r.put("premiseSupport", rule.getPremiseSupport());
                r.put("consequenceSupport", rule.getConsequenceSupport());
                r.put("totalSupport", rule.getTotalSupport());
                r.put(rule.getPrimaryMetricName(), round(rule.getPrimaryMetricValue()));
                try {
                    String[] names = rule.getMetricNamesForRule();
                    double[] values = rule.getMetricValuesForRule();
                    Map<String, Object> metrics = new LinkedHashMap<>();
                    for (int i = 0; i < names.length && i < values.length; i++) {
                        metrics.put(names[i], round(values[i]));
                    }
                    r.put("metrics", metrics);
                } catch (Exception ignored) {}
                rules.add(r);
            }
            out.put("rules", rules);
        }

        out.put("summary", associator.toString());
        return out;
    }

    private static List<String> itemStrings(java.util.Collection<Item> items) {
        List<String> out = new ArrayList<>(items.size());
        for (Item item : items) out.add(item.toString());
        return out;
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
