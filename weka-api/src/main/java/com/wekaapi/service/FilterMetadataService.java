package com.wekaapi.service;

import com.wekaapi.error.ApiException;
import weka.core.Option;
import weka.core.OptionHandler;
import weka.core.Utils;
import weka.filters.Filter;
import weka.filters.SupervisedFilter;
import weka.filters.UnsupervisedFilter;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FilterMetadataService {

    private static final String ALLOWED_PREFIX = "weka.filters.";
    private final Map<String, Map<String, Object>> cache = new ConcurrentHashMap<>();

    public Map<String, Object> metadata(String fqn) {
        if (fqn == null || fqn.isBlank()) {
            throw new ApiException(400, "BAD_REQUEST", "filter classname is required");
        }
        if (!fqn.startsWith(ALLOWED_PREFIX)) {
            throw new ApiException(400, "INVALID_FILTER",
                    "filter must start with '" + ALLOWED_PREFIX + "': " + fqn);
        }
        return cache.computeIfAbsent(fqn, FilterMetadataService::build);
    }

    private static Map<String, Object> build(String fqn) {
        Filter filter;
        try {
            filter = (Filter) Utils.forName(Filter.class, fqn, new String[0]);
        } catch (Exception e) {
            Throwable cause = (e.getCause() != null) ? e.getCause() : e;
            if (cause instanceof ClassNotFoundException) {
                throw new ApiException(400, "INVALID_FILTER", "unknown filter: " + fqn);
            }
            throw new ApiException(400, "INVALID_FILTER",
                    "failed to instantiate filter " + fqn + ": " + cause.getMessage(), e);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("classname", fqn);
        out.put("supervised", deriveSupervised(filter));
        String tail = fqn.substring(ALLOWED_PREFIX.length());
        out.put("family", deriveFamily(tail));
        out.put("level", deriveLevel(tail));
        out.put("description", globalInfo(filter));
        out.put("options", extractOptions(filter));
        return out;
    }

    private static Boolean deriveSupervised(Filter f) {
        if (f instanceof SupervisedFilter) return true;
        if (f instanceof UnsupervisedFilter) return false;
        return null;
    }

    private static String deriveFamily(String tail) {
        int dot = tail.indexOf('.');
        if (dot < 0) return "misc";
        int dot2 = tail.indexOf('.', dot + 1);
        return (dot2 < 0) ? tail.substring(0, dot) : tail.substring(0, dot2);
    }

    private static String deriveLevel(String tail) {
        int dot = tail.indexOf('.');
        if (dot < 0) return null;
        int dot2 = tail.indexOf('.', dot + 1);
        if (dot2 < 0) return null;
        String level = tail.substring(dot + 1, dot2);
        return ("attribute".equals(level) || "instance".equals(level)) ? level : null;
    }

    private static String globalInfo(Filter f) {
        try {
            Method m = f.getClass().getMethod("globalInfo");
            Object r = m.invoke(f);
            return (r == null) ? null : r.toString();
        } catch (NoSuchMethodException e) {
            return null;
        } catch (InvocationTargetException | IllegalAccessException e) {
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static List<Map<String, Object>> extractOptions(Filter f) {
        OptionHandler oh = (OptionHandler) f;

        Map<String, String> presentValues = new LinkedHashMap<>();
        java.util.Set<String> presentFlags = new java.util.HashSet<>();
        String[] currentOptions;
        try {
            currentOptions = oh.getOptions();
        } catch (Exception e) {
            currentOptions = new String[0];
        }
        for (int i = 0; i < currentOptions.length; i++) {
            String token = currentOptions[i];
            if (token == null || !token.startsWith("-")) continue;
            String flag = token.substring(1);
            presentFlags.add(flag);
            if (i + 1 < currentOptions.length) {
                String next = currentOptions[i + 1];
                if (next != null && !next.startsWith("-")) {
                    presentValues.put(flag, next);
                }
            }
        }

        List<Map<String, Object>> options = new ArrayList<>();
        Enumeration<Option> en;
        try {
            en = oh.listOptions();
        } catch (Exception e) {
            return Collections.emptyList();
        }
        while (en != null && en.hasMoreElements()) {
            Option o = en.nextElement();
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", o.name());
            entry.put("synopsis", o.synopsis());
            entry.put("description", o.description());
            int num = o.numArguments();
            entry.put("numArguments", num);
            String flag = o.name();
            if (num == 0) {
                entry.put("default", presentFlags.contains(flag));
            } else {
                String v = presentValues.get(flag);
                if (v != null) entry.put("default", v);
            }
            options.add(entry);
        }
        return options;
    }
}
