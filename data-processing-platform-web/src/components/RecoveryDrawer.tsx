import { Button, Descriptions, Drawer, Popconfirm, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { fetchRecoveryStatus, manualTakeoverRecovery, type RecoveryEntityType, type RecoveryEventRecord, type RecoveryStatusResponse } from '../api/recovery';

const circuitStateColor: Record<string, string> = {
  OK: 'green',
  TRIPPED: 'red'
};

const eventTypeLabel: Record<string, string> = {
  FAILURE_DETECTED: '检测到故障',
  RETRY_ATTEMPTED: '自动重试',
  TIER_ESCALATED: '升级重试级别',
  CIRCUIT_TRIPPED: '自动恢复已停止',
  RECOVERED: '已恢复',
  MANUAL_TAKEOVER: '人工接管',
  RESET: '状态重置'
};

const eventTypeColor: Record<string, string> = {
  FAILURE_DETECTED: 'red',
  RETRY_ATTEMPTED: 'blue',
  TIER_ESCALATED: 'orange',
  CIRCUIT_TRIPPED: 'red',
  RECOVERED: 'green',
  MANUAL_TAKEOVER: 'purple',
  RESET: 'default'
};

interface RecoveryDrawerProps {
  entityType: RecoveryEntityType;
  entityId: number | null;
  entityName?: string;
  canManage: boolean;
  onClose: () => void;
}

/**
 * Shared by CdcSourcesPage and FlinkStreamJobsPage - both entity types read
 * the same RecoveryOrchestrator state machine (see the backend service's own
 * javadoc), so one drawer component covers both instead of duplicating this
 * tier/circuit-state display + timeline table + manual-takeover action twice.
 */
export function RecoveryDrawer({ entityType, entityId, entityName, canManage, onClose }: RecoveryDrawerProps) {
  const [data, setData] = useState<RecoveryStatusResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [takingOver, setTakingOver] = useState(false);

  const load = (id: number) => {
    setLoading(true);
    fetchRecoveryStatus(entityType, id).then(setData).finally(() => setLoading(false));
  };

  useEffect(() => {
    if (entityId !== null) {
      load(entityId);
    } else {
      setData(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entityType, entityId]);

  const takeover = () => {
    if (entityId === null) return;
    setTakingOver(true);
    manualTakeoverRecovery(entityType, entityId, entityName)
      .then(() => {
        message.success('已重置恢复状态，自动恢复将重新生效');
        load(entityId);
      })
      .finally(() => setTakingOver(false));
  };

  const state = data?.state;
  const tripped = state?.circuitState === 'TRIPPED';

  return (
    <Drawer title={`恢复状态${entityName ? ' - ' + entityName : ''}`} open={entityId !== null} onClose={onClose} width={720}>
      {state && (
        <>
          <Descriptions column={2} size="small" bordered style={{ marginBottom: 16 }}>
            <Descriptions.Item label="状态" span={2}>
              <Tag color={circuitStateColor[state.circuitState] || 'default'}>
                {tripped ? '已停止自动恢复，需要人工接管' : '正常（自动恢复生效中）'}
              </Tag>
            </Descriptions.Item>
            <Descriptions.Item label="当前重试级别">第 {state.tier} 级（本级已尝试 {state.attemptsInTier} 次）</Descriptions.Item>
            <Descriptions.Item label="最近一次尝试">{state.lastAttemptAt ?? '从未尝试过'}</Descriptions.Item>
          </Descriptions>
          {tripped && canManage && (
            <Popconfirm title="确定人工接管？将重置重试级别和熔断状态，恢复自动恢复能力" onConfirm={takeover}>
              <Button type="primary" danger loading={takingOver} style={{ marginBottom: 16 }}>人工接管 / 重置</Button>
            </Popconfirm>
          )}
        </>
      )}
      <Typography.Title level={5}>恢复事件时间线</Typography.Title>
      <Table<RecoveryEventRecord>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={data?.timeline ?? []}
        pagination={false}
        locale={{ emptyText: '暂无恢复事件' }}
        columns={[
          { title: '时间', dataIndex: 'occurredAt', width: 160 },
          {
            title: '事件',
            dataIndex: 'eventType',
            width: 140,
            render: (value: string) => <Tag color={eventTypeColor[value] || 'default'}>{eventTypeLabel[value] || value}</Tag>
          },
          { title: '详情', dataIndex: 'detail', render: (value?: string) => value || '-' }
        ]}
      />
    </Drawer>
  );
}
