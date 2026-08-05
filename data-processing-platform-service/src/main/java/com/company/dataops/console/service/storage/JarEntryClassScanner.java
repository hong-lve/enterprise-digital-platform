package com.company.dataops.console.service.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.stereotype.Component;

/**
 * Lists the classes inside an uploaded Flink JAR that expose a runnable
 * `public static void main(String[])`, so the "入口类" field can offer a
 * dropdown instead of requiring the fully-qualified class name to be typed
 * (and gotten exactly right) by hand. Uses Spring's own shaded ASM
 * (org.springframework.asm, bundled in spring-core - confirmed present in
 * the resolved spring-core-6.1.5.jar) purely to read each class's method
 * table; unlike loading the class via a ClassLoader, this never executes
 * the class's static initializer, so scanning an uploaded jar can't run
 * arbitrary code as a side effect of listing it.
 */
@Component
public class JarEntryClassScanner {
    private static final int MAIN_METHOD_ACCESS = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
    private static final String MAIN_METHOD_DESCRIPTOR = "([Ljava/lang/String;)V";

    /**
     * Confirmed live against this project's own shaded cdc-mirror-job jar:
     * a fat/shaded jar bundles every transitive dependency's .class files
     * too, and a surprising number of common libraries ship their own
     * incidental public static void main() (CLI helpers, debug entry
     * points, benchmark harnesses) that have nothing to do with the jar's
     * actual Flink entry points - e.g. oracle.jdbc.OracleDriver,
     * org.apache.kafka.clients.consumer.ConsumerConfig, ANTLR's TestRig.
     * Without this filter a 5-class jar surfaced 30 candidates, defeating
     * the point of suggesting entry classes at all. This is a best-effort
     * denylist of well-known vendor package roots, not exhaustive; the
     * entry-class field stays a free-text autocomplete so a real class that
     * happens to live under one of these roots can still be typed by hand.
     */
    private static final Set<String> VENDOR_PACKAGE_PREFIXES = Set.of(
        "org.apache.", "org.antlr.", "org.xerial.", "net.jpountz.", "oracle.",
        "com.clickhouse.", "io.netty.", "com.fasterxml.", "org.springframework.",
        "org.slf4j.", "ch.qos.", "com.google.", "com.zaxxer.", "org.postgresql.",
        "com.mysql.", "org.rocksdb.", "io.confluent.",
        "javax.", "java.", "jdk.", "sun.", "com.sun."
    );

    public List<String> findEntryClasses(byte[] jarBytes) {
        List<String> entryClasses = new ArrayList<>();
        try (JarInputStream jarInputStream = new JarInputStream(new ByteArrayInputStream(jarBytes))) {
            JarEntry entry;
            while ((entry = jarInputStream.getNextJarEntry()) != null) {
                if (entry.isDirectory() || !entry.getName().endsWith(".class") || entry.getName().equals("module-info.class")) {
                    continue;
                }
                String className = scanClassForMainMethod(jarInputStream.readAllBytes());
                if (className != null && !isVendorClass(className)) {
                    entryClasses.add(className);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        entryClasses.sort(String::compareTo);
        return entryClasses;
    }

    private boolean isVendorClass(String className) {
        return VENDOR_PACKAGE_PREFIXES.stream().anyMatch(className::startsWith);
    }

    private String scanClassForMainMethod(byte[] classBytes) {
        boolean[] hasMainMethod = {false};
        String[] internalName = {null};
        ClassReader classReader;
        try {
            classReader = new ClassReader(classBytes);
        } catch (IllegalArgumentException malformedClass) {
            return null; // not a real class file (e.g. multi-release jar metadata) - skip rather than fail the whole scan
        }
        classReader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
                internalName[0] = name;
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
                if ("main".equals(name) && MAIN_METHOD_DESCRIPTOR.equals(descriptor) && (access & MAIN_METHOD_ACCESS) == MAIN_METHOD_ACCESS) {
                    hasMainMethod[0] = true;
                }
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return hasMainMethod[0] && internalName[0] != null ? internalName[0].replace('/', '.') : null;
    }
}
