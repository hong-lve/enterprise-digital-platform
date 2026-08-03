import { Button, Drawer, Modal, Select, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { fetchCdcSourceSubjects, fetchSchemaDiff, setSchemaCompatibility, type SchemaDiffResponse, type SchemaSubjectSummary } from '../api/schemaRegistry';

const compatibilityOptions = [
  { value: 'BACKWARD', label: 'BACKWARD（新 schema 能读旧数据）' },
  { value: 'BACKWARD_TRANSITIVE', label: 'BACKWARD_TRANSITIVE' },
  { value: 'FORWARD', label: 'FORWARD（旧 schema 能读新数据）' },
  { value: 'FORWARD_TRANSITIVE', label: 'FORWARD_TRANSITIVE' },
  { value: 'FULL', label: 'FULL（双向兼容）' },
  { value: 'FULL_TRANSITIVE', label: 'FULL_TRANSITIVE' },
  { value: 'NONE', label: 'NONE（不做兼容性检查）' }
];

interface SchemaDrawerProps {
  cdcSourceId: number | null;
  cdcSourceName?: string;
  canManage: boolean;
  onClose: () => void;
}

/** Per-CDC-source Schema Registry view: subjects/versions/compatibility mode + a diff viewer between the two latest versions of a table's schema. */
export function SchemaDrawer({ cdcSourceId, cdcSourceName, canManage, onClose }: SchemaDrawerProps) {
  const [subjects, setSubjects] = useState<SchemaSubjectSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [diffTarget, setDiffTarget] = useState<SchemaSubjectSummary | null>(null);
  const [diff, setDiff] = useState<SchemaDiffResponse | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);

  const load = (id: number) => {
    setLoading(true);
    fetchCdcSourceSubjects(id).then(setSubjects).finally(() => setLoading(false));
  };

  useEffect(() => {
    if (cdcSourceId !== null) {
      load(cdcSourceId);
    } else {
      setSubjects([]);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cdcSourceId]);

  const changeCompatibility = (subject: string, level: string) => {
    setSchemaCompatibility(subject, level).then(() => {
      message.success('已更新兼容模式');
      if (cdcSourceId !== null) load(cdcSourceId);
    });
  };

  const openDiff = (record: SchemaSubjectSummary) => {
    if (record.versions.length < 2) return;
    const sorted = [...record.versions].sort((a, b) => a - b);
    const toVersion = sorted[sorted.length - 1];
    const fromVersion = sorted[sorted.length - 2];
    setDiffTarget(record);
    setDiff(null);
    setDiffLoading(true);
    fetchSchemaDiff(record.subject, fromVersion, toVersion).then(setDiff).finally(() => setDiffLoading(false));
  };

  return (
    <>
      <Drawer title={`Schema 管理${cdcSourceName ? ' - ' + cdcSourceName : ''}`} open={cdcSourceId !== null} onClose={onClose} width={820}>
        <Typography.Paragraph type="secondary">
          每张表对应一个 Kafka topic 和一个 Schema Registry subject。兼容模式决定了 Debezium 检测到上游表结构变化时新 schema 能否被接受；版本数达到 2 个以上时可以查看最近一次变更的字段差异。
        </Typography.Paragraph>
        <Table<SchemaSubjectSummary>
          rowKey="subject"
          size="small"
          loading={loading}
          dataSource={subjects}
          pagination={false}
          locale={{ emptyText: '暂无数据（该表还没有任何 CDC 事件写入过 Kafka）' }}
          columns={[
            { title: '表', dataIndex: 'tableRef' },
            { title: 'Topic', dataIndex: 'topic', ellipsis: true },
            { title: '版本数', dataIndex: 'versions', width: 80, render: (value: number[]) => value.length || '-' },
            {
              title: '兼容模式',
              dataIndex: 'compatibilityLevel',
              width: 220,
              render: (value: string | undefined, record) =>
                value === undefined
                  ? '-'
                  : canManage
                    ? (
                      <Select
                        size="small"
                        value={value}
                        style={{ width: 200 }}
                        options={compatibilityOptions}
                        onChange={(level) => changeCompatibility(record.subject, level)}
                      />
                    )
                    : <Tag>{value}</Tag>
            },
            {
              title: '操作',
              width: 100,
              render: (_, record) => (
                <Button size="small" disabled={record.versions.length < 2} onClick={() => openDiff(record)}>查看差异</Button>
              )
            }
          ]}
        />
      </Drawer>

      <Modal title={`字段差异 - ${diffTarget?.tableRef ?? ''}`} open={!!diffTarget} onCancel={() => setDiffTarget(null)} footer={null} width={640}>
        {diffLoading && '加载中...'}
        {!diffLoading && diff && (
          <>
            <p>
              版本 {diff.fromVersion} → {diff.toVersion}：
              <Tag color={diff.compatible ? 'green' : 'red'}>{diff.compatible ? '兼容' : '不兼容'}</Tag>
            </p>
            {diff.removedFields.length > 0 && <p>删除字段：{diff.removedFields.join('、')}</p>}
            {diff.addedFields.length > 0 && <p>新增字段：{diff.addedFields.join('、')}</p>}
            {diff.typeChanges.length > 0 && (
              <ul>
                {diff.typeChanges.map((change) => (
                  <li key={change.field}>{change.field}：{change.oldType} → {change.newType}</li>
                ))}
              </ul>
            )}
            {diff.removedFields.length === 0 && diff.addedFields.length === 0 && diff.typeChanges.length === 0 && (
              <p>未检测到字段级差异（可能是元数据或非表结构相关的 schema 调整）</p>
            )}
            {diff.compatibilityDetail && <p style={{ color: '#888' }}>{diff.compatibilityDetail}</p>}
          </>
        )}
      </Modal>
    </>
  );
}
