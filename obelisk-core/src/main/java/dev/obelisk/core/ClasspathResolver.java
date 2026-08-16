package dev.obelisk.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Discovers a Maven project's runtime/compile classpath by shelling out to
 * {@code mvn dependency:build-classpath}, so symbol resolution stays in sync
 * with the project's real build rather than a hand-maintained config file.
 */
public final class ClasspathResolver {

    private ClasspathResolver() {
    }

    public static List<String> resolve(Path projectDir) {
        if (!Files.exists(projectDir.resolve("pom.xml"))) {
            return List.of();
        }
        try {
            Path outputFile = Files.createTempFile("obelisk-classpath", ".txt");
            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "mvn", "-q", "-B",
                        "dependency:build-classpath",
                        "-Dmdep.outputFile=" + outputFile.toAbsolutePath());
                pb.directory(projectDir.toFile());
                pb.redirectErrorStream(true);
                Process process = pb.start();
                String mvnOutput = new String(process.getInputStream().readAllBytes());
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new RefactorException(
                            "mvn dependency:build-classpath failed (exit " + exitCode + "):\n" + mvnOutput);
                }
                String contents = Files.readString(outputFile).strip();
                if (contents.isEmpty()) {
                    return List.of();
                }
                List<String> entries = new ArrayList<>();
                for (String entry : contents.split(java.io.File.pathSeparator)) {
                    if (!entry.isBlank()) {
                        entries.add(entry);
                    }
                }
                return entries;
            } finally {
                Files.deleteIfExists(outputFile);
            }
        } catch (IOException e) {
            throw new RefactorException("Failed to run 'mvn dependency:build-classpath' in " + projectDir
                    + " (is Maven installed and on PATH?): " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RefactorException("Interrupted while resolving classpath", e);
        }
    }
}
