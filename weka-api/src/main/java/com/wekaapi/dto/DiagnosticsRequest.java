package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiagnosticsRequest {
    public String model;
    public String dataset;
    public String classValue;
    public Integer bins;
    public Integer sample;
    public Long seed;
}
