import { CaretRightOutlined, CodeOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Empty, Input, Space, Table, Typography, message } from 'antd';
import { useState } from 'react';
import { executeFlinkSql, type FlinkSqlResult } from '../api/flinkSql';

const EXAMPLE_SQL = `CREATE TABLE task_log_src (
  task_id BIGINT,
  \`result\` STRING,
  message STRING
) WITH (
  'connector' = 'kafka',
  'topic' = 'demo.data_platform_db.data_task_log',
  'properties.bootstrap.servers' = 'kafka:9092',
  'scan.startup.mode' = 'earliest-offset',
  'format' = 'debezium-json',
  'debezium-json.schema-include' = 'true'
);

SELECT task_id, \`result\`, message FROM task_log_src LIMIT 20;`;

export function FlinkSqlPage() {
  const [sql, setSql] = useState(EXAMPLE_SQL);
  const [result, setResult] = useState<FlinkSqlResult | null>(null);
  const [running, setRunning] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const runQuery = () => {
    if (!sql.trim()) {
      message.warning('请输入 SQL');
      return;
    }
    setRunning(true);
    setError(null);
    executeFlinkSql(sql)
      .then(setResult)
      .catch((err) => {
        setResult(null);
        setError(err?.response?.data?.message || '查询失败');
      })
      .finally(() => setRunning(false));
  };

  return (
    <div className="page-stack">
      <Typography.Title level={3}><CodeOutlined /> Flink SQL 查询</Typography.Title>
      <Typography.Paragraph type="secondary">
        用 SQL 交互式查询 Kafka topic（比如 CDC 采集到的数据），不用手写 Java 打 jar。先 <code>CREATE TABLE ... WITH (...)</code> 映射一个 topic，再 <code>SELECT</code> 取数据——多条语句用分号分隔，在同一个会话里依次执行。
        每次查询最多等 20 秒，到点自动收尾释放资源。只支持查询，不支持把结果写回 ClickHouse（这条路目前被 Flink 生态本身卡住，见 docker/bigdata/README.md）——长驻的写入作业仍然走 Flink 流作业管理页面提交 jar。
      </Typography.Paragraph>

      <Card size="small">
        <Input.TextArea
          className="realtime-sql-editor"
          rows={14}
          value={sql}
          onChange={(event) => setSql(event.target.value)}
        />
        <Space style={{ marginTop: 12 }}>
          <Button type="primary" icon={<CaretRightOutlined />} loading={running} onClick={runQuery}>执行</Button>
        </Space>
      </Card>

      {error && <Alert type="error" showIcon message={error} />}

      <Card size="small" title="结果">
        {result && result.rows.length ? (
          <Table
            rowKey={(_, index) => String(index)}
            dataSource={result.rows}
            pagination={false}
            scroll={{ x: true }}
            size="small"
            columns={result.columns.map((column) => ({ title: column, dataIndex: column, render: (value: unknown) => value === null || value === undefined ? <Typography.Text type="secondary">NULL</Typography.Text> : String(value) }))}
          />
        ) : (
          <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="还没有查询结果" />
        )}
      </Card>
    </div>
  );
}
