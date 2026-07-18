package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AttributeSelectionRequest {
    public String dataset;

    /** Optional class index; defaults to the last attribute. -1 also means last. */
    public Integer classIndex;

    /** Fully qualified evaluator, e.g. weka.attributeSelection.CfsSubsetEval. */
    public String evaluator;
    public List<String> evaluatorOptions;

    /** Fully qualified search, e.g. weka.attributeSelection.BestFirst or Ranker. */
    public String search;
    public List<String> searchOptions;
}
