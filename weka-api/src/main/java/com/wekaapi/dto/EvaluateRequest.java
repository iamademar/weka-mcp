package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EvaluateRequest {
    public String model;
    public String dataset;

    /** "test_set" (default), "cross_validation", or "percentage_split". */
    public String method;

    /** Number of folds for cross_validation. Default 10, clamped to [2, numInstances]. */
    public Integer folds;

    /** Train portion for percentage_split, as a percent. Default 66.0, in (0, 100). */
    public Double trainPercent;

    /** Shuffle seed for cross_validation / percentage_split. Default 42. */
    public Long seed;

    /** percentage_split only: keep the dataset's row order instead of shuffling before
     *  the train/test cut. Default false (shuffled, as before). */
    public Boolean preserveOrder;

    /**
     * Optional square cost matrix (rows/cols = number of class values), nominal class only.
     * When present, the response includes totalCost and avgCost.
     */
    public double[][] costMatrix;
}
