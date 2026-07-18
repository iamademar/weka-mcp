package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SearchTrainRequest {
    public String dataset;
    public String algorithm;
    public List<String> options;
    public String modelName;
    public Integer classIndex;
    /**
     * CVParameterSelection parameter specs, each like "K 1 10 10" (param, lower, upper, steps).
     * See weka.classifiers.meta.CVParameterSelection.
     */
    public List<String> cvParameters;
    /** Folds for the inner CV search. Default 10. */
    public Integer folds;
}
