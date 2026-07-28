package com.company.dataops.console.service.datasource;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.company.dataops.console.entity.DataSourceEntity;
import com.company.dataops.console.mapper.DataSourceMapper;
import com.company.dataops.console.service.query.QueryResult;
import com.company.dataops.console.service.query.RedisKeyView;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.resps.Tuple;

/**
 * Redis isn't JDBC/SQL-queryable like the other four data source types (see
 * DataSourceConnectionService's own Javadoc for why that class stays plain
 * JDBC) - this is its own connection path via Jedis. "database" here means
 * Redis's numbered logical databases (0-15 by default, selected via
 * Jedis.select()), not a separate schema/catalog name. "query" means a single
 * read-only Redis command instead of SQL - execute() only allows a fixed
 * allowlist of read commands, the same defensive posture assertSelect() gives
 * the SQL side.
 */
@Service
public class RedisConnectionService {
    private static final Set<String> READONLY_COMMANDS = Set.of(
        "GET", "MGET", "HGET", "HGETALL", "HKEYS", "HVALS", "HLEN", "LRANGE", "LLEN",
        "SMEMBERS", "SCARD", "ZRANGE", "ZSCORE", "ZCARD", "TYPE", "TTL", "EXISTS",
        "STRLEN", "KEYS", "SCAN", "DBSIZE"
    );

    private final DataSourceMapper dataSourceMapper;

    public RedisConnectionService(DataSourceMapper dataSourceMapper) {
        this.dataSourceMapper = dataSourceMapper;
    }

    private Jedis openConnection(DataSourceEntity dataSource, String database) {
        try {
            Jedis jedis = new Jedis(dataSource.getHost(), dataSource.getPort());
            jedis.auth(dataSource.getUsername(), dataSource.getPassword());
            if (database != null && !database.isBlank()) {
                jedis.select(Integer.parseInt(database));
            }
            return jedis;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "连接数据源失败：" + exception.getMessage(), exception);
        }
    }

    public DataSourceEntity testConnection(DataSourceEntity dataSource) {
        String status;
        String error = null;
        try (Jedis jedis = openConnection(dataSource, null)) {
            jedis.ping();
            status = "OK";
        } catch (Exception exception) {
            status = "FAILED";
            error = exception.getMessage();
        }
        dataSourceMapper.update(null, new LambdaUpdateWrapper<DataSourceEntity>()
            .eq(DataSourceEntity::getId, dataSource.getId())
            .set(DataSourceEntity::getStatus, status)
            .set(DataSourceEntity::getLastTestError, error));
        dataSource.setStatus(status);
        dataSource.setLastTestError(error);
        return dataSource;
    }

    public List<String> listDatabases(DataSourceEntity dataSource) {
        try (Jedis jedis = openConnection(dataSource, null)) {
            Map<String, String> config = jedis.configGet("databases");
            int count = config.containsKey("databases") ? Integer.parseInt(config.get("databases")) : 16;
            List<String> databases = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                databases.add(String.valueOf(i));
            }
            return databases;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取数据库列表失败：" + exception.getMessage(), exception);
        }
    }

    /**
     * Full, uncapped count of keys matching a pattern (SCAN cursor loop run
     * to completion) - powers DataReconciliationService's Redis-target row
     * count. Deliberately separate from scanKeys() below: that one caps at
     * `limit` and stops early for the "Key 浏览" UI preview, which would
     * silently undercount a reconciliation check on any keyspace bigger
     * than the cap.
     */
    public long countKeysMatching(DataSourceEntity dataSource, String database, String pattern) {
        try (Jedis jedis = openConnection(dataSource, database)) {
            long count = 0;
            String cursor = "0";
            ScanParams params = new ScanParams().match(pattern == null || pattern.isBlank() ? "*" : pattern).count(1000);
            do {
                ScanResult<String> result = jedis.scan(cursor, params);
                count += result.getResult().size();
                cursor = result.getCursor();
            } while (!"0".equals(cursor));
            return count;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "统计 key 数量失败：" + exception.getMessage(), exception);
        }
    }

    /** Powers the "Key 浏览" panel: scan by pattern, then TYPE/TTL each match so the UI can list them without a separate round-trip per key. */
    public List<RedisKeyView> listKeysWithMeta(DataSourceEntity dataSource, String database, String pattern, int limit) {
        int capped = Math.max(1, Math.min(limit, 500));
        try (Jedis jedis = openConnection(dataSource, database)) {
            List<String> keys = scanKeys(jedis, pattern == null || pattern.isBlank() ? "*" : pattern, capped);
            List<RedisKeyView> views = new ArrayList<>();
            for (String key : keys) {
                views.add(new RedisKeyView(key, jedis.type(key), jedis.ttl(key)));
            }
            return views;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取 key 列表失败：" + exception.getMessage(), exception);
        }
    }

    /** Powers clicking a key in the "Key 浏览" panel - detects the type itself so the caller doesn't need to already know it (unlike execute(), which needs the command name up front). */
    public QueryResult getValue(DataSourceEntity dataSource, String database, String key) {
        try (Jedis jedis = openConnection(dataSource, database)) {
            String type = jedis.type(key);
            return switch (type) {
                case "string" -> singleColumn("value", List.of(nullToDash(jedis.get(key))));
                case "hash" -> mapResult("field", "value", jedis.hgetAll(key));
                case "list" -> indexedResult(jedis.lrange(key, 0, -1));
                case "set" -> singleColumn("member", new ArrayList<>(jedis.smembers(key)));
                case "zset" -> zrangeResult(jedis.zrangeWithScores(key, 0, -1));
                case "none" -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, "key 不存在：" + key);
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持读取该类型的值：" + type);
            };
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "读取 key 的值失败：" + exception.getMessage(), exception);
        }
    }

    public QueryResult execute(DataSourceEntity dataSource, String database, String commandLine, int limit) {
        List<String> tokens = tokenize(commandLine);
        if (tokens.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "命令不能为空");
        }
        String command = tokens.get(0).toUpperCase(Locale.ROOT);
        if (!READONLY_COMMANDS.contains(command)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实时数据查询只允许执行只读命令，不支持：" + command);
        }
        List<String> args = tokens.subList(1, tokens.size());
        int capped = Math.max(1, Math.min(limit, 500));
        try (Jedis jedis = openConnection(dataSource, database)) {
            return runCommand(jedis, command, args, capped);
        } catch (ResponseStatusException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "执行命令失败：" + exception.getMessage(), exception);
        }
    }

    private QueryResult runCommand(Jedis jedis, String command, List<String> args, int limit) {
        switch (command) {
            case "GET":
                requireArgs(command, args, 1);
                return singleColumn("value", List.of(nullToDash(jedis.get(args.get(0)))));
            case "MGET":
                requireArgs(command, args, 1);
                return singleColumn("value", jedis.mget(args.toArray(new String[0])));
            case "HGET":
                requireArgs(command, args, 2);
                return singleColumn("value", List.of(nullToDash(jedis.hget(args.get(0), args.get(1)))));
            case "HGETALL":
                requireArgs(command, args, 1);
                return mapResult("field", "value", jedis.hgetAll(args.get(0)));
            case "HKEYS":
                requireArgs(command, args, 1);
                return singleColumn("field", new ArrayList<>(jedis.hkeys(args.get(0))));
            case "HVALS":
                requireArgs(command, args, 1);
                return singleColumn("value", jedis.hvals(args.get(0)));
            case "HLEN":
                requireArgs(command, args, 1);
                return singleColumn("count", List.of(String.valueOf(jedis.hlen(args.get(0)))));
            case "LRANGE":
                requireArgs(command, args, 3);
                return indexedResult(jedis.lrange(args.get(0), Long.parseLong(args.get(1)), Long.parseLong(args.get(2))));
            case "LLEN":
                requireArgs(command, args, 1);
                return singleColumn("count", List.of(String.valueOf(jedis.llen(args.get(0)))));
            case "SMEMBERS":
                requireArgs(command, args, 1);
                return singleColumn("member", new ArrayList<>(jedis.smembers(args.get(0))));
            case "SCARD":
                requireArgs(command, args, 1);
                return singleColumn("count", List.of(String.valueOf(jedis.scard(args.get(0)))));
            case "ZRANGE":
                requireArgs(command, args, 3);
                return zrangeResult(jedis.zrangeWithScores(args.get(0), Long.parseLong(args.get(1)), Long.parseLong(args.get(2))));
            case "ZSCORE": {
                requireArgs(command, args, 2);
                Double score = jedis.zscore(args.get(0), args.get(1));
                return singleColumn("score", List.of(score == null ? "-" : String.valueOf(score)));
            }
            case "ZCARD":
                requireArgs(command, args, 1);
                return singleColumn("count", List.of(String.valueOf(jedis.zcard(args.get(0)))));
            case "TYPE":
                requireArgs(command, args, 1);
                return singleColumn("type", List.of(jedis.type(args.get(0))));
            case "TTL":
                requireArgs(command, args, 1);
                return singleColumn("ttl_seconds", List.of(String.valueOf(jedis.ttl(args.get(0)))));
            case "EXISTS":
                requireArgs(command, args, 1);
                return singleColumn("exists", List.of(String.valueOf(jedis.exists(args.get(0)))));
            case "STRLEN":
                requireArgs(command, args, 1);
                return singleColumn("length", List.of(String.valueOf(jedis.strlen(args.get(0)))));
            case "DBSIZE":
                return singleColumn("key_count", List.of(String.valueOf(jedis.dbSize())));
            case "KEYS":
            case "SCAN":
                return singleColumn("key", scanKeys(jedis, args.isEmpty() ? "*" : args.get(0), limit));
            default:
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "不支持的命令：" + command);
        }
    }

    private void requireArgs(String command, List<String> args, int required) {
        if (args.size() < required) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, command + " 需要至少 " + required + " 个参数");
        }
    }

    private String nullToDash(String value) {
        return value == null ? "-" : value;
    }

    private List<String> scanKeys(Jedis jedis, String pattern, int limit) {
        List<String> keys = new ArrayList<>();
        String cursor = "0";
        ScanParams params = new ScanParams().match(pattern).count(100);
        do {
            ScanResult<String> result = jedis.scan(cursor, params);
            keys.addAll(result.getResult());
            cursor = result.getCursor();
        } while (!"0".equals(cursor) && keys.size() < limit);
        return keys.size() > limit ? keys.subList(0, limit) : keys;
    }

    private QueryResult singleColumn(String columnName, List<String> values) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String value : values) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(columnName, value);
            rows.add(row);
        }
        return new QueryResult(List.of(columnName), rows);
    }

    private QueryResult mapResult(String keyColumn, String valueColumn, Map<String, String> map) {
        List<Map<String, Object>> rows = new ArrayList<>();
        map.forEach((key, value) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(keyColumn, key);
            row.put(valueColumn, value);
            rows.add(row);
        });
        return new QueryResult(List.of(keyColumn, valueColumn), rows);
    }

    private QueryResult indexedResult(List<String> values) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("value", values.get(i));
            rows.add(row);
        }
        return new QueryResult(List.of("index", "value"), rows);
    }

    private QueryResult zrangeResult(List<Tuple> tuples) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Tuple tuple : tuples) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("member", tuple.getElement());
            row.put("score", tuple.getScore());
            rows.add(row);
        }
        return new QueryResult(List.of("member", "score"), rows);
    }

    private List<String> tokenize(String commandLine) {
        List<String> tokens = new ArrayList<>();
        if (commandLine == null) {
            return tokens;
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\"([^\"]*)\"|(\\S+)").matcher(commandLine.trim());
        while (matcher.find()) {
            tokens.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        return tokens;
    }
}
