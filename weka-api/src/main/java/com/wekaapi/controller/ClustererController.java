package com.wekaapi.controller;

import com.wekaapi.dto.ClusterAssignRequest;
import com.wekaapi.dto.ClusterEvaluateRequest;
import com.wekaapi.dto.ClusterTrainRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.service.ClusteringService;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import weka.clusterers.Clusterer;
import weka.core.ClassDiscovery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

public class ClustererController {

    private final ClusteringService service;
    private List<String> cachedClusterers;

    public ClustererController(ClusteringService service) {
        this.service = service;
    }

    public synchronized void discover(Context ctx) {
        if (cachedClusterers == null) {
            LinkedHashSet<String> classes = new LinkedHashSet<>();
            try {
                List<String> found = ClassDiscovery.find(Clusterer.class.getName(), "weka.clusterers");
                if (found != null) classes.addAll(found);
            } catch (Throwable ignored) {}
            for (String fqn : List.of(
                    "weka.clusterers.SimpleKMeans",
                    "weka.clusterers.EM",
                    "weka.clusterers.Cobweb",
                    "weka.clusterers.FarthestFirst",
                    "weka.clusterers.HierarchicalClusterer",
                    "weka.clusterers.Canopy",
                    "weka.clusterers.MakeDensityBasedClusterer")) {
                try {
                    Class.forName(fqn);
                    classes.add(fqn);
                } catch (ClassNotFoundException ignored) {}
            }
            List<String> sorted = new ArrayList<>(classes);
            Collections.sort(sorted);
            cachedClusterers = sorted;
        }
        ctx.json(Map.of("clusterers", cachedClusterers));
    }

    public void train(Context ctx) {
        ClusterTrainRequest req;
        try {
            req = ctx.bodyAsClass(ClusterTrainRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.status(HttpStatus.CREATED).json(service.train(req));
    }

    public void assign(Context ctx) {
        ClusterAssignRequest req;
        try {
            req = ctx.bodyAsClass(ClusterAssignRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.json(service.assign(req));
    }

    public void evaluate(Context ctx) {
        ClusterEvaluateRequest req;
        try {
            req = ctx.bodyAsClass(ClusterEvaluateRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.json(service.evaluate(req));
    }
}
