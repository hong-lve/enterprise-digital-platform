package com.company.dataops.console.service.storage;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Backs JAR 包管理's "在线编写" flow: compiles a single Java source file
 * in-process (javax.tools.JavaCompiler - the JDK's own compiler API, no
 * shelling out to javac) against a fixed classpath (Flink's own API jars
 * plus one base jar), then merges the resulting .class file(s) into a copy
 * of that same base jar to produce a self-contained, submittable Flink job
 * jar - the same shape FlinkJarController already stores via
 * JarStorageService, just assembled here instead of uploaded as a file.
 * The base jar is selected per compile via targetType: one of the 5
 * existing single-driver cdc-mirror-* module jars (CLICKHOUSE/ORACLE/
 * MYSQL/REDIS/DORIS, ~20-26MB, that module's own demo job class excluded)
 * when the user's code only needs one driver, or the full cdc-mirror-
 * buildkit jar (all five drivers merged, ~38MB) as the "ALL" fallback for
 * code that genuinely needs more than one.
 *
 * Deliberately NOT a general "run arbitrary Maven coordinates" build
 * service - the classpath is fixed to what these pre-built jars already
 * bundle (Kafka connector, jackson, the JDBC driver(s) this platform's
 * sinks use, CdcMirrorSupport) plus Flink's own APIs. Letting a user pick
 * arbitrary dependencies would mean resolving them from the network at
 * request time - a real supply-chain risk for a feature whose whole point
 * is "paste code into a web form" - so custom sink logic beyond what's
 * already bundled here still needs a real Maven module built and uploaded
 * by hand, same as the five cdc-mirror-* jars were.
 *
 * Compiling untrusted-ish source in-process is safe in a way *running* it
 * wouldn't be: javac's compilation phase (with annotation processing off,
 * the default here since none of this platform's dependencies register
 * one) is a static bytecode transformation, not an execution of the
 * submitted code - nothing in compileAndPackage() below ever invokes a
 * method from the user's class.
 *
 * debugRun() below is the one place that actually executes what got
 * compiled - deliberately never in this JVM. It's launched as a genuinely
 * separate `java` process (ProcessBuilder, not a thread), time-boxed and
 * force-killed via Process.destroyForcibly() on timeout: a hung/runaway/
 * malicious script in a forked OS process can be reaped unconditionally
 * from the parent, the way a stuck *thread* in this process (Thread.stop()
 * being unsafe and effectively unusable since Java 9) cannot be. It's still
 * running with this same OS user's privileges, no container/sandbox - the
 * boundary this relies on is the same one jar upload already does
 * (realtime:jar:upload is only ever granted to operators already trusted to
 * put arbitrary code on the Flink cluster), not an assumption that the code
 * itself is safe. This only moves *when* that already-trusted code first
 * runs (a bounded local trial here, vs. only after a manual "启动" click on
 * the real Flink cluster) - it doesn't hand the capability to anyone new.
 */
@Component
public class JavaJobBuildService {
    private static final int MAX_SOURCE_LENGTH = 500_000;
    private static final int DEBUG_RUN_TIMEOUT_SECONDS = 15;
    private static final int MAX_DEBUG_OUTPUT_CHARS = 20_000;

    // Exact env.java.opts.all this platform's own Flink cluster launches
    // with (docker/bigdata's flink-jobmanager/flink-taskmanager images),
    // read live from a Flink SQL Gateway session's own reported
    // Configuration - see debugRun()'s own comment for why a bare
    // subprocess needs these spelled out explicitly.
    private static final String[] JVM_MODULE_FLAGS = {
        "--add-exports=java.base/sun.net.util=ALL-UNNAMED",
        "--add-exports=java.rmi/sun.rmi.registry=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-exports=java.security.jgss/sun.security.krb5=ALL-UNNAMED",
        "--add-opens=java.base/java.lang=ALL-UNNAMED",
        "--add-opens=java.base/java.net=ALL-UNNAMED",
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-opens=java.base/java.nio=ALL-UNNAMED",
        "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED",
        "--add-opens=java.base/java.lang.reflect=ALL-UNNAMED",
        "--add-opens=java.base/java.text=ALL-UNNAMED",
        "--add-opens=java.base/java.time=ALL-UNNAMED",
        "--add-opens=java.base/java.util=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED",
        "--add-opens=java.base/java.util.concurrent.locks=ALL-UNNAMED"
    };

    /**
     * One of the 5 pre-existing cdc-mirror-* module jars a "目标数据源类型"
     * selection assembles into, instead of always the full buildkit jar.
     * excludeClassPrefix is that module's OWN hand-written job class's
     * simple name (e.g. "ClickHouseMirrorJob") - all 5 modules share the
     * exact same package (com.company.flinkjobs.cdcmirror) as each other
     * and as CdcMirrorSupport itself, so a package-prefix exclude would
     * also strip the shared support classes every module's jar legitimately
     * needs; matching on the specific class's simple name (plus its
     * `$inner` variants) is what actually isolates just that one module's
     * own demo job from the shared classes copied alongside it.
     */
    private record SinkJarConfig(Path jarPath, String excludeClassPrefix) {
    }

    private final Path buildkitJarPath;
    private final Map<String, SinkJarConfig> sinkJarConfigs;
    private final List<Path> compileOnlyJarPaths;
    private final List<Path> debugRunExtraJarPaths;

    public JavaJobBuildService(
        @Value("${platform.bigdata.java-build.buildkit-jar-path}") String buildkitJarPath,
        @Value("${platform.bigdata.java-build.sink-jar-clickhouse}") String sinkJarClickhouse,
        @Value("${platform.bigdata.java-build.sink-jar-oracle}") String sinkJarOracle,
        @Value("${platform.bigdata.java-build.sink-jar-mysql}") String sinkJarMysql,
        @Value("${platform.bigdata.java-build.sink-jar-redis}") String sinkJarRedis,
        @Value("${platform.bigdata.java-build.sink-jar-doris}") String sinkJarDoris,
        @Value("${platform.bigdata.java-build.compile-only-jars}") String compileOnlyJars,
        @Value("${platform.bigdata.java-build.debug-run-extra-jars}") String debugRunExtraJars
    ) {
        this.buildkitJarPath = Path.of(buildkitJarPath);
        Map<String, SinkJarConfig> configs = new LinkedHashMap<>();
        configs.put("CLICKHOUSE", new SinkJarConfig(Path.of(sinkJarClickhouse), "ClickHouseMirrorJob"));
        configs.put("ORACLE", new SinkJarConfig(Path.of(sinkJarOracle), "OracleMirrorJob"));
        configs.put("MYSQL", new SinkJarConfig(Path.of(sinkJarMysql), "MySqlMirrorJob"));
        configs.put("REDIS", new SinkJarConfig(Path.of(sinkJarRedis), "RedisMirrorJob"));
        configs.put("DORIS", new SinkJarConfig(Path.of(sinkJarDoris), "DorisMirrorJob"));
        this.sinkJarConfigs = configs;
        this.compileOnlyJarPaths = Arrays.stream(compileOnlyJars.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Path::of).toList();
        this.debugRunExtraJarPaths = Arrays.stream(debugRunExtraJars.split(",")).map(String::trim).filter(s -> !s.isEmpty()).map(Path::of).toList();
    }

    public record CompileResult(byte[] jarBytes) {
    }

    /**
     * targetType selects which pre-existing module jar gets used as the
     * base for assembly - one of CLICKHOUSE/ORACLE/MYSQL/REDIS/DORIS for
     * that module's own (single-driver, ~20-26MB) footprint, or null/blank/
     * "ALL" to fall back to the buildkit jar (all five drivers merged,
     * ~38MB) for a job that genuinely needs more than one.
     */
    public CompileResult compileAndPackage(String className, String sourceCode, String targetType) {
        if (!className.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "入口类名格式不合法，需要是合法的 Java 全限定类名");
        }
        if (sourceCode == null || sourceCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "源代码不能为空");
        }
        if (sourceCode.length() > MAX_SOURCE_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "源代码过长");
        }
        SinkJarConfig sinkJarConfig = resolveTargetJar(targetType);
        if (!Files.isReadable(sinkJarConfig.jarPath())) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "构建工具包未就绪：" + sinkJarConfig.jarPath());
        }

        Path workDir;
        try {
            workDir = Files.createTempDirectory("java-job-build-");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        try {
            Path sourceDir = workDir.resolve("src");
            Path outputDir = workDir.resolve("out");
            Files.createDirectories(sourceDir);
            Files.createDirectories(outputDir);

            String relativePath = className.replace('.', '/') + ".java";
            Path sourceFile = sourceDir.resolve(relativePath);
            Files.createDirectories(sourceFile.getParent());
            Files.writeString(sourceFile, sourceCode, StandardCharsets.UTF_8);

            compile(sourceFile, outputDir, sinkJarConfig.jarPath());

            byte[] jarBytes = assembleJar(outputDir, className, sinkJarConfig);
            return new CompileResult(jarBytes);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private SinkJarConfig resolveTargetJar(String targetType) {
        if (targetType == null || targetType.isBlank() || "ALL".equalsIgnoreCase(targetType)) {
            return new SinkJarConfig(buildkitJarPath, null);
        }
        SinkJarConfig config = sinkJarConfigs.get(targetType.toUpperCase(Locale.ROOT));
        if (config == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的目标数据源类型：" + targetType);
        }
        return config;
    }

    /**
     * Compiles then actually runs the result for up to
     * DEBUG_RUN_TIMEOUT_SECONDS as a forked `java` process - see this
     * class's own javadoc for why a forked process (not a thread) is the
     * safe way to do this. programArgs is split on whitespace the same
     * naive way CdcMirrorSupport.arg() itself expects (--key value pairs,
     * no quoting support) - consistent with every other jar's programArgs
     * field in this platform (FlinkStreamJobEntity), not a new convention.
     * Flink's getExecutionEnvironment() automatically falls back to an
     * embedded local execution when it detects it isn't running inside an
     * actual cluster (confirmed live) - the user's own main() method
     * doesn't need to know or care that this is a trial run rather than a
     * real cluster submission.
     */
    public String debugRun(byte[] jarBytes, String className, String programArgs) {
        Path jarFile;
        try {
            jarFile = Files.createTempFile("java-job-debug-", ".jar");
            Files.write(jarFile, jarBytes);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        try {
            String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            // Unlike the packaged jar itself (meant for a real Flink cluster,
            // whose lib/ already supplies flink-streaming-java/flink-clients/
            // flink-connector-base - exactly why those aren't bundled into
            // it), this bare `java -cp` subprocess has no cluster underneath
            // it at all. Confirmed live in two separate rounds: without
            // compileOnlyJarPaths, fails immediately on SinkFunction; with
            // that but without debugRunExtraJarPaths (flink-dist), fails
            // once execution actually starts on RecordEmitter instead
            // (compilation never touches that class directly, only
            // KafkaSource's own internals reference it, so the first
            // failure mode didn't catch it).
            List<String> classpathEntries = new ArrayList<>(debugRunExtraJarPaths.stream().map(Path::toString).toList());
            classpathEntries.addAll(compileOnlyJarPaths.stream().map(Path::toString).toList());
            classpathEntries.add(jarFile.toString());
            String classpath = String.join(File.pathSeparator, classpathEntries);
            List<String> command = new ArrayList<>(List.of(javaExecutable));
            // Same --add-opens/--add-exports flags a real Flink cluster JVM
            // always launches with (pulled live from this cluster's own SQL
            // Gateway session config, env.java.opts.all) - without them,
            // Flink's Kryo-based serialization (used for the DataStream
            // pipeline's internal record copying) fails with
            // InaccessibleObjectException on java.base internals under the
            // JDK 9+ module system, confirmed live: the job got as far as
            // actually connecting to Redis before hitting this. A real
            // cluster's JobManager/TaskManager processes already carry these
            // flags from Flink's own startup scripts; this bare subprocess
            // has to be told explicitly since nothing else supplies them.
            command.addAll(List.of(JVM_MODULE_FLAGS));
            command.addAll(List.of("-cp", classpath, className));
            if (programArgs != null && !programArgs.isBlank()) {
                command.addAll(Arrays.stream(programArgs.trim().split("\\s+")).toList());
            }

            ProcessBuilder processBuilder = new ProcessBuilder(command).redirectErrorStream(true);
            Process process = processBuilder.start();
            StringBuilder output = new StringBuilder();
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null && output.length() < MAX_DEBUG_OUTPUT_CHARS) {
                        output.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                    // process died/was killed mid-read - whatever was captured up to that point is still returned below
                }
            });
            outputReader.setDaemon(true);
            outputReader.start();

            boolean finishedInTime = process.waitFor(DEBUG_RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finishedInTime) {
                process.destroyForcibly();
                output.append("\n(运行超过 ").append(DEBUG_RUN_TIMEOUT_SECONDS).append(" 秒，已自动终止 - 这对一个持续读 Kafka 的流作业是正常的，不代表失败)\n");
            } else {
                output.append("\n(进程已退出，退出码 ").append(process.exitValue()).append(")\n");
            }
            outputReader.join(TimeUnit.SECONDS.toMillis(2));
            if (output.length() > MAX_DEBUG_OUTPUT_CHARS) {
                output.setLength(MAX_DEBUG_OUTPUT_CHARS);
                output.append("\n(输出过长，已截断)\n");
            }
            return output.toString();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "调试运行被中断");
        } finally {
            try {
                Files.deleteIfExists(jarFile);
            } catch (IOException ignored) {
                // best-effort temp file cleanup, same as deleteRecursively() elsewhere in this class
            }
        }
    }

    private void compile(Path sourceFile, Path outputDir, Path basePath) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "运行环境缺少 JDK 编译器（javax.tools.JavaCompiler 不可用）");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8)) {
            List<Path> classpath = new ArrayList<>(compileOnlyJarPaths);
            classpath.add(basePath);
            fileManager.setLocationFromPaths(javax.tools.StandardLocation.CLASS_PATH, classpath);
            fileManager.setLocationFromPaths(javax.tools.StandardLocation.CLASS_OUTPUT, List.of(outputDir));

            Iterable<? extends JavaFileObject> compilationUnits = fileManager.getJavaFileObjectsFromPaths(List.of(sourceFile));
            JavaCompiler.CompilationTask task = compiler.getTask(null, fileManager, diagnostics, null, null, compilationUnits);
            boolean success = task.call();

            List<Diagnostic<? extends JavaFileObject>> errors = diagnostics.getDiagnostics().stream()
                .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .toList();
            if (!success || !errors.isEmpty()) {
                String message = errors.stream()
                    .map(d -> "第 " + d.getLineNumber() + " 行：" + d.getMessage(null))
                    .reduce((a, b) -> a + "\n" + b)
                    .orElse("编译失败");
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Copies every entry from the base jar (buildkit, or one of the 5
     * per-sink module jars) into a new jar, adds the freshly-compiled
     * .class file(s), sets Main-Class to the user's entry class. When
     * basing off a per-sink module jar, that module's own hand-written
     * demo job class (and its `$inner` classes) is skipped - it's dead
     * weight in a jar whose Main-Class now points at the user's own class
     * instead, and its simple name could otherwise collide if a user's
     * source happens to reuse it.
     */
    private byte[] assembleJar(Path outputDir, String mainClass, SinkJarConfig sinkJarConfig) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);

        try (JarOutputStream jarOut = new JarOutputStream(buffer, manifest)) {
            try (JarInputStream jarIn = new JarInputStream(Files.newInputStream(sinkJarConfig.jarPath()))) {
                java.util.jar.JarEntry entry;
                while ((entry = jarIn.getNextJarEntry()) != null) {
                    if (entry.getName().equals("META-INF/MANIFEST.MF") || entry.isDirectory() || isModuleOwnJobClass(entry.getName(), sinkJarConfig.excludeClassPrefix())) {
                        continue;
                    }
                    jarOut.putNextEntry(new java.util.jar.JarEntry(entry.getName()));
                    jarIn.transferTo(jarOut);
                    jarOut.closeEntry();
                }
            }
            try (Stream<Path> classFiles = Files.walk(outputDir)) {
                for (Path classFile : classFiles.filter(Files::isRegularFile).toList()) {
                    String entryName = outputDir.relativize(classFile).toString().replace('\\', '/');
                    jarOut.putNextEntry(new java.util.jar.JarEntry(entryName));
                    Files.copy(classFile, jarOut);
                    jarOut.closeEntry();
                }
            }
        }
        return buffer.toByteArray();
    }

    private boolean isModuleOwnJobClass(String entryName, String excludeClassPrefix) {
        if (excludeClassPrefix == null) {
            return false;
        }
        String simpleName = entryName.substring(entryName.lastIndexOf('/') + 1);
        return simpleName.equals(excludeClassPrefix + ".class") || simpleName.startsWith(excludeClassPrefix + "$");
    }

    private void deleteRecursively(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.delete(path);
                } catch (IOException ignored) {
                    // best-effort cleanup of a temp dir - a leftover file here
                    // doesn't affect correctness, just OS temp-dir tidiness
                }
            });
        } catch (IOException ignored) {
            // same as above
        }
    }
}
