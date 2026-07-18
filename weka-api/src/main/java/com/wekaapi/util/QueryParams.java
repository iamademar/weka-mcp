package com.wekaapi.util;

import com.wekaapi.error.ApiException;
import io.javalin.http.Context;

public final class QueryParams {

    private QueryParams() {}

    public static int intOrDefault(Context ctx, String name, int defaultValue, int min, int max) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        int v;
        try {
            v = Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "BAD_REQUEST", "query param '" + name + "' must be an integer");
        }
        if (v < min) v = min;
        if (v > max) v = max;
        return v;
    }

    public static long longOrDefault(Context ctx, String name, long defaultValue) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new ApiException(400, "BAD_REQUEST", "query param '" + name + "' must be an integer");
        }
    }

    public static boolean boolOrDefault(Context ctx, String name, boolean defaultValue) {
        String raw = ctx.queryParam(name);
        if (raw == null || raw.isBlank()) return defaultValue;
        return raw.equalsIgnoreCase("true") || raw.equals("1") || raw.equalsIgnoreCase("yes");
    }

    public static String stringOrDefault(Context ctx, String name, String defaultValue) {
        String raw = ctx.queryParam(name);
        return (raw == null || raw.isBlank()) ? defaultValue : raw;
    }
}
