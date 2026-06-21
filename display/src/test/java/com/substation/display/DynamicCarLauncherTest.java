package com.substation.display;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DynamicCarLauncherTest {

    @Test
    void isJarAvailable_falseWhenMissing(@TempDir Path projectRoot) {
        assertFalse(DynamicCarLauncher.isJarAvailable(projectRoot));
    }

    @Test
    void isJarAvailable_trueWhenJarExists(@TempDir Path projectRoot) throws Exception {
        Path jarDir = projectRoot.resolve("car/target");
        jarDir.toFile().mkdirs();
        Path jar = jarDir.resolve("car-1.0-SNAPSHOT.jar");
        jar.toFile().createNewFile();
        assertTrue(DynamicCarLauncher.isJarAvailable(projectRoot));
        assertTrue(DynamicCarLauncher.jarPath(projectRoot).endsWith("car-1.0-SNAPSHOT.jar"));
    }
}
