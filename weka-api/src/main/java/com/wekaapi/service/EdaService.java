package com.wekaapi.service;

import com.wekaapi.error.ApiException;
import com.wekaapi.util.AttributeTypes;
import com.wekaapi.util.SamplingUtil;
import weka.core.Attribute;
import weka.core.AttributeStats;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.Utils;
import weka.experiment.Stats;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class EdaService {

    private final DatasetService datasetService;

    public EdaService(DatasetService datasetService) {
        this.datasetService = datasetService;
    }

    public Map<String, Object> attributeStats(String datasetName, String attributeName) {
        Instances data = datasetService.load(datasetName);
        int idx = attributeIndex(data, attributeName);
        return statsForAttribute(data, idx);
    }

    public Map<String, Object> summary(String datasetName) {
        Instances data = datasetService.load(datasetName);
        List<Map<String, Object>> attrs = new ArrayList<>();
        for (int i = 0; i < data.numAttributes(); i++) {
            attrs.add(statsForAttribute(data, i));
        }
        return Map.of(
                "name", datasetName,
                "numInstances", data.numInstances(),
                "attributes", attrs,
                "classAttribute", data.classAttribute().name()
        );
    }

    public Map<String, Object> histogram(String datasetName, String attributeName, int bins, boolean groupByClass) {
        Instances data = datasetService.load(datasetName);
        int idx = attributeIndex(data, attributeName);
        Attribute attr = data.attribute(idx);
        Attribute classAttr = data.classAttribute();
        boolean includeByClass = groupByClass && classAttr.isNominal() && classAttr.index() != idx;

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attribute", attr.name());
        out.put("type", AttributeTypes.of(attr));

        int missing = 0;
        List<Map<String, Object>> binList = new ArrayList<>();

        if (attr.isNominal()) {
            int n = attr.numValues();
            int[] counts = new int[n];
            int[][] byClass = includeByClass ? new int[n][classAttr.numValues()] : null;
            for (Instance inst : data) {
                if (inst.isMissing(idx)) { missing++; continue; }
                int v = (int) inst.value(idx);
                counts[v]++;
                if (byClass != null && !inst.classIsMissing()) {
                    byClass[v][(int) inst.classValue()]++;
                }
            }
            for (int i = 0; i < n; i++) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("value", attr.value(i));
                b.put("count", counts[i]);
                if (byClass != null) b.put("byClass", labelCounts(classAttr, byClass[i]));
                binList.add(b);
            }
        } else if (attr.isNumeric()) {
            AttributeStats stats = data.attributeStats(idx);
            Stats numeric = stats.numericStats;
            double min = numeric == null ? 0.0 : numeric.min;
            double max = numeric == null ? 1.0 : numeric.max;
            if (Double.isNaN(min) || Double.isNaN(max) || max <= min) {
                max = min + 1.0;
            }
            int b = Math.max(1, bins);
            double width = (max - min) / b;
            int[] counts = new int[b];
            int[][] byClass = includeByClass ? new int[b][classAttr.numValues()] : null;
            for (Instance inst : data) {
                if (inst.isMissing(idx)) { missing++; continue; }
                double v = inst.value(idx);
                int bin = (int) Math.floor((v - min) / width);
                if (bin >= b) bin = b - 1;
                if (bin < 0) bin = 0;
                counts[bin]++;
                if (byClass != null && !inst.classIsMissing()) {
                    byClass[bin][(int) inst.classValue()]++;
                }
            }
            for (int i = 0; i < b; i++) {
                Map<String, Object> bin = new LinkedHashMap<>();
                bin.put("lo", round(min + i * width));
                bin.put("hi", round(min + (i + 1) * width));
                bin.put("count", counts[i]);
                if (byClass != null) bin.put("byClass", labelCounts(classAttr, byClass[i]));
                binList.add(bin);
            }
        } else {
            throw new ApiException(400, "INVALID_ATTRIBUTE",
                    "histogram only supported for numeric or nominal attributes");
        }

        out.put("bins", binList);
        out.put("missing", missing);
        if (includeByClass) {
            List<String> classLabels = new ArrayList<>(classAttr.numValues());
            for (int i = 0; i < classAttr.numValues(); i++) classLabels.add(classAttr.value(i));
            out.put("classLabels", classLabels);
        }
        return out;
    }

    public Map<String, Object> scatter(String datasetName, String xName, String yName,
                                       int sample, boolean jitter, long seed) {
        Instances data = datasetService.load(datasetName);
        int xi = attributeIndex(data, xName);
        int yi = attributeIndex(data, yName);
        Attribute xa = data.attribute(xi);
        Attribute ya = data.attribute(yi);
        Attribute classAttr = data.classAttribute();
        boolean hasClass = classAttr.isNominal();

        int total = data.numInstances();
        int s = SamplingUtil.clampSampleSize(sample);
        int[] indices = SamplingUtil.sampleIndices(total, s, seed);

        Random rng = new Random(seed);
        List<Map<String, Object>> points = new ArrayList<>(indices.length);
        for (int i : indices) {
            Instance inst = data.instance(i);
            if (inst.isMissing(xi) || inst.isMissing(yi)) continue;
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("x", coord(xa, inst.value(xi), jitter, rng));
            p.put("y", coord(ya, inst.value(yi), jitter, rng));
            if (xa.isNominal()) p.put("xLabel", xa.value((int) inst.value(xi)));
            if (ya.isNominal()) p.put("yLabel", ya.value((int) inst.value(yi)));
            if (hasClass && !inst.classIsMissing()) {
                p.put("class", classAttr.value((int) inst.classValue()));
            }
            points.add(p);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", xa.name());
        out.put("y", ya.name());
        out.put("xType", AttributeTypes.of(xa));
        out.put("yType", AttributeTypes.of(ya));
        out.put("classAttribute", classAttr.name());
        out.put("totalInstances", total);
        out.put("sampled", points.size());
        out.put("points", points);
        return out;
    }

    public Map<String, Object> scatterMatrix(String datasetName, List<String> attributes, int sample, long seed) {
        Instances data = datasetService.load(datasetName);
        if (attributes == null || attributes.isEmpty()) {
            attributes = new ArrayList<>();
            int max = Math.min(4, data.numAttributes() - 1);
            for (int i = 0; i < max; i++) attributes.add(data.attribute(i).name());
        }
        if (attributes.size() > 6) {
            attributes = new ArrayList<>(attributes.subList(0, 6));
        }
        int[] idxs = new int[attributes.size()];
        for (int i = 0; i < attributes.size(); i++) {
            idxs[i] = attributeIndex(data, attributes.get(i));
        }

        Attribute classAttr = data.classAttribute();
        boolean hasClass = classAttr.isNominal();
        int total = data.numInstances();
        int s = SamplingUtil.clampSampleSize(sample);
        int[] indices = SamplingUtil.sampleIndices(total, s, seed);

        List<Map<String, Object>> pairs = new ArrayList<>();
        for (int a = 0; a < idxs.length; a++) {
            for (int b = a + 1; b < idxs.length; b++) {
                Attribute xa = data.attribute(idxs[a]);
                Attribute ya = data.attribute(idxs[b]);
                List<Map<String, Object>> points = new ArrayList<>();
                for (int i : indices) {
                    Instance inst = data.instance(i);
                    if (inst.isMissing(idxs[a]) || inst.isMissing(idxs[b])) continue;
                    Map<String, Object> p = new LinkedHashMap<>();
                    p.put("x", inst.value(idxs[a]));
                    p.put("y", inst.value(idxs[b]));
                    if (hasClass && !inst.classIsMissing()) {
                        p.put("class", classAttr.value((int) inst.classValue()));
                    }
                    points.add(p);
                }
                Map<String, Object> pair = new LinkedHashMap<>();
                pair.put("x", xa.name());
                pair.put("y", ya.name());
                pair.put("points", points);
                pairs.add(pair);
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("attributes", attributes);
        out.put("classAttribute", classAttr.name());
        out.put("totalInstances", total);
        out.put("sampled", Math.min(s, total));
        out.put("pairs", pairs);
        return out;
    }

    private static double coord(Attribute attr, double raw, boolean jitter, Random rng) {
        if (attr.isNominal() && jitter) {
            return raw + (rng.nextGaussian() * 0.05);
        }
        return raw;
    }

    private static Map<String, Integer> labelCounts(Attribute classAttr, int[] counts) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < counts.length; i++) m.put(classAttr.value(i), counts[i]);
        return m;
    }

    private static int attributeIndex(Instances data, String name) {
        if (name == null || name.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "attribute name is required");
        }
        Attribute a = data.attribute(name);
        if (a == null) {
            throw new ApiException(400, "INVALID_ATTRIBUTE",
                    "attribute not found on dataset: " + name);
        }
        return a.index();
    }

    private static Map<String, Object> statsForAttribute(Instances data, int idx) {
        Attribute attr = data.attribute(idx);
        AttributeStats stats = data.attributeStats(idx);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", attr.name());
        out.put("type", AttributeTypes.of(attr));
        out.put("count", stats.totalCount);
        out.put("missing", stats.missingCount);
        out.put("distinct", stats.distinctCount);
        out.put("unique", stats.uniqueCount);

        if (attr.isNumeric() && stats.numericStats != null) {
            Stats n = stats.numericStats;
            Map<String, Object> num = new LinkedHashMap<>();
            num.put("min", round(n.min));
            num.put("max", round(n.max));
            num.put("mean", round(n.mean));
            num.put("stdDev", round(n.stdDev));
            num.put("sum", round(n.sum));
            // Full-dataset quartiles + 1.5*IQR outlier count. WEKA's AttributeStats
            // does not expose quantiles, so compute them over the whole column here.
            // The quantile formula (linear interpolation on (n-1)*q) and the 1.5*IQR
            // fences mirror the frontend's numericStats() exactly, so preview-based
            // and full-dataset outlier definitions agree.
            double[] sorted = sortedNonMissingValues(data, idx);
            if (sorted.length > 0) {
                double q1 = quantile(sorted, 0.25);
                double median = quantile(sorted, 0.50);
                double q3 = quantile(sorted, 0.75);
                double iqr = q3 - q1;
                double loFence = q1 - 1.5 * iqr;
                double hiFence = q3 + 1.5 * iqr;
                int outlierCount = 0;
                for (double v : sorted) {
                    if (v < loFence || v > hiFence) outlierCount++;
                }
                num.put("q1", round(q1));
                num.put("median", round(median));
                num.put("q3", round(q3));
                num.put("outlierCount", outlierCount);
            }
            out.put("numeric", num);
        }
        if (attr.isNominal() && stats.nominalCounts != null) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            for (int i = 0; i < attr.numValues(); i++) {
                counts.put(attr.value(i), stats.nominalCounts[i]);
            }
            out.put("nominalCounts", counts);
        }
        return out;
    }

    private static double round(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v)) return v;
        return Math.round(v * 10000.0) / 10000.0;
    }

    /** All non-missing values of a numeric attribute across the full dataset, sorted ascending. */
    private static double[] sortedNonMissingValues(Instances data, int idx) {
        int nRows = data.numInstances();
        double[] buf = new double[nRows];
        int k = 0;
        for (int r = 0; r < nRows; r++) {
            double v = data.instance(r).value(idx);
            if (!Utils.isMissingValue(v)) buf[k++] = v;
        }
        double[] out = java.util.Arrays.copyOf(buf, k);
        java.util.Arrays.sort(out);
        return out;
    }

    /**
     * Linear-interpolation quantile on a sorted array, matching the frontend's
     * quantile() in explore-stats.ts: pos = (n-1)*q, interpolate between neighbours.
     * Caller guarantees {@code sorted.length > 0}.
     */
    private static double quantile(double[] sorted, double q) {
        double pos = (sorted.length - 1) * q;
        int lo = (int) Math.floor(pos);
        int hi = (int) Math.ceil(pos);
        if (lo == hi) return sorted[lo];
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo);
    }

    // unused but kept for future filter integration
    @SuppressWarnings("unused")
    private static double safeMissing(double v) {
        return Utils.isMissingValue(v) ? Double.NaN : v;
    }
}
