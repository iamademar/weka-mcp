package com.wekaapi.util;

import weka.core.Attribute;

public final class AttributeTypes {

    private AttributeTypes() {}

    public static String of(Attribute a) {
        if (a.isNominal()) return "nominal";
        if (a.isNumeric()) return "numeric";
        if (a.isString()) return "string";
        if (a.isDate()) return "date";
        return "unknown";
    }
}
