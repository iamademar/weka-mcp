package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class LearningCurveRequest {
    public String model;
    public String dataset;
    /** Training-set fractions to evaluate. Default [0.1, 0.2, ... 1.0]. Each in (0, 1]. */
    public List<Double> fractions;
    /** Folds for the CV at each fraction. Default 10. */
    public Integer folds;
    public Long seed;
}
