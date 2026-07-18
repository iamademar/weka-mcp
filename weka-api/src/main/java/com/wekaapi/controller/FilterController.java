package com.wekaapi.controller;

import com.wekaapi.error.ApiException;
import com.wekaapi.service.FilterMetadataService;
import io.javalin.http.Context;
import weka.core.ClassDiscovery;
import weka.filters.Filter;
import weka.filters.SupervisedFilter;
import weka.filters.UnsupervisedFilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class FilterController {

    private final FilterMetadataService metadataService;
    private Map<String, List<Map<String, Object>>> cached;

    public FilterController(FilterMetadataService metadataService) {
        this.metadataService = metadataService;
    }

    public synchronized void get(Context ctx) {
        if (cached == null) {
            cached = discover();
        }
        ctx.json(Map.of("filters", cached));
    }

    public void metadata(Context ctx) {
        String fqn = ctx.queryParam("filter");
        if (fqn == null || fqn.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "query param 'filter' is required");
        }
        ctx.json(metadataService.metadata(fqn));
    }

    private static Map<String, List<Map<String, Object>>> discover() {
        java.util.LinkedHashSet<String> classes = new java.util.LinkedHashSet<>();
        for (String pkg : new String[]{
                "weka.filters",
                "weka.filters.unsupervised.attribute",
                "weka.filters.unsupervised.instance",
                "weka.filters.supervised.attribute",
                "weka.filters.supervised.instance"
        }) {
            try {
                @SuppressWarnings("unchecked")
                List<String> found = ClassDiscovery.find(Filter.class.getName(), pkg);
                if (found != null) classes.addAll(found);
            } catch (Throwable ignored) {}
        }
        classes.addAll(fallbackFilters());

        Map<String, List<Map<String, Object>>> grouped = new TreeMap<>();
        for (String fqn : classes) {
            if (!fqn.startsWith("weka.filters.")) continue;
            String tail = fqn.substring("weka.filters.".length());
            int dot = tail.indexOf('.');
            int dot2 = (dot < 0) ? -1 : tail.indexOf('.', dot + 1);
            String groupKey = (dot < 0) ? "misc" : (dot2 < 0 ? tail.substring(0, dot) : tail.substring(0, dot2));

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("classname", fqn);
            entry.put("supervised", deriveSupervised(fqn));
            entry.put("level", deriveLevel(tail, dot, dot2));
            grouped.computeIfAbsent(groupKey, k -> new ArrayList<>()).add(entry);
        }
        for (List<Map<String, Object>> v : grouped.values()) {
            v.sort((a, b) -> ((String) a.get("classname")).compareTo((String) b.get("classname")));
        }
        return new LinkedHashMap<>(grouped);
    }

    private static Boolean deriveSupervised(String fqn) {
        try {
            Class<?> raw = Class.forName(fqn);
            if (SupervisedFilter.class.isAssignableFrom(raw)) return true;
            if (UnsupervisedFilter.class.isAssignableFrom(raw)) return false;
            return null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String deriveLevel(String tail, int dot, int dot2) {
        if (dot < 0) return null;
        // tail = family.<level>.<...>, e.g. "unsupervised.attribute.Normalize"
        if (dot2 < 0) return null;
        String level = tail.substring(dot + 1, dot2);
        if ("attribute".equals(level) || "instance".equals(level)) return level;
        return null;
    }

    private static List<String> fallbackFilters() {
        List<String> defaults = List.of(
                "weka.filters.unsupervised.attribute.Normalize",
                "weka.filters.unsupervised.attribute.Standardize",
                "weka.filters.unsupervised.attribute.Discretize",
                "weka.filters.unsupervised.attribute.ReplaceMissingValues",
                "weka.filters.unsupervised.attribute.Remove",
                "weka.filters.unsupervised.attribute.RemoveUseless",
                "weka.filters.unsupervised.attribute.PrincipalComponents",
                "weka.filters.unsupervised.attribute.NominalToBinary",
                "weka.filters.unsupervised.attribute.NumericToNominal",
                "weka.filters.unsupervised.attribute.AddNoise",
                "weka.filters.unsupervised.instance.Randomize",
                "weka.filters.unsupervised.instance.RemoveDuplicates",
                "weka.filters.unsupervised.instance.Resample",
                "weka.filters.unsupervised.instance.RemovePercentage",
                "weka.filters.supervised.attribute.Discretize",
                "weka.filters.supervised.attribute.NominalToBinary",
                "weka.filters.supervised.attribute.AttributeSelection",
                "weka.filters.supervised.instance.Resample",
                "weka.filters.supervised.instance.SpreadSubsample",
                "weka.filters.supervised.instance.StratifiedRemoveFolds"
        );
        List<String> filtered = new ArrayList<>();
        for (String fqn : defaults) {
            try {
                Class.forName(fqn);
                filtered.add(fqn);
            } catch (ClassNotFoundException ignored) {}
        }
        return filtered;
    }
}
