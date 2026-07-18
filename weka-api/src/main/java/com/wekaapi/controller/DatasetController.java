package com.wekaapi.controller;

import com.wekaapi.error.ApiException;
import com.wekaapi.service.DatasetService;
import io.javalin.http.Context;
import io.javalin.http.HttpStatus;
import io.javalin.http.UploadedFile;

import java.io.InputStream;
import java.util.Map;

public class DatasetController {

    private final DatasetService service;

    public DatasetController(DatasetService service) {
        this.service = service;
    }

    public void upload(Context ctx) {
        UploadedFile file = ctx.uploadedFile("file");
        if (file == null) {
            throw new ApiException(400, "BAD_REQUEST", "missing 'file' field in multipart upload");
        }
        String name = ctx.formParam("name");
        try (InputStream content = file.content()) {
            Map<String, Object> result = service.upload(content, file.size(), file.filename(), name);
            ctx.status(HttpStatus.CREATED).json(result);
        } catch (java.io.IOException e) {
            throw new ApiException(500, "INTERNAL_ERROR", "failed to read upload: " + e.getMessage(), e);
        }
    }

    public void list(Context ctx) {
        ctx.json(Map.of("datasets", service.list()));
    }

    public void get(Context ctx) {
        String name = ctx.pathParam("name");
        ctx.json(service.metadata(name));
    }

    public void delete(Context ctx) {
        String name = ctx.pathParam("name");
        service.delete(name);
        ctx.status(HttpStatus.NO_CONTENT);
    }
}
