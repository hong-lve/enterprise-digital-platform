package com.company.dataops.console.api;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.company.dataops.console.common.ApiResponse;
import com.company.dataops.console.common.PageResult;
import com.company.dataops.console.entity.FlinkJarEntity;
import com.company.dataops.console.entity.FlinkJarVersionEntity;
import com.company.dataops.console.entity.FlinkStreamJobEntity;
import com.company.dataops.console.mapper.FlinkJarMapper;
import com.company.dataops.console.mapper.FlinkJarVersionMapper;
import com.company.dataops.console.mapper.FlinkStreamJobMapper;
import com.company.dataops.console.service.storage.JarEntryClassScanner;
import com.company.dataops.console.service.storage.JarStorageService;
import com.company.dataops.console.service.storage.JavaJobBuildService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * JAR bytes live in the S3-compatible object store behind JarStorageService,
 * not on this JVM's local disk - storagePath/storedName on the entity/version
 * rows hold the object key, not a filesystem path. FlinkStreamSubmissionClient
 * pulls the bytes back out of the same store when a streaming job starts
 * (see its uploadJar()), so no shared filesystem with the Flink containers
 * is required, and this controller can run behind any number of replicas.
 *
 * flink_jar always mirrors whichever version is "current" (denormalized),
 * so every existing read path (job submission, download, jar dropdowns)
 * keeps working unchanged regardless of how many versions have been
 * uploaded or restored - only reupload()/restore() ever touch those fields.
 */
@RestController
@RequestMapping("/realtime/jars")
public class FlinkJarController {
    private final FlinkJarMapper flinkJarMapper;
    private final FlinkJarVersionMapper flinkJarVersionMapper;
    private final FlinkStreamJobMapper flinkStreamJobMapper;
    private final JarStorageService jarStorageService;
    private final JarEntryClassScanner jarEntryClassScanner;
    private final JavaJobBuildService javaJobBuildService;

    public FlinkJarController(
        FlinkJarMapper flinkJarMapper,
        FlinkJarVersionMapper flinkJarVersionMapper,
        FlinkStreamJobMapper flinkStreamJobMapper,
        JarStorageService jarStorageService,
        JarEntryClassScanner jarEntryClassScanner,
        JavaJobBuildService javaJobBuildService
    ) {
        this.flinkJarMapper = flinkJarMapper;
        this.flinkJarVersionMapper = flinkJarVersionMapper;
        this.flinkStreamJobMapper = flinkStreamJobMapper;
        this.jarStorageService = jarStorageService;
        this.jarEntryClassScanner = jarEntryClassScanner;
        this.javaJobBuildService = javaJobBuildService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('realtime:jar:view')")
    public ApiResponse<PageResult<FlinkJarEntity>> jars(@RequestParam(defaultValue = "1") long current, @RequestParam(defaultValue = "50") long pageSize) {
        Page<FlinkJarEntity> page = flinkJarMapper.selectPage(Page.of(current, pageSize), new LambdaQueryWrapper<FlinkJarEntity>().orderByDesc(FlinkJarEntity::getId));
        return ApiResponse.ok(new PageResult<>(page.getTotal(), page.getRecords()));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('realtime:jar:upload')")
    public ApiResponse<FlinkJarEntity> upload(
        @RequestParam("file") MultipartFile file,
        @RequestParam String name,
        @RequestParam(required = false) String description,
        Authentication authentication
    ) throws IOException {
        if (name == null || name.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "名称不能为空");
        }
        String uploader = authentication == null ? "anonymous" : authentication.getName();
        StoredFile stored = storeUpload(file);

        FlinkJarEntity entity = new FlinkJarEntity();
        entity.setName(name);
        entity.setOriginalName(stored.originalName());
        entity.setStoredName(stored.storageKey());
        entity.setStoragePath(stored.storageKey());
        entity.setSizeBytes(stored.sizeBytes());
        entity.setDescription(description);
        entity.setUploader(uploader);
        flinkJarMapper.insert(entity);

        insertVersion(entity.getId(), stored, uploader);
        return ApiResponse.ok(entity);
    }

    /**
     * "在线编写" - compiles a single Java source file (see
     * JavaJobBuildService for the classpath/security reasoning) and stores
     * the result exactly like upload() does, so it shows up in the same
     * list and is selectable from Flink 流作业 the same way. Same
     * realtime:jar:upload permission as a file upload - compiling and
     * storing a jar is the same capability either way, the only difference
     * is where the bytes came from.
     */
    @PostMapping("/compile")
    @PreAuthorize("hasAuthority('realtime:jar:upload')")
    public ApiResponse<FlinkJarEntity> compile(@Valid @RequestBody CompileJarRequest request, Authentication authentication) {
        String uploader = authentication == null ? "anonymous" : authentication.getName();
        JavaJobBuildService.CompileResult result = javaJobBuildService.compileAndPackage(request.className(), request.sourceCode(), request.targetType());
        StoredFile stored = storeBytes(result.jarBytes(), request.className() + ".jar");

        FlinkJarEntity entity = new FlinkJarEntity();
        entity.setName(request.name());
        entity.setOriginalName(stored.originalName());
        entity.setStoredName(stored.storageKey());
        entity.setStoragePath(stored.storageKey());
        entity.setSizeBytes(stored.sizeBytes());
        entity.setDescription(request.description());
        entity.setUploader(uploader);
        flinkJarMapper.insert(entity);

        insertVersion(entity.getId(), stored, uploader);
        return ApiResponse.ok(entity);
    }

    /**
     * Compiles then actually runs the result for a bounded trial (see
     * JavaJobBuildService.debugRun's own javadoc for the process-isolation
     * safety story) - nothing gets stored here, this is purely "does my
     * code work" feedback before spending an upload/JAR-record on it.
     */
    @PostMapping("/debug-run")
    @PreAuthorize("hasAuthority('realtime:jar:upload')")
    public ApiResponse<DebugRunResponse> debugRun(@Valid @RequestBody DebugRunRequest request) {
        JavaJobBuildService.CompileResult result = javaJobBuildService.compileAndPackage(request.className(), request.sourceCode(), request.targetType());
        String output = javaJobBuildService.debugRun(result.jarBytes(), request.className(), request.programArgs());
        return ApiResponse.ok(new DebugRunResponse(output));
    }

    public record DebugRunRequest(
        @NotBlank(message = "入口类名不能为空") String className,
        @NotBlank(message = "源代码不能为空") String sourceCode,
        String programArgs,
        String targetType
    ) {
    }

    public record DebugRunResponse(String output) {
    }

    public record CompileJarRequest(
        @NotBlank(message = "名称不能为空") String name,
        String description,
        @NotBlank(message = "入口类名不能为空") String className,
        @NotBlank(message = "源代码不能为空") String sourceCode,
        String targetType
    ) {
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:jar:update')")
    public ApiResponse<FlinkJarEntity> update(@PathVariable Long id, @RequestBody UpdateJarRequest request) {
        FlinkJarEntity jar = flinkJarMapper.selectById(id);
        if (jar == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "JAR 包不存在");
        }
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "名称不能为空");
        }
        jar.setName(request.name());
        jar.setDescription(request.description());
        flinkJarMapper.updateById(jar);
        return ApiResponse.ok(jar);
    }

    public record UpdateJarRequest(String name, String description) {
    }

    @PostMapping(value = "/{id}/reupload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('realtime:jar:upload')")
    public ApiResponse<FlinkJarEntity> reupload(@PathVariable Long id, @RequestParam("file") MultipartFile file, Authentication authentication) throws IOException {
        FlinkJarEntity jar = flinkJarMapper.selectById(id);
        if (jar == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "JAR 包不存在");
        }
        String uploader = authentication == null ? "anonymous" : authentication.getName();
        StoredFile stored = storeUpload(file);

        jar.setOriginalName(stored.originalName());
        jar.setStoredName(stored.storageKey());
        jar.setStoragePath(stored.storageKey());
        jar.setSizeBytes(stored.sizeBytes());
        jar.setUploader(uploader);
        flinkJarMapper.updateById(jar);

        insertVersion(id, stored, uploader);
        return ApiResponse.ok(jar);
    }

    @GetMapping("/{id}/versions")
    @PreAuthorize("hasAuthority('realtime:jar:view')")
    public ApiResponse<List<FlinkJarVersionEntity>> versions(@PathVariable Long id) {
        List<FlinkJarVersionEntity> versions = flinkJarVersionMapper.selectList(
            new LambdaQueryWrapper<FlinkJarVersionEntity>().eq(FlinkJarVersionEntity::getJarId, id).orderByDesc(FlinkJarVersionEntity::getCreatedAt)
        );
        return ApiResponse.ok(versions);
    }

    @PostMapping("/{id}/versions/{versionId}/restore")
    @PreAuthorize("hasAuthority('realtime:jar:upload')")
    public ApiResponse<FlinkJarEntity> restore(@PathVariable Long id, @PathVariable Long versionId) {
        FlinkJarEntity jar = flinkJarMapper.selectById(id);
        if (jar == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "JAR 包不存在");
        }
        FlinkJarVersionEntity version = flinkJarVersionMapper.selectById(versionId);
        if (version == null || !version.getJarId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        }
        jar.setOriginalName(version.getOriginalName());
        jar.setStoredName(version.getStoredName());
        jar.setStoragePath(version.getStoragePath());
        jar.setSizeBytes(version.getSizeBytes());
        jar.setUploader(version.getUploader());
        flinkJarMapper.updateById(jar);
        return ApiResponse.ok(jar);
    }

    @DeleteMapping("/{id}/versions/{versionId}")
    @PreAuthorize("hasAuthority('realtime:jar:delete')")
    public ApiResponse<Void> deleteVersion(@PathVariable Long id, @PathVariable Long versionId) {
        FlinkJarEntity jar = flinkJarMapper.selectById(id);
        FlinkJarVersionEntity version = flinkJarVersionMapper.selectById(versionId);
        if (jar == null || version == null || !version.getJarId().equals(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        }
        if (version.getStoragePath().equals(jar.getStoragePath())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不能删除当前正在使用的版本，请先恢复到其他版本");
        }
        long referencingJobs = flinkStreamJobMapper.selectCount(new LambdaQueryWrapper<FlinkStreamJobEntity>().eq(FlinkStreamJobEntity::getJarPath, version.getStoragePath()));
        if (referencingJobs > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该版本正被 Flink 流作业引用，无法删除");
        }
        jarStorageService.delete(version.getStoragePath());
        flinkJarVersionMapper.deleteById(versionId);
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/entry-classes")
    @PreAuthorize("hasAuthority('realtime:jar:view')")
    public ApiResponse<List<String>> entryClasses(@PathVariable Long id) {
        FlinkJarEntity jar = flinkJarMapper.selectById(id);
        if (jar == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "JAR 包不存在");
        }
        byte[] content = jarStorageService.get(jar.getStoragePath());
        return ApiResponse.ok(jarEntryClassScanner.findEntryClasses(content));
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasAuthority('realtime:jar:view')")
    public ResponseEntity<Resource> download(@PathVariable Long id) {
        FlinkJarEntity jar = flinkJarMapper.selectById(id);
        if (jar == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "JAR 包不存在");
        }
        byte[] content = jarStorageService.get(jar.getStoragePath());
        String encodedName = URLEncoder.encode(jar.getOriginalName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''" + encodedName)
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .contentLength(content.length)
            .body(new ByteArrayResource(content));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('realtime:jar:delete')")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        FlinkJarEntity jar = flinkJarMapper.selectById(id);
        if (jar != null) {
            List<FlinkJarVersionEntity> versions = flinkJarVersionMapper.selectList(new LambdaQueryWrapper<FlinkJarVersionEntity>().eq(FlinkJarVersionEntity::getJarId, id));
            for (FlinkJarVersionEntity version : versions) {
                jarStorageService.delete(version.getStoragePath());
            }
            flinkJarVersionMapper.delete(new LambdaQueryWrapper<FlinkJarVersionEntity>().eq(FlinkJarVersionEntity::getJarId, id));
            jarStorageService.delete(jar.getStoragePath());
            flinkJarMapper.deleteById(id);
        }
        return ApiResponse.ok();
    }

    private record StoredFile(String originalName, String storageKey, long sizeBytes) {
    }

    private StoredFile storeUpload(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择要上传的文件");
        }
        String originalName = file.getOriginalFilename() == null ? "unknown" : Path.of(file.getOriginalFilename()).getFileName().toString();
        if (!originalName.toLowerCase().endsWith(".jar")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能上传 .jar 文件");
        }
        return storeBytes(file.getBytes(), originalName);
    }

    private StoredFile storeBytes(byte[] bytes, String originalName) {
        String storageKey = UUID.randomUUID() + ".jar";
        jarStorageService.put(storageKey, bytes);
        return new StoredFile(originalName, storageKey, bytes.length);
    }

    private void insertVersion(Long jarId, StoredFile stored, String uploader) {
        FlinkJarVersionEntity version = new FlinkJarVersionEntity();
        version.setJarId(jarId);
        version.setOriginalName(stored.originalName());
        version.setStoredName(stored.storageKey());
        version.setStoragePath(stored.storageKey());
        version.setSizeBytes(stored.sizeBytes());
        version.setUploader(uploader);
        flinkJarVersionMapper.insert(version);
    }
}
