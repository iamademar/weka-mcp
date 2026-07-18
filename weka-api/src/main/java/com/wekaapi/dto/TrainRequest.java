package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrainRequest {
    public String dataset;
    public String algorithm;
    public List<String> options;
    public String modelName;
    public Integer classIndex;
    public List<FilterSpec> filters;
}
