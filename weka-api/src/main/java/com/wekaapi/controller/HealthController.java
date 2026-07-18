package com.wekaapi.controller;

import io.javalin.http.Context;
import weka.core.Version;

import java.util.Map;

public class HealthController {

    public void get(Context ctx) {
        ctx.json(Map.of(
                "status", "ok",
                "wekaVersion", Version.VERSION
        ));
    }
}
