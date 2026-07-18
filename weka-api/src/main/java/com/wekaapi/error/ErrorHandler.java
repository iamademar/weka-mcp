package com.wekaapi.error;

import io.javalin.Javalin;
import io.javalin.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ErrorHandler {

    private static final Logger LOG = LoggerFactory.getLogger(ErrorHandler.class);

    private ErrorHandler() {}

    /**
     * The enriched error body (master §10.5). Keeps the legacy {@code error}/{@code code}
     * keys (the MCP client and older callers read them) and adds {@code userMessage},
     * {@code technicalMessage}, {@code recoverable}, and {@code allowedRecoveries} so the
     * Recovery agent can branch without parsing prose. A {@link LinkedHashMap} is used
     * because {@code Map.of} rejects nulls and does not preserve key order.
     */
    private static Map<String, Object> body(String code, String message, String userMessage,
                                            boolean recoverable, java.util.List<String> allowedRecoveries) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("code", code);
        out.put("error", message);                 // legacy: technical message
        out.put("technicalMessage", message);
        out.put("userMessage", userMessage);
        out.put("recoverable", recoverable);
        out.put("allowedRecoveries", allowedRecoveries);
        return out;
    }

    public static void register(Javalin app) {
        app.exception(ApiException.class, (ex, ctx) -> {
            ctx.status(ex.status());
            ctx.json(body(ex.code(), ex.getMessage(), ex.userMessage(),
                          ex.recoverable(), ex.allowedRecoveries()));
        });

        app.exception(IllegalArgumentException.class, (ex, ctx) -> {
            ctx.status(HttpStatus.BAD_REQUEST);
            RecoveryMetadata.Info info = RecoveryMetadata.forCode("BAD_REQUEST");
            ctx.json(body("BAD_REQUEST", ex.getMessage(), info.userMessage(),
                          info.recoverable(), info.allowedRecoveries()));
        });

        app.exception(Exception.class, (ex, ctx) -> {
            LOG.error("Unhandled exception", ex);
            ctx.status(HttpStatus.INTERNAL_SERVER_ERROR);
            String message = ex.getMessage() == null ? "internal error" : ex.getMessage();
            RecoveryMetadata.Info info = RecoveryMetadata.forCode("INTERNAL_ERROR");
            ctx.json(body("INTERNAL_ERROR", message, info.userMessage(),
                          info.recoverable(), info.allowedRecoveries()));
        });
    }
}
