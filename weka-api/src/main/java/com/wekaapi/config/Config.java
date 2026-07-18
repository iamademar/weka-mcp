package com.wekaapi.config;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class Config {

    public final int port;
    public final Path modelsDir;
    public final Path dataDir;
    public final long maxUploadBytes;
    public final String logLevel;

    private Config(int port, Path modelsDir, Path dataDir, long maxUploadBytes, String logLevel) {
        this.port = port;
        this.modelsDir = modelsDir;
        this.dataDir = dataDir;
        this.maxUploadBytes = maxUploadBytes;
        this.logLevel = logLevel;
    }

    public static Config fromEnv() {
        int port = parseInt(System.getenv("PORT"), 7070);
        Path modelsDir = Paths.get(orDefault(System.getenv("MODELS_DIR"), "/app/models"));
        Path dataDir = Paths.get(orDefault(System.getenv("DATA_DIR"), "/app/data"));
        int maxUploadMb = parseInt(System.getenv("MAX_UPLOAD_MB"), 100);
        String logLevel = orDefault(System.getenv("LOG_LEVEL"), "INFO");
        return new Config(port, modelsDir, dataDir, maxUploadMb * 1024L * 1024L, logLevel);
    }

    public static Config of(int port, Path modelsDir, Path dataDir, int maxUploadMb) {
        return new Config(port, modelsDir, dataDir, maxUploadMb * 1024L * 1024L, "INFO");
    }

    private static String orDefault(String v, String d) {
        return (v == null || v.isBlank()) ? d : v;
    }

    private static int parseInt(String v, int d) {
        if (v == null || v.isBlank()) return d;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return d; }
    }
}
