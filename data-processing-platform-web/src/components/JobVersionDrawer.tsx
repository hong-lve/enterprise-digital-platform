import { Button, Drawer, Empty, Popconfirm, Select, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import {
  diffJobVersions,
  fetchJobVersionHistory,
  type JobVersionEntityType,
  type JobVersionFieldChange,
  type JobVersionSnapshotRecord
} from '../api/jobVersions';

interface JobVersionDrawerProps {
  entityType: JobVersionEntityType;
  entityId: number | null;
  entityName?: string;
  canRollback: boolean;
  onClose: () => void;
  onRollback: (versionNo: number) => Promise<unknown>;
}

/**
 * Shared by FlinkStreamJobsPage and FlinkSqlJobsPage - version history/diff/
 * rollback logic is entity-type-agnostic (see JobVersionSnapshotService), so
 * one drawer covers both instead of duplicating this table + diff view +
 * rollback action twice. Rollback itself still goes through each page's own
 * API call (onRollback prop) since only the caller knows which entity type's
 * endpoint to hit and how to refresh its own job list afterward.
 */
export function JobVersionDrawer({ entityType, entityId, entityName, canRollback, onClose, onRollback }: JobVersionDrawerProps) {
  const [versions, setVersions] = useState<JobVersionSnapshotRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [fromVersion, setFromVersion] = useState<number | undefined>();
  const [toVersion, setToVersion] = useState<number | undefined>();
  const [diff, setDiff] = useState<JobVersionFieldChange[] | null>(null);
  const [diffing, setDiffing] = useState(false);
  const [rollingBack, setRollingBack] = useState<number | null>(null);

  const load = (id: number) => {
    setLoading(true);
    fetchJobVersionHistory(entityType, id)
      .then((data) => {
        setVersions(data);
        setDiff(null);
        if (data.length >= 2) {
          setToVersion(data[0].versionNo);
          setFromVersion(data[1].versionNo);
        } else {
          setToVersion(data[0]?.versionNo);
          setFromVersion(undefined);
        }
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    if (entityId !== null) {
      load(entityId);
    } else {
      setVersions([]);
      setDiff(null);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entityType, entityId]);

  const runDiff = () => {
    if (entityId === null || fromVersion === undefined || toVersion === undefined) return;
    setDiffing(true);
    diffJobVersions(entityType, entityId, fromVersion, toVersion)
      .then(setDiff)
      .finally(() => setDiffing(false));
  };

  // Success/pending-approval messaging is the caller's job (onRollback) -
  // it already knows how to distinguish an immediately-applied rollback from
  // one deferred to PROD approval, same as every other gated action on these
  // pages. This just refreshes the version list once the call settles.
  const rollback = (versionNo: number) => {
    setRollingBack(versionNo);
    onRollback(versionNo)
      .then(() => {
        if (entityId !== null) load(entityId);
      })
      .finally(() => setRollingBack(null));
  };

  const versionOptions = versions.map((v) => ({ value: v.versionNo, label: `版本 ${v.versionNo}` }));

  return (
    <Drawer title={`版本历史${entityName ? ' - ' + entityName : ''}`} open={entityId !== null} onClose={onClose} width={800}>
      <Typography.Title level={5}>配置差异</Typography.Title>
      <Space style={{ marginBottom: 12 }}>
        <Select style={{ width: 140 }} placeholder="从版本" options={versionOptions} value={fromVersion} onChange={setFromVersion} />
        <span>→</span>
        <Select style={{ width: 140 }} placeholder="到版本" options={versionOptions} value={toVersion} onChange={setToVersion} />
        <Button onClick={runDiff} loading={diffing} disabled={fromVersion === undefined || toVersion === undefined}>对比</Button>
      </Space>
      {diff !== null && (
        diff.length === 0 ? (
          <Typography.Text type="secondary">这两个版本之间没有字段差异</Typography.Text>
        ) : (
          <Table
            rowKey="field"
            size="small"
            pagination={false}
            dataSource={diff}
            style={{ marginBottom: 16 }}
            columns={[
              { title: '字段', dataIndex: 'field', width: 200 },
              { title: '旧值', dataIndex: 'oldValue', render: (value?: string) => value ?? <Tag>空</Tag> },
              { title: '新值', dataIndex: 'newValue', render: (value?: string) => value ?? <Tag>空</Tag> }
            ]}
          />
        )
      )}

      <Typography.Title level={5}>版本列表</Typography.Title>
      <Table<JobVersionSnapshotRecord>
        rowKey="id"
        size="small"
        loading={loading}
        dataSource={versions}
        pagination={false}
        locale={{ emptyText: <Empty description="暂无版本记录" /> }}
        columns={[
          {
            title: '版本',
            dataIndex: 'versionNo',
            width: 90,
            render: (value: number, record) => (
              <Space>
                <Tag color="blue">V{value}</Tag>
                {record.rollbackOfVersion != null && <Tag color="purple">回滚自 V{record.rollbackOfVersion}</Tag>}
              </Space>
            )
          },
          { title: '变更', dataIndex: 'changeSummary', render: (value?: string) => value || '-' },
          { title: '操作人', dataIndex: 'createdBy', width: 100, render: (value?: string) => value || '-' },
          { title: '时间', dataIndex: 'createdAt', width: 160 },
          {
            title: '操作',
            width: 100,
            render: (_, record) =>
              canRollback && (
                <Popconfirm title={`确定回滚到版本 ${record.versionNo} 吗？将停止当前运行实例并按该版本的配置重新部署`} onConfirm={() => rollback(record.versionNo)}>
                  <Button size="small" danger loading={rollingBack === record.versionNo}>回滚至此</Button>
                </Popconfirm>
              )
          }
        ]}
      />
    </Drawer>
  );
}
