package com.company.dataops.console.common;

import java.util.List;

public record PageResult<T>(long total, List<T> records) {
}
