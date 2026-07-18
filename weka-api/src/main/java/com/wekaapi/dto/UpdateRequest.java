package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class UpdateRequest {
    public String model;
    /** New instances (attribute name -> value), as with /predict. */
    public List<Map<String, Object>> instances;
}
