import {
  CheckCircleOutlined,
  DiffOutlined,
  RollbackOutlined,
  StopOutlined
} from '@ant-design/icons';
import {
  Button,
  Descriptions,
  Drawer,
  Form,
  Input,
  Modal,
  Space,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  errorMessage,
  listApiVersions,
  reviewApiVersion,
  rollbackApiVersion,
  type AdminUser,
  type ApiVersionRecord,
  type DataApiRecord
} from './api';

interface Props {
  api: DataApiRecord | null;
  user: AdminUser;
  canApprove: boolean;
  onClose: () => void;
  onChanged: () => Promise<void>;
}

const statusColor: Record<string, string> = {
  DRAFT: 'default',
  PENDING_APPROVAL: 'processing',
  REJECTED: 'error',
  PUBLISHED: 'success',
  ARCHIVED: 'warning'
};

export default function ApiVersionDrawer({
  api,
  user,
  canApprove,
  onClose,
  onChanged
}: Props) {
  const [versions, setVersions] = useState<ApiVersionRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [compareVersion, setCompareVersion] = useState<ApiVersionRecord | null>(null);
  const [reviewVersion, setReviewVersion] = useState<ApiVersionRecord | null>(null);
  const [reviewAction, setReviewAction] = useState<'APPROVE' | 'REJECT'>('APPROVE');
  const [rollbackVersion, setRollbackVersion] = useState<ApiVersionRecord | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [reviewForm] = Form.useForm();
  const [rollbackForm] = Form.useForm();

  useEffect(() => {
    if (!api) {
      setVersions([]);
      return;
    }
    setLoading(true);
    listApiVersions(api.id)
      .then(setVersions)
      .catch((error) => message.error(errorMessage(error, '加载版本历史失败')))
      .finally(() => setLoading(false));
  }, [api]);

  const published = useMemo(
    () => versions.find((version) => version.status === 'PUBLISHED'),
    [versions]
  );

  const refresh = async () => {
    if (!api) return;
    setVersions(await listApiVersions(api.id));
    await onChanged();
  };

  const review = async (values: { comment?: string }) => {
    if (!api || !reviewVersion) return;
    setSubmitting(true);
    try {
      await reviewApiVersion(
        api.id,
        reviewVersion.versionNo,
        reviewAction,
        values.comment
      );
      message.success(reviewAction === 'APPROVE' ? '版本已批准并发布' : '版本已驳回');
      setReviewVersion(null);
      reviewForm.resetFields();
      await refresh();
    } catch (error) {
      message.error(errorMessage(error, '审批失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const rollback = async (values: { changeSummary: string }) => {
    if (!api || !rollbackVersion) return;
    setSubmitting(true);
    try {
      await rollbackApiVersion(api.id, rollbackVersion.versionNo, values.changeSummary);
      message.success(`已回滚并发布为新版本`);
      setRollbackVersion(null);
      rollbackForm.resetFields();
      await refresh();
    } catch (error) {
      message.error(errorMessage(error, '回滚失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const openReview = (version: ApiVersionRecord, action: 'APPROVE' | 'REJECT') => {
    setReviewVersion(version);
    setReviewAction(action);
    reviewForm.resetFields();
  };

  return (
    <>
      <Drawer
        title={api ? `${api.name} · 版本治理` : '版本治理'}
        open={Boolean(api)}
        onClose={onClose}
        width={980}
        destroyOnClose
      >
        <Table<ApiVersionRecord>
          rowKey="id"
          loading={loading}
          dataSource={versions}
          pagination={false}
          scroll={{ x: 1050 }}
          columns={[
            { title: '版本', dataIndex: 'versionNo', width: 80, render: (value) => `v${value}` },
            { title: '状态', dataIndex: 'status', width: 150, render: (value) => <Tag color={statusColor[value]}>{value}</Tag> },
            {
              title: '变更说明',
              dataIndex: 'changeSummary',
              width: 220,
              render: (value, row) => (
                <div className="primary-cell">
                  <strong>{value || '未填写'}</strong>
                  <span>{row.sourceVersionId ? `回滚来源 #${row.sourceVersionId}` : row.createdBy}</span>
                </div>
              )
            },
            { title: '提交人', dataIndex: 'submittedBy', width: 110, render: (value) => value || '-' },
            { title: '审批人', dataIndex: 'reviewedBy', width: 110, render: (value) => value || '-' },
            {
              title: '创建时间',
              dataIndex: 'createdAt',
              width: 180,
              render: (value) => new Date(value).toLocaleString('zh-CN', { hour12: false })
            },
            {
              title: '操作',
              fixed: 'right',
              width: 280,
              render: (_, row) => (
                <Space size={0}>
                  <Button type="link" icon={<DiffOutlined />} onClick={() => setCompareVersion(row)}>
                    对比
                  </Button>
                  {canApprove && row.status === 'PENDING_APPROVAL' && row.submittedBy !== user.username && (
                    <>
                      <Button type="link" icon={<CheckCircleOutlined />} onClick={() => openReview(row, 'APPROVE')}>
                        通过
                      </Button>
                      <Button type="link" danger icon={<StopOutlined />} onClick={() => openReview(row, 'REJECT')}>
                        驳回
                      </Button>
                    </>
                  )}
                  {canApprove && row.status === 'ARCHIVED' && (
                    <Button type="link" icon={<RollbackOutlined />} onClick={() => setRollbackVersion(row)}>
                      回滚
                    </Button>
                  )}
                </Space>
              )
            }
          ]}
        />
      </Drawer>

      <Modal
        title={`版本对比 · v${compareVersion?.versionNo || ''} vs 线上 v${published?.versionNo || '-'}`}
        open={Boolean(compareVersion)}
        onCancel={() => setCompareVersion(null)}
        footer={<Button onClick={() => setCompareVersion(null)}>关闭</Button>}
        width={900}
        destroyOnHidden
      >
        {compareVersion && (
          <div className="version-compare">
            <Descriptions bordered size="small" column={2}>
              <Descriptions.Item label="对比版本">{compareVersion.name}</Descriptions.Item>
              <Descriptions.Item label="线上版本">{published?.name || '暂无线上版本'}</Descriptions.Item>
              <Descriptions.Item label="路径">{compareVersion.method} {compareVersion.path}</Descriptions.Item>
              <Descriptions.Item label="线上路径">{published ? `${published.method} ${published.path}` : '-'}</Descriptions.Item>
              <Descriptions.Item label="数据集">#{compareVersion.datasetId}</Descriptions.Item>
              <Descriptions.Item label="线上数据集">{published ? `#${published.datasetId}` : '-'}</Descriptions.Item>
              <Descriptions.Item label="最大分页">{compareVersion.maxPageSize}</Descriptions.Item>
              <Descriptions.Item label="线上最大分页">{published?.maxPageSize ?? '-'}</Descriptions.Item>
            </Descriptions>
            <div className="sql-compare-grid">
              <div>
                <Typography.Text strong>v{compareVersion.versionNo} SQL</Typography.Text>
                <pre>{compareVersion.querySql}</pre>
              </div>
              <div>
                <Typography.Text strong>线上 SQL</Typography.Text>
                <pre>{published?.querySql || '暂无线上版本'}</pre>
              </div>
            </div>
          </div>
        )}
      </Modal>

      <Modal
        title={`${reviewAction === 'APPROVE' ? '批准' : '驳回'} v${reviewVersion?.versionNo || ''}`}
        open={Boolean(reviewVersion)}
        onCancel={() => setReviewVersion(null)}
        onOk={() => reviewForm.submit()}
        confirmLoading={submitting}
        okButtonProps={{ danger: reviewAction === 'REJECT' }}
        destroyOnHidden
      >
        <Form form={reviewForm} layout="vertical" onFinish={review}>
          <Form.Item
            name="comment"
            label="审批意见"
            rules={reviewAction === 'REJECT' ? [{ required: true, message: '请输入驳回原因' }] : []}
          >
            <Input.TextArea rows={4} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`回滚到 v${rollbackVersion?.versionNo || ''}`}
        open={Boolean(rollbackVersion)}
        onCancel={() => setRollbackVersion(null)}
        onOk={() => rollbackForm.submit()}
        confirmLoading={submitting}
        destroyOnHidden
      >
        <Form form={rollbackForm} layout="vertical" onFinish={rollback}>
          <Form.Item
            name="changeSummary"
            label="回滚说明"
            rules={[{ required: true, message: '请输入回滚原因和影响范围' }]}
          >
            <Input.TextArea rows={4} placeholder="说明回滚原因和影响范围" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  );
}
