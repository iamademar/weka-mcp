package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransformRequest {
    public String dataset;
    public List<FilterSpec> filters;
    public String outputName;
    public String format;
}
