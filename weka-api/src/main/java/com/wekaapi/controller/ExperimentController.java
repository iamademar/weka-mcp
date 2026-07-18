package com.wekaapi.controller;

import com.wekaapi.dto.ExperimentRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.service.ExperimentService;
import io.javalin.http.Context;

public class ExperimentController {

    private final ExperimentService service;

    public ExperimentController(ExperimentService service) {
        this.service = service;
    }

    public void run(Context ctx) {
        ExperimentRequest req;
        try {
            req = ctx.bodyAsClass(ExperimentRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.json(service.run(req));
    }
}
