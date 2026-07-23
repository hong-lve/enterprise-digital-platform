package com.company.dataops.console.service.query;

/** One row of the "Key 浏览" panel - RedisConnectionService.listKeysWithMeta(). ttlSeconds is -1 for no expiry. */
public record RedisKeyView(String key, String type, Long ttlSeconds) {
}
