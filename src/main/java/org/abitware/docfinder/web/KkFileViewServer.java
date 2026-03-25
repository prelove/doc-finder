package org.abitware.docfinder.web;

import org.abitware.docfinder.util.AppPaths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages the kkFileView server as an embedded subprocess.
 * The server JAR should be placed at ~/.docfinder/kkfileview/kkFileView.jar
 */
public class KkFileViewServer {
    private static final Logger log = LoggerFactory.getLogger(KkFileViewServer.class);

    private Process process;
    private Thread outputThread;
    private Thread errorThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final int port;
    private final Path jarPath;
    private final Path workDir;

    /**
     * Create a new KkFileViewServer instance.
     * @param port The port for kkFileView to listen on (default: 8012)
     */
    public KkFileViewServer(int port) {
        this.port = port;
        this.workDir = AppPaths.getBaseDir().resolve("kkfileview");
        this.jarPath = workDir.resolve("kkFileView.jar");
    }

    /**
     * Check if the kkFileView JAR exists and is ready to run.
     */
    public boolean isAvailable() {
        return Files.exists(jarPath) && Files.isRegularFile(jarPath);
    }

    /**
     * Get the path where the kkFileView JAR should be placed.
     */
    public Path getJarPath() {
        return jarPath;
    }

    /**
     * Start the kkFileView server.
     * @throws IOException if the server cannot be started
     */
    public synchronized void start() throws IOException {
        if (running.get()) {
            log.warn("kkFileView server is already running");
            return;
        }

        if (!isAvailable()) {
            throw new IOException("kkFileView JAR not found at: " + jarPath +
                "\nPlease download and place kkFileView JAR at this location.");
        }

        // Ensure work directory exists
        Files.createDirectories(workDir);

        // Build the command to start kkFileView
        List<String> command = new ArrayList<>();
        command.add(getJavaExecutable());
        command.add("-Dserver.port=" + port);
        command.add("-Dfile.dir=" + workDir.resolve("files").toString());
        command.add("-Duser.language=en");
        command.add("-Duser.country=US");
        command.add("-jar");
        command.add(jarPath.toString());

        log.info("Starting kkFileView server on port {} with command: {}", port, String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir.toFile());
        pb.redirectErrorStream(false);

        try {
            process = pb.start();
            running.set(true);

            // Start threads to consume output streams
            outputThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.debug("[kkFileView] {}", line);
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        log.error("Error reading kkFileView output", e);
                    }
                }
            }, "kkFileView-output");
            outputThread.setDaemon(true);
            outputThread.start();

            errorThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        log.warn("[kkFileView-err] {}", line);
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        log.error("Error reading kkFileView error stream", e);
                    }
                }
            }, "kkFileView-error");
            errorThread.setDaemon(true);
            errorThread.start();

            // Wait a bit to see if the process starts successfully
            Thread.sleep(2000);
            if (!process.isAlive()) {
                running.set(false);
                throw new IOException("kkFileView process terminated unexpectedly");
            }

            log.info("kkFileView server started successfully on port {}", port);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while starting kkFileView", e);
        } catch (IOException e) {
            running.set(false);
            if (process != null) {
                process.destroy();
            }
            throw e;
        }
    }

    /**
     * Stop the kkFileView server.
     */
    public synchronized void stop() {
        if (!running.get()) {
            log.debug("kkFileView server is not running");
            return;
        }

        log.info("Stopping kkFileView server...");
        running.set(false);

        if (process != null) {
            process.destroy();
            try {
                boolean exited = process.waitFor(10, TimeUnit.SECONDS);
                if (!exited) {
                    log.warn("kkFileView process did not exit gracefully, forcing termination");
                    process.destroyForcibly();
                    process.waitFor(5, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while waiting for kkFileView to stop", e);
                process.destroyForcibly();
            }
            process = null;
        }

        // Wait for output threads to finish
        if (outputThread != null) {
            try {
                outputThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (errorThread != null) {
            try {
                errorThread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        log.info("kkFileView server stopped");
    }

    /**
     * Check if the server is currently running.
     */
    public boolean isRunning() {
        return running.get() && process != null && process.isAlive();
    }

    /**
     * Get the port the server is configured to run on.
     */
    public int getPort() {
        return port;
    }

    /**
     * Get the base URL for accessing the kkFileView server.
     */
    public String getBaseUrl() {
        return "http://127.0.0.1:" + port;
    }

    /**
     * Get the path to the Java executable.
     */
    private String getJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null) {
            Path javaPath = Paths.get(javaHome, "bin", "java");
            if (Files.exists(javaPath)) {
                return javaPath.toString();
            }
            // Try with .exe for Windows
            javaPath = Paths.get(javaHome, "bin", "java.exe");
            if (Files.exists(javaPath)) {
                return javaPath.toString();
            }
        }
        // Fallback to assuming java is on PATH
        return "java";
    }
}
