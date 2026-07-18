package com.wekaapi.controller;

import io.javalin.http.Context;
import weka.classifiers.Classifier;
import weka.core.ClassDiscovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class AlgorithmController {

    private Map<String, List<String>> cached;

    public synchronized void get(Context ctx) {
        if (cached == null) {
            cached = discover();
        }
        ctx.json(Map.of("classifiers", cached));
    }

    private static Map<String, List<String>> discover() {
        java.util.LinkedHashSet<String> classes = new java.util.LinkedHashSet<>();
        for (String pkg : new String[]{
                "weka.classifiers",
                "weka.classifiers.trees",
                "weka.classifiers.bayes",
                "weka.classifiers.functions",
                "weka.classifiers.lazy",
                "weka.classifiers.rules",
                "weka.classifiers.meta",
                "weka.classifiers.misc"
        }) {
            try {
                @SuppressWarnings("unchecked")
                List<String> found = ClassDiscovery.find(Classifier.class.getName(), pkg);
                if (found != null) classes.addAll(found);
            } catch (Throwable ignored) {}
        }
        classes.addAll(fallbackClassifiers());

        Map<String, List<String>> grouped = new TreeMap<>();
        for (String fqn : classes) {
            if (!fqn.startsWith("weka.classifiers.")) continue;
            String tail = fqn.substring("weka.classifiers.".length());
            int dot = tail.indexOf('.');
            String family = (dot < 0) ? "misc" : tail.substring(0, dot);
            grouped.computeIfAbsent(family, k -> new ArrayList<>()).add(fqn);
        }
        for (List<String> v : grouped.values()) Collections.sort(v);

        Map<String, List<String>> ordered = new LinkedHashMap<>(grouped);
        return ordered;
    }

    private static List<String> fallbackClassifiers() {
        List<String> defaults = List.of(
                "weka.classifiers.trees.J48",
                "weka.classifiers.trees.RandomForest",
                "weka.classifiers.trees.REPTree",
                "weka.classifiers.trees.DecisionStump",
                "weka.classifiers.bayes.NaiveBayes",
                "weka.classifiers.bayes.BayesNet",
                "weka.classifiers.functions.Logistic",
                "weka.classifiers.functions.SMO",
                "weka.classifiers.functions.MultilayerPerceptron",
                "weka.classifiers.functions.LinearRegression",
                "weka.classifiers.lazy.IBk",
                "weka.classifiers.lazy.KStar",
                "weka.classifiers.rules.JRip",
                "weka.classifiers.rules.OneR",
                "weka.classifiers.rules.ZeroR",
                "weka.classifiers.rules.PART",
                "weka.classifiers.meta.AdaBoostM1",
                "weka.classifiers.meta.Bagging",
                "weka.classifiers.meta.Vote"
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
