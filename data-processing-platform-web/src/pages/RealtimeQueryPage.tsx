import { CaretRightOutlined, FileSearchOutlined, ReloadOutlined, TableOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, List, Select, Space, Table, Typography, message } from 'antd';
import Editor from '@monaco-editor/react';
import { useEffect, useRef, useState } from 'react';
import { listDataSourceDatabases, pageDataSources, type DataSourceRecord } from '../api/dataSources';
import { executeRealtimeQuery, listRealtimeTables, type QueryResult, type TableInfo } from '../api/realtimeQuery';

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

  // True from the moment a new data source is picked until its database list
  // has actually resolved. A plain `database === undefined` check doesn't
  // catch the dangerous window: the effect below and the reset effect both
  // run in the *same* render pass when dataSourceId changes, so on that
  // first pass `database` is still the *previous* data source's value (not
  // yet undefined - setDatabase(undefined) only takes effect on the next
  // render) - confirmed live via the network tab: switching data sources
  // fired GET .../tables with the previous source's database before the
  // corrected request. A ref is read synchronously within the same pass,
  // unlike state.
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

  const loadTables = () => {
    if (dataSourceId === null) return;
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
    if (dataSourceId === null || database === undefined || switchingDataSourceRef.current) return;
    loadTables();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dataSourceId, database]);

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
      message.warning('请输入 SQL');
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
        选择"数据源配置"页面里注册的任意数据源查询数据，用来验证 Flink 流作业写进去的结果对不对。MySQL/ClickHouse/Doris/Oracle 只能执行 SELECT 查询。建表/写入请用对应的客户端手动操作。
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

        <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 16 }}>
          <Card size="small">
            <div style={{ border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden' }}>
              <Editor
                height="320px"
                language="sql"
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
