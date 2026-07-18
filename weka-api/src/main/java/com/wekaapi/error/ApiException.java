package com.wekaapi.error;

import java.util.List;

/**
 * A structured API failure. Beyond {@code status}/{@code code}/{@code message}, it
 * carries the enriched recovery metadata of master §10.5 — a participant-facing
 * {@code userMessage}, whether the failure is {@code recoverable}, and the
 * {@code allowedRecoveries} the Recovery agent may choose from. These are derived
 * from {@link RecoveryMetadata} by default so existing throw sites need no change;
 * a caller may override them with the fuller constructor.
 */
public class ApiException extends RuntimeException {

    private final int status;
    private final String code;
    private final String userMessage;
    private final boolean recoverable;
    private final List<String> allowedRecoveries;

    public ApiException(int status, String code, String message) {
        this(status, code, message, (Throwable) null);
    }

    public ApiException(int status, String code, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
        this.code = code;
        RecoveryMetadata.Info info = RecoveryMetadata.forCode(code);
        this.userMessage = info.userMessage();
        this.recoverable = info.recoverable();
        this.allowedRecoveries = info.allowedRecoveries();
    }

    /** Full constructor for a call site that wants to override the recovery metadata. */
    public ApiException(int status, String code, String message, String userMessage,
                        boolean recoverable, List<String> allowedRecoveries) {
        super(message);
        this.status = status;
        this.code = code;
        this.userMessage = userMessage;
        this.recoverable = recoverable;
        this.allowedRecoveries = allowedRecoveries;
    }

    public int status() { return status; }
    public String code() { return code; }
    public String userMessage() { return userMessage; }
    public boolean recoverable() { return recoverable; }
    public List<String> allowedRecoveries() { return allowedRecoveries; }
}
