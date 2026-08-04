package com.company.dataops.console.mapper;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PlatformLeaseMapper {
    @Insert("INSERT IGNORE INTO platform_lease(lock_name) VALUES(#{name})")
    int ensureExists(@Param("name") String name);

    @Update("""
        UPDATE platform_lease
        SET lock_owner = #{owner},
            lease_until = TIMESTAMPADD(SECOND, #{leaseSeconds}, NOW(3)),
            fencing_token = fencing_token + 1
        WHERE lock_name = #{name}
          AND (lease_until IS NULL OR lease_until < NOW(3) OR lock_owner = #{owner})
        """)
    int acquire(@Param("name") String name, @Param("owner") String owner, @Param("leaseSeconds") long leaseSeconds);

    @Select("SELECT fencing_token FROM platform_lease WHERE lock_name = #{name} AND lock_owner = #{owner}")
    Long fencingToken(@Param("name") String name, @Param("owner") String owner);

    @Update("""
        UPDATE platform_lease SET lock_owner = NULL, lease_until = NULL
        WHERE lock_name = #{name} AND lock_owner = #{owner} AND fencing_token = #{token}
        """)
    int release(@Param("name") String name, @Param("owner") String owner, @Param("token") long token);

    record LeaseRow(String lockName, String lockOwner, LocalDateTime leaseUntil, Long fencingToken) {
    }
}
