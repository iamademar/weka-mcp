package com.wekaapi.service;

import com.wekaapi.dto.ClusterAssignRequest;
import com.wekaapi.dto.ClusterEvaluateRequest;
import com.wekaapi.dto.ClusterTrainRequest;
import com.wekaapi.error.ApiException;
import weka.clusterers.AbstractClusterer;
import weka.clusterers.ClusterEvaluation;
import weka.clusterers.Clusterer;
import weka.clusterers.DensityBasedClusterer;
import weka.core.Instance;
import weka.core.Instances;
import weka.filters.Filter;
import weka.filters.unsupervised.attribute.Remove;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ClusteringService {

    private static final String ALLOWED_PREFIX = "weka.clusterers.";

    private final DatasetService datasetService;
    private final ModelService modelService;

    public ClusteringService(DatasetService datasetService, ModelService modelService) {
        this.datasetService = datasetService;
        this.modelService = modelService;
    }

    public Map<String, Object> train(ClusterTrainRequest req) {
        if (req == null) throw new ApiException(400, "BAD_REQUEST", "missing request body");
        if (req.dataset == null || req.dataset.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");
        if (req.algorithm == null || req.algorithm.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "algorithm is required");
        if (req.modelName == null || req.modelName.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "modelName is required");
        if (!req.algorithm.startsWith(ALLOWED_PREFIX))
            throw new ApiException(400, "INVALID_CLUSTERER",
                    "algorithm must start with '" + ALLOWED_PREFIX + "': " + req.algorithm);
        DatasetService.validateName(req.modelName);

        String[] options = (req.options == null) ? new String[0] : req.options.toArray(new String[0]);
        Clusterer clusterer;
        try {
            clusterer = AbstractClusterer.forName(req.algorithm, options);
        } catch (Exception e) {
            throw new ApiException(400, "INVALID_CLUSTERER",
                    "failed to instantiate clusterer: " + e.getMessage(), e);
        }

        Instances data = datasetService.load(req.dataset);
        boolean ignoreClass = (req.ignoreClass == null) || req.ignoreClass;
        // Header we persist keeps the original schema (incl. class) for later classes-to-clusters.
        Instances storedHeader = new Instances(data);
        Instances trainData = ignoreClass ? withoutClass(data) : data;
        trainData.setClassIndex(-1);

        long start = System.currentTimeMillis();
        try {
            clusterer.buildClusterer(trainData);
        } catch (Exception e) {
            throw new ApiException(422, "CLUSTERING_FAILED",
                    "clustering failed: " + e.getMessage(), e);
        }
        long elapsed = System.currentTimeMillis() - start;

        modelService.saveClusterer(req.modelName, clusterer, storedHeader);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("modelName", req.modelName);
        out.put("algorithm", req.algorithm);
        out.put("trainedOn", req.dataset);
        out.put("trainingTimeMs", elapsed);
        try {
            out.put("numClusters", clusterer.numberOfClusters());
        } catch (Exception ignored) {}
        out.put("summary", clusterer.toString());
        return out;
    }

    public Map<String, Object> assign(ClusterAssignRequest req) {
        if (req == null || req.model == null || req.model.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "model is required");
        boolean hasInline = req.instances != null && !req.instances.isEmpty();
        boolean hasDataset = req.dataset != null && !req.dataset.isBlank();
        if (hasInline == hasDataset) {
            throw new ApiException(400, "BAD_REQUEST",
                    "provide exactly one of 'instances' or 'dataset'");
        }

        ModelService.LoadedClusterer loaded = modelService.loadClusterer(req.model);
        Clusterer clusterer = loaded.clusterer();
        Instances header = clusterHeader(loaded.header());
        boolean density = clusterer instanceof DensityBasedClusterer;

        List<Instance> instances = new ArrayList<>();
        if (hasInline) {
            for (Map<String, Object> input : req.instances) {
                instances.add(PredictionService.buildInstance(header, input));
            }
        } else {
            Instances data = withoutClass(datasetService.load(req.dataset));
            for (int i = 0; i < data.numInstances(); i++) instances.add(data.instance(i));
        }

        List<Map<String, Object>> assignments = new ArrayList<>(instances.size());
        for (int i = 0; i < instances.size(); i++) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i);
            try {
                entry.put("cluster", clusterer.clusterInstance(instances.get(i)));
                if (density) {
                    double[] dist = clusterer.distributionForInstance(instances.get(i));
                    List<Double> d = new ArrayList<>(dist.length);
                    for (double v : dist) d.add(round(v));
                    entry.put("distribution", d);
                }
            } catch (Exception e) {
                throw new ApiException(422, "CLUSTERING_FAILED",
                        "cluster assignment failed: " + e.getMessage(), e);
            }
            assignments.add(entry);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", req.model);
        try {
            out.put("numClusters", clusterer.numberOfClusters());
        } catch (Exception ignored) {}
        out.put("assignments", assignments);
        return out;
    }

    public Map<String, Object> evaluate(ClusterEvaluateRequest req) {
        if (req == null || req.model == null || req.model.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "model is required");
        if (req.dataset == null || req.dataset.isBlank())
            throw new ApiException(400, "BAD_REQUEST", "dataset is required");

        ModelService.LoadedClusterer loaded = modelService.loadClusterer(req.model);
        Clusterer clusterer = loaded.clusterer();
        Instances data = datasetService.load(req.dataset);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("model", req.model);
        out.put("dataset", req.dataset);
        try {
            out.put("numClusters", clusterer.numberOfClusters());

            ClusterEvaluation ce = new ClusterEvaluation();
            ce.setClusterer(clusterer);
            // classes-to-clusters: only when the dataset carries a nominal class attribute
            boolean hasNominalClass = data.classIndex() >= 0 && data.classAttribute().isNominal();
            if (hasNominalClass) {
                ce.evaluateClusterer(data);
                // For each cluster, the class index it was assigned to in the best mapping.
                int[] mapping = ce.getClassesToClusters();
                if (mapping != null) out.put("clusterToClass", mapping);
            } else {
                ce.evaluateClusterer(withoutClass(data));
            }

            if (clusterer instanceof DensityBasedClusterer) {
                out.put("logLikelihood", round(ce.getLogLikelihood()));
            }
            out.put("summary", ce.clusterResultsToString());
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new ApiException(422, "CLUSTERING_FAILED",
                    "cluster evaluation failed: " + e.getMessage(), e);
        }
        return out;
    }

    /** Returns a copy with any class attribute removed and no class index set. */
    private static Instances withoutClass(Instances data) {
        if (data.classIndex() < 0) {
            Instances copy = new Instances(data);
            copy.setClassIndex(-1);
            return copy;
        }
        try {
            Remove remove = new Remove();
            remove.setAttributeIndicesArray(new int[]{data.classIndex()});
            remove.setInputFormat(data);
            Instances out = Filter.useFilter(data, remove);
            out.setClassIndex(-1);
            return out;
        } catch (Exception e) {
            throw new ApiException(422, "CLUSTERING_FAILED",
                    "failed to remove class attribute: " + e.getMessage(), e);
        }
    }

    /** Build-time header used to coerce inline instances; class removed to match how the clusterer was built. */
    private static Instances clusterHeader(Instances storedHeader) {
        return withoutClass(storedHeader);
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return 0.0;
        return Math.round(v * 10000.0) / 10000.0;
    }
}
