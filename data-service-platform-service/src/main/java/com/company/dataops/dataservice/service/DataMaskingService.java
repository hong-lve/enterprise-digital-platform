package com.company.dataops.dataservice.service;

import com.company.dataops.dataservice.domain.DatasetColumnPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class DataMaskingService {
    public List<Map<String, Object>> apply(
        List<Map<String, Object>> rows,
        List<DatasetColumnPolicy> policies
    ) {
        if (rows.isEmpty() || policies.isEmpty()) {
            return rows;
        }
        Map<String, DatasetColumnPolicy> byColumn = new LinkedHashMap<>();
        for (DatasetColumnPolicy policy : policies) {
            byColumn.put(policy.columnName().toLowerCase(Locale.ROOT), policy);
        }

        List<Map<String, Object>> protectedRows = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> protectedRow = new LinkedHashMap<>();
            row.forEach((column, value) -> {
                DatasetColumnPolicy policy = byColumn.get(column.toLowerCase(Locale.ROOT));
                if (policy == null) {
                    protectedRow.put(column, value);
                } else if ("MASK".equals(policy.action())) {
                    protectedRow.put(column, mask(value, policy.maskType()));
                }
            });
            protectedRows.add(protectedRow);
        }
        return protectedRows;
    }

    private Object mask(Object value, String maskType) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value);
        return switch (maskType == null ? "FULL" : maskType.toUpperCase(Locale.ROOT)) {
            case "PARTIAL" -> partial(text);
            case "EMAIL" -> email(text);
            case "PHONE" -> phone(text);
            case "HASH" -> hash(text);
            default -> "******";
        };
    }

    private String partial(String value) {
        if (value.length() <= 2) {
            return "******";
        }
        if (value.length() <= 4) {
            return value.charAt(0) + "****" + value.charAt(value.length() - 1);
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private String email(String value) {
        int separator = value.indexOf('@');
        if (separator <= 0) {
            return partial(value);
        }
        String local = value.substring(0, separator);
        return local.charAt(0) + "****" + value.substring(separator);
    }

    private String phone(String value) {
        if (value.length() < 7) {
            return partial(value);
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 4);
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
