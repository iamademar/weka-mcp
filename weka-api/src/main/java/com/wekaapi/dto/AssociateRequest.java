package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AssociateRequest {
    public String dataset;
    /** Fully qualified associator, e.g. weka.associations.Apriori or weka.associations.FPGrowth. */
    public String algorithm;
    public List<String> options;
}
