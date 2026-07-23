package com.company.dataops.console.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("flink_jar_version")
public class FlinkJarVersionEntity {
    private Long id;
    private Long jarId;
    private String originalName;
    private String storedName;
    private String storagePath;
    private Long sizeBytes;
    private String uploader;
    private LocalDateTime createdAt;
}
