package com.company.dataops.console.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.dataops.console.entity.AlertRetryQueueEntity;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface AlertRetryQueueMapper extends BaseMapper<AlertRetryQueueEntity> {
    @Select("""
        SELECT * FROM alert_retry_queue
        WHERE (status = 'PENDING' AND next_attempt_at <= NOW())
           OR (status = 'PROCESSING' AND lock_until < NOW())
        ORDER BY next_attempt_at ASC LIMIT #{limit}
        """)
    List<AlertRetryQueueEntity> selectClaimCandidates(@Param("limit") int limit);

    @Update("""
        UPDATE alert_retry_queue
        SET status = 'PROCESSING', lock_owner = #{owner},
            lock_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW())
        WHERE id = #{id}
          AND ((status = 'PENDING' AND next_attempt_at <= NOW())
            OR (status = 'PROCESSING' AND lock_until < NOW()))
        """)
    int claim(@Param("id") Long id, @Param("owner") String owner, @Param("leaseSeconds") int leaseSeconds);
}
