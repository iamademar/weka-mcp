package com.wekaapi.controller;

import com.wekaapi.dto.AttributeSelectionRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.service.AttributeSelectionService;
import io.javalin.http.Context;
import weka.attributeSelection.ASEvaluation;
import weka.attributeSelection.ASSearch;
import weka.core.ClassDiscovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class AttributeSelectionController {

    private final AttributeSelectionService service;
    private List<String> cachedEvaluators;
    private List<String> cachedSearches;

    public AttributeSelectionController(AttributeSelectionService service) {
        this.service = service;
    }

    public synchronized void evaluators(Context ctx) {
        if (cachedEvaluators == null) {
            cachedEvaluators = discover(ASEvaluation.class.getName(), "weka.attributeSelection",
                    List.of(
                            "weka.attributeSelection.CfsSubsetEval",
                            "weka.attributeSelection.InfoGainAttributeEval",
                            "weka.attributeSelection.GainRatioAttributeEval",
                            "weka.attributeSelection.CorrelationAttributeEval",
                            "weka.attributeSelection.ReliefFAttributeEval",
                            "weka.attributeSelection.WrapperSubsetEval",
                            "weka.attributeSelection.PrincipalComponents"));
        }
        ctx.json(Map.of("evaluators", cachedEvaluators));
    }

    public synchronized void searches(Context ctx) {
        if (cachedSearches == null) {
            cachedSearches = discover(ASSearch.class.getName(), "weka.attributeSelection",
                    List.of(
                            "weka.attributeSelection.BestFirst",
                            "weka.attributeSelection.GreedyStepwise",
                            "weka.attributeSelection.Ranker"));
        }
        ctx.json(Map.of("searches", cachedSearches));
    }

    public void select(Context ctx) {
        AttributeSelectionRequest req;
        try {
            req = ctx.bodyAsClass(AttributeSelectionRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.json(service.select(req));
    }

    private static List<String> discover(String iface, String pkg, List<String> fallback) {
        LinkedHashSet<String> classes = new LinkedHashSet<>();
        try {
            List<String> found = ClassDiscovery.find(iface, pkg);
            if (found != null) classes.addAll(found);
        } catch (Throwable ignored) {}
        for (String fqn : fallback) {
            try {
                Class.forName(fqn);
                classes.add(fqn);
            } catch (ClassNotFoundException ignored) {}
        }
        List<String> sorted = new ArrayList<>(classes);
        Collections.sort(sorted);
        return sorted;
    }
}
