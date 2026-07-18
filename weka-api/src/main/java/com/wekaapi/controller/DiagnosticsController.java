package com.wekaapi.controller;

import com.wekaapi.dto.DiagnosticsRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.service.DiagnosticsService;
import io.javalin.http.Context;

public class DiagnosticsController {

    private final DiagnosticsService service;

    public DiagnosticsController(DiagnosticsService service) {
        this.service = service;
    }

    public void errors(Context ctx) {
        ctx.json(service.errors(body(ctx)));
    }

    public void thresholdCurve(Context ctx) {
        ctx.json(service.thresholdCurve(body(ctx)));
    }

    public void marginCurve(Context ctx) {
        ctx.json(service.marginCurve(body(ctx)));
    }

    public void costCurve(Context ctx) {
        ctx.json(service.costCurve(body(ctx)));
    }

    public void calibration(Context ctx) {
        ctx.json(service.calibration(body(ctx)));
    }

    public void residuals(Context ctx) {
        ctx.json(service.residuals(body(ctx)));
    }

    public void prCurve(Context ctx) {
        ctx.json(service.prCurve(body(ctx)));
    }

    private static DiagnosticsRequest body(Context ctx) {
        try {
            return ctx.bodyAsClass(DiagnosticsRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
    }
}
