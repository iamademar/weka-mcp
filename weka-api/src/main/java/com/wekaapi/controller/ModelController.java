package com.wekaapi.controller;

import com.wekaapi.error.ApiException;
import com.wekaapi.service.DatasetService;
import com.wekaapi.service.ModelService;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;
import weka.core.Instances;

import java.io.InputStream;
import java.util.Map;

public class ModelController {

    private final ModelService service;
    private final DatasetService datasetService;

    public ModelController(ModelService service, DatasetService datasetService) {
        this.service = service;
        this.datasetService = datasetService;
    }

    public void list(Context ctx) {
        ctx.json(Map.of("models", service.list()));
    }

    public void get(Context ctx) {
        String name = ctx.pathParam("name");
        ctx.json(service.describe(name));
    }

    public void delete(Context ctx) {
        String name = ctx.pathParam("name");
        service.delete(name);
        ctx.status(HttpStatus.NO_CONTENT);
    }

    public void drawableType(Context ctx) {
        ctx.json(service.drawableType(ctx.pathParam("name")));
    }

    public void tree(Context ctx) {
        ctx.json(service.graph(ctx.pathParam("name"), "tree"));
    }

    public void graph(Context ctx) {
        ctx.json(service.graph(ctx.pathParam("name"), "graph"));
    }

    public void download(Context ctx) {
        String name = ctx.pathParam("name");
        byte[] bytes = service.modelBytes(name);
        ctx.contentType("application/octet-stream");
        ctx.header("Content-Disposition", "attachment; filename=\"" + name + ".model\"");
        ctx.result(bytes);
    }

    public void importModel(Context ctx) {
        String name = ctx.pathParam("name");
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new ApiException(400, "BAD_REQUEST", "missing 'file' field in multipart upload");
        }
        String dataset = ctx.formParam("dataset");
        if (dataset == null || dataset.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "form param 'dataset' is required to capture the model header");
        }
        Instances header = datasetService.load(dataset);
        byte[] bytes;
        try (InputStream in = file.content()) {
            bytes = in.readAllBytes();
        } catch (java.io.IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", "failed to read upload: " + e.getMessage(), e);
        }
        ctx.status(HttpStatus.CREATED).json(service.importModel(name, bytes, header));
    }
}
