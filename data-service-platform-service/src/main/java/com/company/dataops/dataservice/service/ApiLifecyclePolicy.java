package com.company.dataops.dataservice.service;

import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ApiLifecyclePolicy {
    private static final Set<String> ROLLBACK_SOURCES = Set.of("PUBLISHED", "ARCHIVED");

    public void assertEditable(String latestVersionStatus) {
        if ("PENDING_APPROVAL".equals(latestVersionStatus)) {
            throw conflict("当前版本正在审批，不能继续修改");
        }
    }

    public void assertSubmittable(String versionStatus) {
        if (!"DRAFT".equals(versionStatus)) {
            throw conflict("只有草稿版本可以提交审批");
        }
    }

    public void assertReviewable(String versionStatus, String submitter, String reviewer) {
        if (!"PENDING_APPROVAL".equals(versionStatus)) {
            throw conflict("该版本不在待审批状态");
        }
        if (reviewer != null && reviewer.equals(submitter)) {
            throw conflict("提交人不能审批自己的版本");
        }
    }

    public void assertRollbackSource(String versionStatus) {
        if (!ROLLBACK_SOURCES.contains(versionStatus)) {
            throw conflict("只能回滚到曾经发布过的版本");
        }
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
