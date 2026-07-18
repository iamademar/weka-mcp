package com.wekaapi.controller;

import com.wekaapi.dto.TransformRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.service.TransformService;
import com.wekaapi.util.QueryParams;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

public class TransformController {

    private final TransformService service;

    public TransformController(TransformService service) {
        this.service = service;
    }

    public void post(Context ctx) {
        TransformRequest req = parseBody(ctx);
        ctx.status(HttpStatus.CREATED).json(service.transform(req));
    }

    public void preview(Context ctx) {
        TransformRequest req = parseBody(ctx);
        int head = QueryParams.intOrDefault(ctx, "head", 20, 1, 200);
        long seed = QueryParams.longOrDefault(ctx, "seed", 42L);
        ctx.json(service.preview(req, head, seed));
    }

    private static TransformRequest parseBody(Context ctx) {
        try {
            return ctx.bodyAsClass(TransformRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
    }
}
