package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClusterAssignRequest {
    public String model;
    /** Inline instances (attribute name -> value), as with /predict. Mutually exclusive with dataset. */
    public List<Map<String, Object>> instances;
    /** Named dataset to assign clusters for. Mutually exclusive with instances. */
    public String dataset;
}
