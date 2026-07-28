package com.company.dataops.dataservice.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.company.dataops.dataservice.domain.DatasetColumnPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DataMaskingServiceTest {
    private final DataMaskingService service = new DataMaskingService();

    @Test
    void hidesAndMasksColumnsCaseInsensitively() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("USER_ID", 42);
        row.put("phone", "13812345678");
        row.put("email", "alice@example.com");
        row.put("name", "Alice");

        List<Map<String, Object>> result = service.apply(
            List.of(row),
            List.of(
                new DatasetColumnPolicy("user_id", "HIDE", null),
                new DatasetColumnPolicy("PHONE", "MASK", "PHONE"),
                new DatasetColumnPolicy("email", "MASK", "EMAIL")
            )
        );

        assertFalse(result.get(0).containsKey("USER_ID"));
        assertEquals("138****5678", result.get(0).get("phone"));
        assertEquals("a****@example.com", result.get(0).get("email"));
        assertEquals("Alice", result.get(0).get("name"));
    }

    @Test
    void supportsFullPartialAndDeterministicHashMasking() {
        Map<String, Object> row = Map.of(
            "secret", "value",
            "card", "6222021234567890",
            "identity", "A123"
        );
        List<DatasetColumnPolicy> policies = List.of(
            new DatasetColumnPolicy("secret", "MASK", "FULL"),
            new DatasetColumnPolicy("card", "MASK", "PARTIAL"),
            new DatasetColumnPolicy("identity", "MASK", "HASH")
        );

        Map<String, Object> first = service.apply(List.of(row), policies).get(0);
        Map<String, Object> second = service.apply(List.of(row), policies).get(0);

        assertEquals("******", first.get("secret"));
        assertEquals("62****90", first.get("card"));
        assertEquals(first.get("identity"), second.get("identity"));
        assertEquals(64, String.valueOf(first.get("identity")).length());
    }
}
