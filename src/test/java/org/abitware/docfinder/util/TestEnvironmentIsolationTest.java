package org.abitware.docfinder.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the fixed Surefire isolation: the forked JVM must run with
 * user.dir inside target/test-classes and user.home inside
 * target/test-home so production data locations can never be reached.
 */
@Execution(ExecutionMode.SAME_THREAD)
class TestEnvironmentIsolationTest {

    @Test
    void shouldRunForkWithIsolatedWorkingDirectory() {
        Path userDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        assertEquals("test-classes", userDir.getFileName().toString(),
                "surefire workingDirectory must be target/test-classes, actual: " + userDir);
        assertEquals("target", userDir.getParent().getFileName().toString(),
                "test-classes must live under target/, actual: " + userDir);
        Path base = AppPaths.getBaseDir();
        assertTrue(base.startsWith(userDir),
                "AppPaths base dir must be under the isolated working directory: " + base);
    }

    @Test
    void shouldRunForkWithIsolatedUserHome() {
        Path home = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        assertEquals("test-home", home.getFileName().toString(),
                "user.home must be target/test-home, actual: " + home);
        assertEquals("target", home.getParent().getFileName().toString(),
                "test-home must live under target/, actual: " + home);
    }

    @Test
    void shouldPreserveExternalArgLineProbe() {
        // Runs only when -DargLine carries the probe, e.g.
        // mvn test -DargLine="-Xmx256m -Ddocfinder.argline.probe=present"
        assumeTrue("present".equals(System.getProperty("docfinder.argline.probe")),
                "probe property not supplied; run with -DargLine to verify external args are preserved");
        // If we reach here the external argLine survived the isolation composition.
    }
}
