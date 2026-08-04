package com.company.dataops.console.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface JobOperationRequestMapper {
    @Insert("""
        INSERT IGNORE INTO job_operation_request
          (idempotency_key, entity_type, entity_id, operation_type, status, fencing_token)
        VALUES (#{key}, #{entityType}, #{entityId}, #{operationType}, 'RUNNING', #{token})
        """)
    int register(@Param("key") String key, @Param("entityType") String entityType,
                 @Param("entityId") Long entityId, @Param("operationType") String operationType,
                 @Param("token") long token);

    @Update("""
        UPDATE job_operation_request SET status = 'SUCCEEDED', completed_at = NOW(3)
        WHERE idempotency_key = #{key} AND fencing_token = #{token} AND status = 'RUNNING'
        """)
    int markSucceeded(@Param("key") String key, @Param("token") long token);

    @Update("""
        UPDATE job_operation_request
        SET status = 'FAILED', error_message = #{error}, completed_at = NOW(3)
        WHERE idempotency_key = #{key} AND fencing_token = #{token} AND status = 'RUNNING'
        """)
    int markFailed(@Param("key") String key, @Param("token") long token, @Param("error") String error);
}
