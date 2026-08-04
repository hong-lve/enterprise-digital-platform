package com.company.dataops.console.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;

public interface HistoryRetentionMapper {
    @Delete("DELETE FROM data_quality_violation WHERE detected_at < #{before} LIMIT #{limit}")
    int deleteDataQuality(@Param("before") LocalDateTime before, @Param("limit") int limit);

    @Delete("""
        DELETE FROM flink_checkpoint_history
        WHERE created_at < #{before} AND (external_path IS NULL OR disposed = 1)
        LIMIT #{limit}
        """)
    int deleteCheckpoints(@Param("before") LocalDateTime before, @Param("limit") int limit);

    @Delete("DELETE FROM recovery_event WHERE occurred_at < #{before} LIMIT #{limit}")
    int deleteRecoveryEvents(@Param("before") LocalDateTime before, @Param("limit") int limit);

    @Delete("DELETE FROM audit_log WHERE occurred_at < #{before} LIMIT #{limit}")
    int deleteAuditLogs(@Param("before") LocalDateTime before, @Param("limit") int limit);

    @Delete("DELETE FROM job_operation_request WHERE completed_at < #{before} AND status <> 'RUNNING' LIMIT #{limit}")
    int deleteJobOperations(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
