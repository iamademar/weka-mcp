package com.wekaapi.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class SamplingUtil {

    public static final int DEFAULT_SAMPLE = 500;
    public static final int MAX_SAMPLE = 5000;
    public static final long DEFAULT_SEED = 42L;

    private SamplingUtil() {}

    public static int clampSampleSize(int requested) {
        if (requested <= 0) return DEFAULT_SAMPLE;
        return Math.min(requested, MAX_SAMPLE);
    }

    public static int[] sampleIndices(int total, int sample, long seed) {
        if (total <= sample) {
            int[] all = new int[total];
            for (int i = 0; i < total; i++) all[i] = i;
            return all;
        }
        List<Integer> idx = new ArrayList<>(total);
        for (int i = 0; i < total; i++) idx.add(i);
        Collections.shuffle(idx, new Random(seed));
        int[] out = new int[sample];
        for (int i = 0; i < sample; i++) out[i] = idx.get(i);
        java.util.Arrays.sort(out);
        return out;
    }
}
