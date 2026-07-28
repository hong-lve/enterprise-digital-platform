import { ClusterOutlined, DatabaseOutlined, FunctionOutlined, NodeIndexOutlined, ReloadOutlined, TableOutlined } from '@ant-design/icons';
import { Button, Empty, Space, Tag, Tree, Typography, message } from 'antd';
import type { DataNode } from 'antd/es/tree';
import { useEffect, useState } from 'react';
import { getLineage, type CdcSourceLineage, type FlinkJobLineage, type LineageView } from '../api/lineage';

const statusColor: Record<string, string> = {
  DRAFT: 'default',
  RUNNING: 'green',
  PAUSED: 'orange',
  FAILED: 'red',
  CANCELED: 'orange',
  FINISHED: 'gold',
  UNKNOWN: 'default'
};

function statusTag(status: string) {
  return <Tag color={statusColor[status] || 'default'}>{status}</Tag>;
}

const sinkTypeLabel: Record<string, string> = {
  CLICKHOUSE: 'ClickHouse 表',
  DORIS: 'Doris 表',
  ORACLE: 'Oracle 表',
  REDIS: 'Redis 表',
  MYSQL: 'MySQL 表'
};

function flinkJobNode(prefix: string, job: FlinkJobLineage): DataNode {
  const isSql = job.jobType === 'SQL';
  const sinkNodes: DataNode[] =
    job.sinkTables.length > 0
      ? job.sinkTables.map((sink) => ({
          key: `${prefix}-sink-${sink.table}`,
          title: (
            <Space>
              <TableOutlined /> {sink.table} <Tag>{sinkTypeLabel[sink.sinkType] || sink.sinkType}</Tag>
            </Space>
          ),
          isLeaf: true
        }))
      : [
          {
            key: `${prefix}-sink-none`,
            title: (
              <Typography.Text type="secondary">
                {isSql ? 'SQL 作业写到其他 Kafka topic，未纳入这张血缘图' : '未声明目标表'}
              </Typography.Text>
            ),
            isLeaf: true
          }
        ];
  return {
    key: prefix,
    title: (
      <Space>
        {isSql ? <FunctionOutlined /> : <ClusterOutlined />} {job.name} <Tag>{isSql ? 'SQL 作业' : 'JAR 作业'}</Tag> {statusTag(job.status)}
      </Space>
    ),
    children: sinkNodes
  };
}

function cdcSourceNode(source: CdcSourceLineage): DataNode {
  const topicNodes: DataNode[] = source.topics.map((topic) => ({
    key: `cdc-${source.id}-topic-${topic.topic}`,
    title: (
      <Space>
        <NodeIndexOutlined /> {topic.topic} <Tag>Kafka Topic</Tag>
      </Space>
    ),
    children:
      topic.consumers.length > 0
        ? topic.consumers.map((job) => flinkJobNode(`cdc-${source.id}-topic-${topic.topic}-job-${job.id}`, job))
        : [
            {
              key: `cdc-${source.id}-topic-${topic.topic}-none`,
              title: <Typography.Text type="warning">无消费者——数据进了 Kafka，但没有 Flink 作业在消费</Typography.Text>,
              isLeaf: true
            }
          ]
  }));
  return {
    key: `cdc-${source.id}`,
    title: (
      <Space>
        <DatabaseOutlined /> {source.name} {statusTag(source.status)}
      </Space>
    ),
    children:
      topicNodes.length > 0
        ? topicNodes
        : [
            {
              key: `cdc-${source.id}-no-topics`,
              title: <Typography.Text type="secondary">未配置监听表</Typography.Text>,
              isLeaf: true
            }
          ]
  };
}

export function LineagePage() {
  const [lineage, setLineage] = useState<LineageView | null>(null);
  const [loading, setLoading] = useState(false);

  const load = () => {
    setLoading(true);
    getLineage()
      .then(setLineage)
      .catch(() => message.error('加载血缘数据失败'))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const cdcTreeData = lineage?.cdcSources.map(cdcSourceNode) ?? [];
  const orphanTreeData = lineage?.orphanFlinkJobs.map((job) => flinkJobNode(`orphan-job-${job.id}`, job)) ?? [];

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><NodeIndexOutlined /> 实时血缘</Typography.Title>
          <Typography.Paragraph type="secondary">
            实时链路的数据流向：MySQL 表（CDC 数据源）→ Kafka Topic → Flink 流作业 → ClickHouse 表。仅覆盖实时链路，不含离线批处理。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
        </Space>
      </Space>

      {cdcTreeData.length > 0 ? (
        <Tree treeData={cdcTreeData} defaultExpandAll showLine selectable={false} />
      ) : (
        <Empty description="暂无 CDC 数据源" />
      )}

      {orphanTreeData.length > 0 && (
        <div>
          <Typography.Title level={5}>未匹配已知数据源的作业</Typography.Title>
          <Typography.Paragraph type="secondary">
            这些 Flink 作业声明消费的 Kafka Topic，不属于任何已知 CDC 数据源产生的 Topic（可能是数据源已被删除，或 Topic 由其他方式创建）。
          </Typography.Paragraph>
          <Tree treeData={orphanTreeData} defaultExpandAll showLine selectable={false} />
        </div>
      )}
    </div>
  );
}
