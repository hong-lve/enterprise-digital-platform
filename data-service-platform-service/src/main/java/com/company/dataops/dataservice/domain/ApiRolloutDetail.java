package com.company.dataops.dataservice.domain;

import java.util.List;

public record ApiRolloutDetail(
    List<ApiRolloutRecord> rollouts,
    List<RolloutVariantMetrics> metrics,
    RolloutHealthSnapshot health,
    List<RolloutEventRecord> events
) {
}
