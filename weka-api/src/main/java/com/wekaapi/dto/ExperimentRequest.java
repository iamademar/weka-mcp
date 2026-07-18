package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExperimentRequest {
    public List<String> datasets;
    public List<AlgorithmSpec> algorithms;
    /** "accuracy" (default, nominal) or "rmse" (numeric). */
    public String metric;
    public Integer folds;
    public Integer runs;
    public Long seed;
    /** Index into algorithms used as the significance baseline. Default 0. */
    public Integer baselineIndex;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class AlgorithmSpec {
        public String algorithm;
        public List<String> options;
    }
}
