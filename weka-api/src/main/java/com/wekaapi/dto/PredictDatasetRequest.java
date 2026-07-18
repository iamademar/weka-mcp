package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PredictDatasetRequest {
    public String model;
    public String dataset;
    /** Include the class probability distribution per row (nominal class only). Default false. */
    public Boolean includeDistribution;
}
