import { FileSearchOutlined, ReloadOutlined } from '@ant-design/icons';
import { Select, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { pageAlertHistory, type AlertHistoryRecord } from '../api/alertHistory';

const entityTypeLabel: Record<string, string> = {
  CDC_SOURCE: 'CDC 数据源',
  FLINK_JOB: 'Flink 流作业',
  SQL_JOB: 'SQL 流作业'
};

const dimensionLabel: Record<string, string> = {
  CONNECTOR_FAILURE: '连接器故障',
  MESSAGE_LAG: '消息延迟',
  JOB_FAILURE: '作业故障',
  BACKPRESSURE: '反压',
  CONSUMER_LAG: '消费延迟'
};

export function AlertHistoryPage() {
  const [records, setRecords] = useState<AlertHistoryRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [pageSize, setPageSize] = useState(10);
  const [entityType, setEntityType] = useState<string | undefined>(undefined);

  const load = () => {
    setLoading(true);
    pageAlertHistory({ current, pageSize, entityType })
      .then((data) => {
        setRecords(data.records);
        setTotal(data.total);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, pageSize, entityType]);

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><FileSearchOutlined /> 告警历史</Typography.Title>
          <Typography.Paragraph type="secondary">
            CDC 数据源、Flink 流作业、SQL 流作业每一次 ALERTING/OK 状态跳变的记录，独立于各自当前的告警状态字段。
          </Typography.Paragraph>
        </div>
        <Space>
          <Select
            allowClear
            placeholder="按实体类型筛选"
            style={{ width: 160 }}
            value={entityType}
            onChange={(value) => { setCurrent(1); setEntityType(value); }}
            options={Object.entries(entityTypeLabel).map(([value, label]) => ({ value, label }))}
          />
          <ReloadOutlined onClick={load} />
        </Space>
      </Space>

      <Table<AlertHistoryRecord>
        rowKey="id"
        loading={loading}
        dataSource={records}
        pagination={{
          current,
          pageSize,
          total,
          showSizeChanger: true,
          onChange: (page, size) => { setCurrent(page); setPageSize(size); }
        }}
        columns={[
          { title: '时间', dataIndex: 'occurredAt', width: 180 },
          {
            title: '实体',
            width: 280,
            render: (_, record) => (
              <span style={{ whiteSpace: 'nowrap' }}>
                <Tag>{entityTypeLabel[record.entityType] || record.entityType}</Tag>
                {record.entityName}
              </span>
            )
          },
          { title: '维度', dataIndex: 'dimension', width: 120, render: (value: string) => dimensionLabel[value] || value },
          {
            title: '状态',
            dataIndex: 'state',
            width: 100,
            render: (value: string) => <Tag color={value === 'ALERTING' ? 'red' : 'green'}>{value === 'ALERTING' ? '告警中' : '已恢复'}</Tag>
          },
          { title: '消息', dataIndex: 'message', ellipsis: true, render: (value?: string) => value || '-' }
        ]}
      />
    </div>
  );
}
