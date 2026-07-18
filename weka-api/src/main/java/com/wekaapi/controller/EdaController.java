package com.wekaapi.controller;

import com.wekaapi.error.ApiException;
import com.wekaapi.service.EdaService;
import com.wekaapi.util.QueryParams;
import com.wekaapi.util.SamplingUtil;
import io.javalin.http.Context;

import java.util.Arrays;
import java.util.List;

public class EdaController {

    private final EdaService service;

    public EdaController(EdaService service) {
        this.service = service;
    }

    public void attributeStats(Context ctx) {
        String dataset = ctx.pathParam("name");
        String attribute = ctx.queryParam("attribute");
        if (attribute == null || attribute.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "query param 'attribute' is required");
        }
        ctx.json(service.attributeStats(dataset, attribute));
    }

    public void summary(Context ctx) {
        ctx.json(service.summary(ctx.pathParam("name")));
    }

    public void histogram(Context ctx) {
        String dataset = ctx.pathParam("name");
        String attribute = ctx.queryParam("attribute");
        if (attribute == null || attribute.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "query param 'attribute' is required");
        }
        int bins = QueryParams.intOrDefault(ctx, "bins", 10, 1, 100);
        boolean groupByClass = QueryParams.boolOrDefault(ctx, "groupBy", false)
                || "class".equalsIgnoreCase(ctx.queryParam("groupBy"));
        ctx.json(service.histogram(dataset, attribute, bins, groupByClass));
    }

    public void scatter(Context ctx) {
        String dataset = ctx.pathParam("name");
        String x = ctx.queryParam("x");
        String y = ctx.queryParam("y");
        if (x == null || x.isBlank() || y == null || y.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "query params 'x' and 'y' are required");
        }
        int sample = QueryParams.intOrDefault(ctx, "sample", SamplingUtil.DEFAULT_SAMPLE, 1, SamplingUtil.MAX_SAMPLE);
        boolean jitter = QueryParams.boolOrDefault(ctx, "jitter", false);
        long seed = QueryParams.longOrDefault(ctx, "seed", SamplingUtil.DEFAULT_SEED);
        ctx.json(service.scatter(dataset, x, y, sample, jitter, seed));
    }

    public void scatterMatrix(Context ctx) {
        String dataset = ctx.pathParam("name");
        String attrs = ctx.queryParam("attributes");
        List<String> list = (attrs == null || attrs.isBlank())
                ? List.of()
                : Arrays.stream(attrs.split(",")).map(String::trim).filter(s -> !s.isBlank()).toList();
        int sample = QueryParams.intOrDefault(ctx, "sample", SamplingUtil.DEFAULT_SAMPLE, 1, SamplingUtil.MAX_SAMPLE);
        long seed = QueryParams.longOrDefault(ctx, "seed", SamplingUtil.DEFAULT_SEED);
        ctx.json(service.scatterMatrix(dataset, list, sample, seed));
    }
}
