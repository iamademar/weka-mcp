package com.wekaapi;

import com.wekaapi.config.Config;
import io.javalin.Javalin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class TestSupport {

    private TestSupport() {}

    public static Javalin app(Path modelsDir, Path dataDir) {
        Config config = Config.of(0, modelsDir, dataDir, 100);
        return App.build(config);
    }

    public static Path copyIris(Path dir) throws IOException {
        Path out = dir.resolve("iris.arff");
        try (InputStream in = TestSupport.class.getResourceAsStream("/iris.arff")) {
            if (in == null) throw new IOException("classpath resource /iris.arff missing");
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }
}
