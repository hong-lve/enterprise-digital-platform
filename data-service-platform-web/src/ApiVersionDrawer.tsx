import {
  CheckCircleOutlined,
  DiffOutlined,
  RollbackOutlined,
  SafetyCertificateOutlined,
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
  getContractReport,
  listApiVersions,
  reviewApiVersion,
  rollbackApiVersion,
  type AdminUser,
  type ApiVersionRecord,
  type ApplicationRecord,
  type ContractReport,
  type DataApiRecord
} from './api';
import CanaryRolloutManagement from './CanaryRolloutManagement';
import ContractTestManagement from './ContractTestManagement';

interface Props {
  api: DataApiRecord | null;
  user: AdminUser;
  applications: ApplicationRecord[];
  canApprove: boolean;
  onClose: () => void;
  onChanged: () => Promise<void>;
}

const statusColor: Record<string, string> = {
  DRAFT: 'default',
  PENDING_APPROVAL: 'processing',
  CANARY: 'cyan',
  REJECTED: 'error',
  PUBLISHED: 'success',
  ARCHIVED: 'warning'
};

export default function ApiVersionDrawer({
  api,
  user,
  applications,
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
  const [contractReport, setContractReport] = useState<ContractReport | null>(null);
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

  const openContractReport = async (version: ApiVersionRecord) => {
    if (!api) return;
    setLoading(true);
    try {
      setContractReport(await getContractReport(api.id, version.versionNo));
    } catch (error) {
      message.error(errorMessage(error, '加载契约报告失败'));
    } finally {
      setLoading(false);
    }
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
              width: 350,
              render: (_, row) => (
                <Space size={0}>
                  <Button type="link" icon={<SafetyCertificateOutlined />} onClick={() => openContractReport(row)}>
                    契约
                  </Button>
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
        {api && (
          <CanaryRolloutManagement
            api={api}
            versions={versions}
            applications={applications}
            canRead={user.permissions.includes('CANARY_READ')}
            canManage={user.permissions.includes('CANARY_MANAGE')}
            onChanged={refresh}
          />
        )}
        {api && (
          <ContractTestManagement
            api={api}
            versions={versions}
            canManage={user.permissions.includes('CONTRACT_TEST_MANAGE')}
          />
        )}
      </Drawer>

      <Modal
        title={`契约兼容性报告 · v${contractReport?.versionNo || ''}`}
        open={Boolean(contractReport)}
        onCancel={() => setContractReport(null)}
        footer={<Button onClick={() => setContractReport(null)}>关闭</Button>}
        width={780}
      >
        {contractReport && (
          <>
            <Descriptions size="small" bordered column={2}>
              <Descriptions.Item label="检查结果">
                <Tag color={contractReport.severity === 'BREAKING' ? 'error' : contractReport.severity === 'RISKY' ? 'warning' : 'success'}>
                  {contractReport.severity}
                </Tag>
              </Descriptions.Item>
              <Descriptions.Item label="基线版本">
                {contractReport.baselineVersionNo ? `v${contractReport.baselineVersionNo}` : '首次发布'}
              </Descriptions.Item>
            </Descriptions>
            <Table
              rowKey={(row) => `${row.code}-${row.subject}`}
              dataSource={contractReport.findings}
              pagination={false}
              size="small"
              className="contract-findings"
              columns={[
                {
                  title: '级别',
                  dataIndex: 'level',
                  width: 100,
                  render: (value) => <Tag color={value === 'BREAKING' ? 'error' : value === 'RISKY' ? 'warning' : 'blue'}>{value}</Tag>
                },
                { title: '对象', dataIndex: 'subject', width: 160 },
                { title: '检查项', dataIndex: 'code', width: 200 },
                { title: '说明', dataIndex: 'message' }
              ]}
            />
          </>
        )}
      </Modal>

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
