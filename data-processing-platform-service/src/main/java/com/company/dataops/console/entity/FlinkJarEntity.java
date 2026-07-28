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
}
