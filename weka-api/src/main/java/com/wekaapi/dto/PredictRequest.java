package com.wekaapi.dto;

import java.util.List;
import java.util.Map;

public class PredictRequest {
    public String model;
    public List<Map<String, Object>> instances;
}
