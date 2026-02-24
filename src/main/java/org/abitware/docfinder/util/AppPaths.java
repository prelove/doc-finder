package org.abitware.docfinder.util;

import java.nio.file.Path;
import java.nio.file.Paths;

public final class AppPaths {
    private AppPaths() {
    }

    public static Path getBaseDir() {
        return Paths.get(System.getProperty("user.dir"), ".docfinder").toAbsolutePath().normalize();
    }
}

