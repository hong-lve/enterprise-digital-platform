package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("flink_jar")
public class FlinkJarEntity {
    private Long id;
    private String name;
    private String originalName;
    private String storedName;
    private String storagePath;
    private Long sizeBytes;
    private String description;
    private String uploader;
    private LocalDateTime createdAt;
    // Only set for jars created via "在线编写" (FlinkJarController.compile()/
    // recompile()) - null for plain file uploads, which never have source to
    // store. The JAR 包管理 frontend uses sourceCode's presence to decide
    // whether to offer a "查看/编辑代码" action for a given row.
    private String className;
    private String sourceCode;
    private String targetType;
}
