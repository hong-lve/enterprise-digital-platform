import { CaretRightOutlined, FileSearchOutlined, ReloadOutlined, SearchOutlined, TableOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Input, List, Select, Space, Table, Tag, Typography, message } from 'antd';
import Editor from '@monaco-editor/react';
import { useEffect, useRef, useState } from 'react';
import { listDataSourceDatabases, pageDataSources, type DataSourceRecord } from '../api/dataSources';
import { executeRealtimeQuery, getRedisValue, listRealtimeTables, listRedisKeys, type QueryResult, type RedisKeyInfo, type TableInfo } from '../api/realtimeQuery';

// Redis TTL is in whole seconds; -1/-2 (no expiry / key gone) are Redis's own
// sentinel values, not error codes, so they get worded text instead of a
// negative number.
function formatTtl(ttlSeconds: number): string {
  if (ttlSeconds < 0) return '无过期时间';
  if (ttlSeconds < 60) return `${ttlSeconds} 秒后过期`;
  if (ttlSeconds < 3600) return `${Math.floor(ttlSeconds / 60)} 分钟后过期`;
  return `${Math.floor(ttlSeconds / 3600)} 小时后过期`;
}

export function RealtimeQueryPage() {
  const [dataSources, setDataSources] = useState<DataSourceRecord[]>([]);
  const [dataSourceId, setDataSourceId] = useState<number | null>(null);
  const [databases, setDatabases] = useState<string[]>([]);
  const [databasesLoading, setDatabasesLoading] = useState(false);
  const [database, setDatabase] = useState<string | undefined>(undefined);
  const [tables, setTables] = useState<TableInfo[]>([]);
  const [tablesLoading, setTablesLoading] = useState(false);
  const [selectedTable, setSelectedTable] = useState<string | null>(null);
  const [sql, setSql] = useState('');
  const [result, setResult] = useState<QueryResult | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const [redisPattern, setRedisPattern] = useState('*');
  const [redisKeys, setRedisKeys] = useState<RedisKeyInfo[]>([]);
  const [redisKeysLoading, setRedisKeysLoading] = useState(false);
  const [selectedRedisKey, setSelectedRedisKey] = useState<string | null>(null);

  // True from the moment a new data source is picked until its database list
  // has actually resolved. A plain `database === undefined` check doesn't
  // catch the dangerous window: the effect below and the reset effect both
  // run in the *same* render pass when dataSourceId changes, so on that
  // first pass `database` is still the *previous* data source's value (not
  // yet undefined - setDatabase(undefined) only takes effect on the next
  // render) - confirmed live via the network tab: switching from a Redis
  // data source (database="0") to Oracle fired
  // GET .../tables?dataSourceId=<oracle>&database=0 before the corrected
  // request. A ref is read synchronously within the same pass, unlike state.
  const switchingDataSourceRef = useRef(false);
  // Tracks the most recently selected data source so an in-flight
  // listDataSourceDatabases() response from a data source the user has
  // since switched away from doesn't clobber state with the wrong values.
  const latestDataSourceIdRef = useRef<number | null>(null);

  useEffect(() => {
    pageDataSources({ current: 1, pageSize: 100 }).then((data) => {
      setDataSources(data.records);
      if (data.records.length && dataSourceId === null) {
        setDataSourceId(data.records[0].id);
      }
    });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (dataSourceId === null) return;
    const targetId = dataSourceId;
    latestDataSourceIdRef.current = targetId;
    const current = dataSources.find((item) => item.id === targetId);
    switchingDataSourceRef.current = true;
    setSelectedTable(null);
    setSql('');
    setResult(null);
    setError(null);
    setTables([]);
    setRedisKeys([]);
    setRedisPattern('*');
    setSelectedRedisKey(null);
    setDatabase(undefined);
    setDatabasesLoading(true);
    listDataSourceDatabases(targetId)
      .then((names) => {
        if (latestDataSourceIdRef.current !== targetId) return;
        setDatabases(names);
        switchingDataSourceRef.current = false;
        setDatabase(current?.databaseName && names.includes(current.databaseName) ? current.databaseName : names[0]);
      })
      .finally(() => {
        if (latestDataSourceIdRef.current === targetId) setDatabasesLoading(false);
      });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dataSourceId]);

  const currentDataSource = dataSources.find((item) => item.id === dataSourceId);
  const isRedis = currentDataSource?.type === 'REDIS';

  const loadTables = () => {
    if (dataSourceId === null || isRedis) return;
    setTablesLoading(true);
    listRealtimeTables(dataSourceId, database)
      .then(setTables)
      // Without this, a failed reload (wrong/stale database, or a genuine
      // backend error) leaves the previous data source's table list on
      // screen looking like it belongs to the current selection.
      .catch(() => setTables([]))
      .finally(() => setTablesLoading(false));
  };

  useEffect(() => {
    if (dataSourceId === null || database === undefined || isRedis || switchingDataSourceRef.current) return;
    loadTables();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dataSourceId, database, isRedis]);

  const scanRedisKeys = () => {
    if (dataSourceId === null) return;
    setRedisKeysLoading(true);
    listRedisKeys(dataSourceId, database, redisPattern)
      .then(setRedisKeys)
      .catch(() => setRedisKeys([]))
      .finally(() => setRedisKeysLoading(false));
  };

  useEffect(() => {
    if (dataSourceId === null || database === undefined || !isRedis || switchingDataSourceRef.current) return;
    scanRedisKeys();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dataSourceId, database, isRedis]);

  const viewRedisKey = (key: string) => {
    if (dataSourceId === null) return;
    setSelectedRedisKey(key);
    setRunning(true);
    setError(null);
    getRedisValue(dataSourceId, key, database)
      .then(setResult)
      .catch((err) => {
        setResult(null);
        setError(err?.response?.data?.message || '读取失败');
      })
      .finally(() => setRunning(false));
  };

  const selectTable = (table: string) => {
    setSelectedTable(table);
    setSql(`SELECT * FROM ${table} LIMIT 100`);
  };

  const runQuery = () => {
    if (dataSourceId === null) {
      message.warning('请先选择数据源');
      return;
    }
    if (!sql.trim()) {
      message.warning(isRedis ? '请输入 Redis 命令' : '请输入 SQL');
      return;
    }
    setRunning(true);
    setError(null);
    executeRealtimeQuery(sql, dataSourceId, database)
      .then(setResult)
      .catch((err) => {
        setResult(null);
        setError(err?.response?.data?.message || '查询失败');
      })
      .finally(() => setRunning(false));
  };

  return (
    <div className="page-stack">
      <Typography.Title level={3}><FileSearchOutlined /> 实时数据查询</Typography.Title>
      <Typography.Paragraph type="secondary">
        选择"数据源配置"页面里注册的任意数据源查询数据，用来验证 Flink 流作业写进去的结果对不对。MySQL/ClickHouse/Doris/Oracle 只能执行 SELECT 查询；Redis 只能执行 GET/HGETALL/LRANGE/SMEMBERS/ZRANGE/KEYS 等只读命令。建表/写入请用对应的客户端手动操作。
      </Typography.Paragraph>

      <Space>
        <Select
          style={{ width: 260 }}
          value={dataSourceId ?? undefined}
          placeholder="选择数据源"
          onChange={(value) => setDataSourceId(value)}
          options={dataSources.map((item) => ({ value: item.id, label: `${item.name}（${item.type}）` }))}
        />
        <Select
          style={{ width: 220 }}
          value={database}
          placeholder="选择数据库"
          loading={databasesLoading}
          onChange={(value) => setDatabase(value)}
          options={databases.map((name) => ({ value: name, label: name }))}
        />
      </Space>

      <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
        {isRedis ? (
          <Card
            title="Key 浏览"
            size="small"
            style={{ width: 280, flexShrink: 0 }}
            extra={<Button size="small" icon={<ReloadOutlined />} loading={redisKeysLoading} onClick={scanRedisKeys} />}
          >
            <Space.Compact style={{ width: '100%', marginBottom: 8 }}>
              <Input
                value={redisPattern}
                onChange={(event) => setRedisPattern(event.target.value)}
                onPressEnter={scanRedisKeys}
                placeholder="*"
              />
              <Button icon={<SearchOutlined />} loading={redisKeysLoading} onClick={scanRedisKeys}>扫描</Button>
            </Space.Compact>
            {redisKeys.length ? (
              <List
                size="small"
                style={{ maxHeight: 280, overflow: 'auto' }}
                dataSource={redisKeys}
                renderItem={(item) => (
                  <List.Item
                    onClick={() => viewRedisKey(item.key)}
                    style={{ cursor: 'pointer', display: 'block', fontWeight: item.key === selectedRedisKey ? 600 : 400 }}
                  >
                    <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{item.key}</div>
                    <Space size={4}>
                      <Tag>{item.type}</Tag>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>{formatTtl(item.ttlSeconds)}</Typography.Text>
                    </Space>
                  </List.Item>
                )}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="点击扫描查看 key" />
            )}
            <Typography.Paragraph type="secondary" style={{ marginTop: 12, marginBottom: 4, fontSize: 12 }}>
              常用命令（点了直接填进下面的命令框）
            </Typography.Paragraph>
            <Space direction="vertical" size={4} style={{ width: '100%' }}>
              {['DBSIZE', 'GET key', 'HGETALL key', 'LRANGE key 0 -1', 'SMEMBERS key', 'ZRANGE key 0 -1', 'TYPE key', 'TTL key'].map((example) => (
                <Button key={example} size="small" block style={{ textAlign: 'left' }} onClick={() => setSql(example)}>{example}</Button>
              ))}
            </Space>
          </Card>
        ) : (
          <Card
            title="表"
            size="small"
            style={{ width: 240, flexShrink: 0 }}
            extra={<Button size="small" icon={<ReloadOutlined />} loading={tablesLoading} onClick={loadTables} />}
          >
            {tables.length ? (
              <List
                size="small"
                dataSource={tables}
                renderItem={(table) => (
                  <List.Item
                    onClick={() => selectTable(table.name)}
                    style={{ cursor: 'pointer', fontWeight: table.name === selectedTable ? 600 : 400 }}
                  >
                    <Space size={6}>
                      <TableOutlined />
                      {table.name}
                    </Space>
                  </List.Item>
                )}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无表" />
            )}
          </Card>
        )}

        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Card size="small">
            {/* No Redis language mode in Monaco, so it falls back to
                plaintext there rather than mislabeling GET/HGETALL/... as SQL. */}
            <div style={{ border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden' }}>
              <Editor
                height="320px"
                language={isRedis ? 'plaintext' : 'sql'}
                value={sql}
                onChange={(value) => setSql(value ?? '')}
                // automaticLayout alone doesn't correct the *initial*
                // measurement on a plain (non-modal) page - see the
                // identical comment in FlinkSqlPage.tsx.
                onMount={(editor) => editor.layout()}
                options={{ minimap: { enabled: false }, fontSize: 13, automaticLayout: true }}
              />
            </div>
            <Space style={{ marginTop: 12 }}>
              <Button type="primary" icon={<CaretRightOutlined />} loading={running} onClick={runQuery}>执行</Button>
            </Space>
          </Card>

          {error && <Alert type="error" showIcon message={error} />}

          <Card size="small" title="结果">
            {result ? (
              <Table
                rowKey={(_, index) => String(index)}
                dataSource={result.rows}
                pagination={false}
                scroll={{ x: true }}
                size="small"
                columns={result.columns.map((column) => ({ title: column, dataIndex: column, render: (value: unknown) => value === null ? <Typography.Text type="secondary">NULL</Typography.Text> : String(value) }))}
              />
            ) : (
              <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有查询结果" />
            )}
          </Card>
        </div>
      </div>
    </div>
  );
}
