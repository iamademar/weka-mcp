package com.wekaapi.controller;

import com.wekaapi.dto.EvaluateRequest;
import com.wekaapi.dto.LearningCurveRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.service.EvaluationService;
import io.javalin.http.Context;

public class EvaluateController {

    private final EvaluationService service;

    public EvaluateController(EvaluationService service) {
        this.service = service;
    }

    public void post(Context ctx) {
        EvaluateRequest req;
        try {
            req = ctx.bodyAsClass(EvaluateRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.json(service.evaluate(req));
    }

    public void learningCurve(Context ctx) {
        LearningCurveRequest req;
        try {
            req = ctx.bodyAsClass(LearningCurveRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.json(service.learningCurve(req));
    }
}
