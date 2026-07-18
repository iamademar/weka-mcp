package com.wekaapi.controller;

import com.wekaapi.dto.SearchTrainRequest;
import com.wekaapi.dto.TrainRequest;
import com.wekaapi.dto.UpdateRequest;
import com.wekaapi.error.ApiException;
import com.wekaapi.service.TrainingService;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;

public class TrainController {

    private final TrainingService service;

    public TrainController(TrainingService service) {
        this.service = service;
    }

    public void post(Context ctx) {
        TrainRequest req;
        try {
            req = ctx.bodyAsClass(TrainRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.status(HttpStatus.CREATED).json(service.train(req));
    }

    public void search(Context ctx) {
        SearchTrainRequest req;
        try {
            req = ctx.bodyAsClass(SearchTrainRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.status(HttpStatus.CREATED).json(service.searchTrain(req));
    }

    public void update(Context ctx) {
        UpdateRequest req;
        try {
            req = ctx.bodyAsClass(UpdateRequest.class);
        } catch (Exception e) {
            throw new ApiException(400, "BAD_REQUEST", "invalid JSON: " + e.getMessage(), e);
        }
        ctx.json(service.update(req));
    }
}
