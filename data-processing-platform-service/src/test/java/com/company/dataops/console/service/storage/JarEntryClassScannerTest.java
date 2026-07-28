package com.company.dataops.console.service.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * Compiles small fixture classes in-process (same javax.tools.JavaCompiler
 * approach JavaJobBuildService uses) and packs them into a real in-memory
 * jar, so findEntryClasses runs against genuine bytecode rather than
 * hand-rolled class bytes.
 */
class JarEntryClassScannerTest {

    private byte[] buildJar(String... sources) throws IOException {
        Path workDir = Files.createTempDirectory("jar-entry-scanner-test-");
        try {
            Path srcDir = workDir.resolve("src");
            Path outDir = workDir.resolve("out");
            Files.createDirectories(srcDir);
            Files.createDirectories(outDir);

            List<Path> sourceFiles = new java.util.ArrayList<>();
            for (String source : sources) {
                String className = extractFullyQualifiedName(source);
                Path sourceFile = srcDir.resolve(className.replace('.', '/') + ".java");
                Files.createDirectories(sourceFile.getParent());
                Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
                sourceFiles.add(sourceFile);
            }

            JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
            try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
                fileManager.setLocationFromPaths(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(outDir));
                boolean success = compiler.getTask(null, fileManager, null, null, null,
                    fileManager.getJavaFileObjectsFromPaths(sourceFiles)).call();
                assertTrue(success, "fixture sources must compile cleanly");
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (JarOutputStream jarOut = new JarOutputStream(buffer)) {
                try (Stream<Path> classFiles = Files.walk(outDir)) {
                    for (Path classFile : classFiles.filter(Files::isRegularFile).toList()) {
                        String entryName = outDir.relativize(classFile).toString().replace('\\', '/');
                        jarOut.putNextEntry(new JarEntry(entryName));
                        Files.copy(classFile, jarOut);
                        jarOut.closeEntry();
                    }
                }
            }
            return buffer.toByteArray();
        } finally {
            try (Stream<Path> paths = Files.walk(workDir)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.delete(path);
                    } catch (IOException ignored) {
                        // best-effort temp cleanup
                    }
                });
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private String extractFullyQualifiedName(String source) {
        String packageName = source.lines()
            .filter(line -> line.trim().startsWith("package "))
            .findFirst()
            .map(line -> line.trim().substring("package ".length()).replace(";", "").trim())
            .orElseThrow();
        String simpleName = source.lines()
            .filter(line -> line.contains("class "))
            .findFirst()
            .map(line -> {
                String afterClass = line.substring(line.indexOf("class ") + "class ".length()).trim();
                int spaceIndex = afterClass.indexOf(' ');
                return spaceIndex == -1 ? afterClass.replace("{", "").trim() : afterClass.substring(0, spaceIndex);
            })
            .orElseThrow();
        return packageName + "." + simpleName;
    }

    @Test
    void findsAPublicStaticVoidMainInAnOrdinaryClass() throws IOException {
        byte[] jarBytes = buildJar(
            "package com.example.fixture;\npublic class HasMain { public static void main(String[] args) { } }"
        );
        List<String> entryClasses = new JarEntryClassScanner().findEntryClasses(jarBytes);
        assertEquals(List.of("com.example.fixture.HasMain"), entryClasses);
    }

    @Test
    void ignoresClassesWithoutAMainMethod() throws IOException {
        byte[] jarBytes = buildJar(
            "package com.example.fixture;\npublic class NoMain { public void doStuff() { } }"
        );
        List<String> entryClasses = new JarEntryClassScanner().findEntryClasses(jarBytes);
        assertEquals(List.of(), entryClasses);
    }

    @Test
    void ignoresMainMethodsWithTheWrongSignature() throws IOException {
        byte[] jarBytes = buildJar(
            "package com.example.fixture;\npublic class InstanceMain { public void main(String[] args) { } }",
            "package com.example.fixture;\nclass NonStaticHelper { static void main(int notAStringArray) { } }"
        );
        List<String> entryClasses = new JarEntryClassScanner().findEntryClasses(jarBytes);
        assertEquals(List.of(), entryClasses);
    }

    @Test
    void filtersOutMainMethodsUnderVendorPackagePrefixes() throws IOException {
        byte[] jarBytes = buildJar(
            "package org.apache.fixture;\npublic class VendorMain { public static void main(String[] args) { } }",
            "package com.example.fixture;\npublic class RealEntryPoint { public static void main(String[] args) { } }"
        );
        List<String> entryClasses = new JarEntryClassScanner().findEntryClasses(jarBytes);
        assertEquals(List.of("com.example.fixture.RealEntryPoint"), entryClasses);
    }

    @Test
    void sortsMultipleEntryClassesAlphabetically() throws IOException {
        byte[] jarBytes = buildJar(
            "package com.example.fixture;\npublic class ZLast { public static void main(String[] args) { } }",
            "package com.example.fixture;\npublic class AFirst { public static void main(String[] args) { } }"
        );
        List<String> entryClasses = new JarEntryClassScanner().findEntryClasses(jarBytes);
        assertEquals(List.of("com.example.fixture.AFirst", "com.example.fixture.ZLast"), entryClasses);
    }
}
