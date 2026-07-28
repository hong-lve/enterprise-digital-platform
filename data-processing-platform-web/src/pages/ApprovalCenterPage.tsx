import { CheckOutlined, CloseOutlined, ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { Button, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { approveChangeRequest, pageApprovalRequests, rejectChangeRequest, type ChangeRequestRecord } from '../api/approval';

const actionTypeLabel: Record<string, string> = {
  DATA_SOURCE_DELETE: '删除数据源',
  CDC_SOURCE_DELETE: '删除 CDC 数据源',
  CDC_SOURCE_STOP: '停止 CDC 数据源',
  FLINK_STREAM_JOB_DELETE: '删除 Flink 流作业',
  FLINK_STREAM_JOB_STOP: '停止 Flink 流作业',
  FLINK_SQL_JOB_DELETE: '删除 SQL 流作业',
  FLINK_SQL_JOB_STOP: '停止 SQL 流作业',
  ROLE_PERMISSION_UPDATE: '修改角色权限',
  USER_DISABLE: '禁用用户',
  USER_PASSWORD_RESET: '重置用户密码'
};

const statusColor: Record<string, string> = {
  PENDING: 'orange',
  APPROVED: 'green',
  REJECTED: 'red'
};

const statusLabel: Record<string, string> = {
  PENDING: '待审批',
  APPROVED: '已通过',
  REJECTED: '已驳回'
};

export function ApprovalCenterPage() {
  const [records, setRecords] = useState<ChangeRequestRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [status, setStatus] = useState<string | undefined>('PENDING');
  const [busyId, setBusyId] = useState<number | null>(null);
  const [rejectTarget, setRejectTarget] = useState<ChangeRequestRecord | null>(null);
  const [rejectReason, setRejectReason] = useState('');

  const load = () => {
    setLoading(true);
    pageApprovalRequests({ current, pageSize, status })
      .then((data) => {
        setRecords(data.records);
        setTotal(data.total);
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current, pageSize, status]);

  const approve = (record: ChangeRequestRecord) => {
    setBusyId(record.id);
    approveChangeRequest(record.id)
      .then(() => {
        message.success('已批准，操作已生效');
        load();
      })
      .finally(() => setBusyId(null));
  };

  const confirmReject = () => {
    if (!rejectTarget) {
      return;
    }
    setBusyId(rejectTarget.id);
    rejectChangeRequest(rejectTarget.id, rejectReason)
      .then(() => {
        message.success('已驳回');
        setRejectTarget(null);
        setRejectReason('');
        load();
      })
      .finally(() => setBusyId(null));
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><SafetyCertificateOutlined /> 审批中心</Typography.Title>
          <Typography.Paragraph type="secondary">
            生产环境（PROD）资源的删除/停止操作需要另一名审批人处理——发起人不能审批自己提交的申请。开发环境（DEV）资源不受影响，操作即时生效。
          </Typography.Paragraph>
        </div>
        <Space>
          <Select
            placeholder="状态"
            allowClear
            style={{ width: 140 }}
            value={status}
            onChange={(value) => {
              setStatus(value);
              setCurrent(1);
            }}
            options={[
              { value: 'PENDING', label: '待审批' },
              { value: 'APPROVED', label: '已通过' },
              { value: 'REJECTED', label: '已驳回' }
            ]}
          />
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
        </Space>
      </Space>

      <Table<ChangeRequestRecord>
        rowKey="id"
        loading={loading}
        dataSource={records}
        scroll={{ x: true }}
        pagination={{
          current,
          pageSize,
          total,
          showSizeChanger: true,
          onChange: (page, size) => {
            setCurrent(page);
            setPageSize(size);
          }
        }}
        columns={[
          { title: '申请编号', dataIndex: 'id', width: 90 },
          { title: '操作类型', dataIndex: 'actionType', width: 160, render: (value: string) => actionTypeLabel[value] || value },
          { title: '目标资源', dataIndex: 'targetSummary', ellipsis: true, render: (value?: string) => value || '-' },
          { title: '发起人', dataIndex: 'requester', width: 120 },
          {
            title: '状态',
            dataIndex: 'status',
            width: 90,
            render: (value: string) => <Tag color={statusColor[value]}>{statusLabel[value] || value}</Tag>
          },
          { title: '审批人', dataIndex: 'approver', width: 120, render: (value?: string) => value || '-' },
          { title: '驳回理由', dataIndex: 'rejectReason', ellipsis: true, render: (value?: string) => value || '-' },
          { title: '发起时间', dataIndex: 'createdAt', width: 170 },
          { title: '处理时间', dataIndex: 'decidedAt', width: 170, render: (value?: string) => value || '-' },
          {
            title: '操作',
            key: 'actions',
            width: 160,
            render: (_, record) =>
              record.status === 'PENDING' ? (
                <Space>
                  <Popconfirm title="确定批准该申请？操作将立即生效" onConfirm={() => approve(record)}>
                    <Button size="small" type="primary" icon={<CheckOutlined />} loading={busyId === record.id}>批准</Button>
                  </Popconfirm>
                  <Button
                    size="small"
                    danger
                    icon={<CloseOutlined />}
                    loading={busyId === record.id}
                    onClick={() => {
                      setRejectTarget(record);
                      setRejectReason('');
                    }}
                  >
                    驳回
                  </Button>
                </Space>
              ) : (
                '-'
              )
          }
        ]}
      />

      <Modal
        title="驳回申请"
        open={rejectTarget !== null}
        onOk={confirmReject}
        onCancel={() => setRejectTarget(null)}
        confirmLoading={busyId === rejectTarget?.id}
      >
        <Input.TextArea
          rows={3}
          placeholder="驳回理由（可选）"
          value={rejectReason}
          onChange={(event) => setRejectReason(event.target.value)}
        />
      </Modal>
    </div>
  );
}
