package com.company.dataops.console.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.company.dataops.console.entity.RecoveryStateEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface RecoveryStateMapper extends BaseMapper<RecoveryStateEntity> {
    @Update("""
        UPDATE recovery_state
        SET lease_owner = #{owner},
            lease_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW())
        WHERE id = #{id}
          AND circuit_state <> 'TRIPPED'
          AND (lease_until IS NULL OR lease_until < NOW())
        """)
    int acquireLease(@Param("id") Long id, @Param("owner") String owner, @Param("leaseSeconds") int leaseSeconds);

    @Update("""
        UPDATE recovery_state
        SET attempts_in_tier = attempts_in_tier + 1, last_attempt_at = NOW()
        WHERE id = #{id} AND lease_owner = #{owner}
        """)
    int recordAttempt(@Param("id") Long id, @Param("owner") String owner);

    @Update("""
        UPDATE recovery_state
        SET lease_owner = NULL, lease_until = NULL
        WHERE id = #{id} AND lease_owner = #{owner}
        """)
    int releaseLease(@Param("id") Long id, @Param("owner") String owner);

    @Update("""
        UPDATE recovery_state
        SET tier = 1, attempts_in_tier = 0, circuit_state = 'OK',
            last_attempt_at = NULL, lease_owner = NULL, lease_until = NULL
        WHERE id = #{id}
        """)
    int resetState(@Param("id") Long id);
}
