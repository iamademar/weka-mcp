package com.wekaapi.error;

import java.util.List;
import java.util.Map;

/**
 * Maps a machine-readable error {@code code} to the recovery metadata the
 * enriched MCP error shape carries (master §10.5): a participant-facing
 * {@code userMessage}, whether the failure is {@code recoverable}, and the set of
 * {@code allowedRecoveries} the Recovery agent may choose from.
 *
 * <p>Keeping this here means the many {@link ApiException} throw sites do not each
 * have to spell out recovery options — an unknown code degrades to a safe
 * non-recoverable default rather than inventing an action. Recovery actions use
 * the vocabulary the Recovery agent understands (see the {@code recovery/v1}
 * prompt and {@code glamli_contracts.RecoveryDecision}).
 */
public final class RecoveryMetadata {

    /** The recovery metadata for one error code. */
    public record Info(String userMessage, boolean recoverable, List<String> allowedRecoveries) {}

    private RecoveryMetadata() {}

    // Safe default: an unrecognised error is treated as not automatically
    // recoverable, so the agent stops and explains rather than retrying blindly.
    private static final Info DEFAULT =
            new Info("Something went wrong while running the experiment.", false, List.of("stop"));

    private static final Map<String, Info> BY_CODE = Map.ofEntries(
            // Target-type problems: the class column is the wrong kind for the task.
            Map.entry("NOT_NOMINAL_CLASS", new Info(
                    "This task needs a category to predict, but the chosen column holds numbers.",
                    true, List.of("select_another_target", "change_task_type"))),
            Map.entry("NOT_NUMERIC_CLASS", new Info(
                    "This task needs a number to predict, but the chosen column holds categories.",
                    true, List.of("select_another_target", "change_task_type"))),
            Map.entry("REQUIRES_NOMINAL", new Info(
                    "This method needs a category column, but the chosen column is not one.",
                    true, List.of("select_another_target", "change_task_type"))),
            Map.entry("INVALID_CLASS_VALUE", new Info(
                    "A value in the target column could not be used.",
                    true, List.of("exclude_column", "select_another_target"))),

            // Prediction-time problems: usually a bad input value on one field.
            Map.entry("BAD_REQUEST", new Info(
                    "One of the values entered was not valid for its field.",
                    true, List.of("ask_user", "retry"))),
            Map.entry("PREDICTION_FAILED", new Info(
                    "The model could not score this case.",
                    true, List.of("ask_user", "retry"))),
            Map.entry("HEADER_MISMATCH", new Info(
                    "The data given does not match what the model expects.",
                    true, List.of("change_transformation", "stop"))),

            // Training / evaluation problems: retry once, else exclude a bad column.
            Map.entry("TRAINING_FAILED", new Info(
                    "A model could not be trained on this data.",
                    true, List.of("retry", "exclude_column", "stop"))),
            Map.entry("EVALUATION_FAILED", new Info(
                    "A trained model could not be evaluated.",
                    true, List.of("retry", "stop"))),
            Map.entry("TRANSFORM_FAILED", new Info(
                    "A preprocessing step could not be applied.",
                    true, List.of("change_transformation", "stop"))),

            // Not-found / configuration problems: not automatically recoverable.
            Map.entry("DATASET_NOT_FOUND", new Info(
                    "The dataset could not be found.", false, List.of("stop"))),
            Map.entry("MODEL_NOT_FOUND", new Info(
                    "The trained model could not be found.", false, List.of("stop"))),
            Map.entry("INVALID_ALGORITHM", new Info(
                    "The requested algorithm is not available.", false, List.of("stop"))),
            Map.entry("INVALID_FILTER", new Info(
                    "A requested preprocessing filter is not available.",
                    true, List.of("change_transformation", "stop")))
    );

    public static Info forCode(String code) {
        return BY_CODE.getOrDefault(code, DEFAULT);
    }
}
