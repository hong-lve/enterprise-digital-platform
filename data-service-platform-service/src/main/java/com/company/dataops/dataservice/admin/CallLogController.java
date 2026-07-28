package com.company.dataops.dataservice.admin;

import com.company.dataops.dataservice.common.ApiResponse;
import com.company.dataops.dataservice.domain.CallLogRecord;
import com.company.dataops.dataservice.repository.CallLogRepository;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/data-service-admin/call-logs")
public class CallLogController {
    private final CallLogRepository repository;

    public CallLogController(CallLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public ApiResponse<List<CallLogRecord>> list(@RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.ok(repository.findRecent(Math.max(1, Math.min(limit, 500))));
    }
}
