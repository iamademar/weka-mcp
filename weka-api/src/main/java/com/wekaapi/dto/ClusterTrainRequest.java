package com.wekaapi.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClusterTrainRequest {
    public String dataset;
    /** Fully qualified clusterer, e.g. weka.clusterers.SimpleKMeans. */
    public String algorithm;
    public List<String> options;
    public String modelName;
    /**
     * If true (default), a nominal class attribute is removed before clustering so the clusterer
     * runs unsupervised. The original class is retained in the stored header for classes-to-clusters.
     */
    public Boolean ignoreClass;
}
