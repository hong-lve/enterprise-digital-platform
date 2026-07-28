import { AuditOutlined, ReloadOutlined } from '@ant-design/icons';
import { Button, Input, Select, Space, Table, Tag, Typography } from 'antd';
import { useEffect, useState } from 'react';
import { pageAuditLog, type AuditLogRecord } from '../api/auditLog';

export function AuditLogPage() {
  const [records, setRecords] = useState<AuditLogRecord[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [current, setCurrent] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [username, setUsername] = useState('');
  const [status, setStatus] = useState<string | undefined>(undefined);

  const load = () => {
    setLoading(true);
    pageAuditLog({ current, pageSize, username: username || undefined, status })
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

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><AuditOutlined /> 操作审计</Typography.Title>
          <Typography.Paragraph type="secondary">
            记录谁在什么时候对哪个接口做了修改性操作（新建/编辑/删除/启动/停止等）以及是否成功——不记录请求体本身，避免数据源密码、TOTP 密钥这类敏感字段进日志。登录/登出走单独的登录日志，这里不重复记录。
          </Typography.Paragraph>
        </div>
        <Space>
          <Input.Search
            placeholder="按用户名过滤"
            allowClear
            style={{ width: 180 }}
            onSearch={(value) => {
              setUsername(value);
              setCurrent(1);
              load();
            }}
          />
          <Select
            placeholder="状态"
            allowClear
            style={{ width: 120 }}
            value={status}
            onChange={(value) => {
              setStatus(value);
              setCurrent(1);
            }}
            options={[{ value: 'SUCCESS', label: '成功' }, { value: 'FAILURE', label: '失败' }]}
          />
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
        </Space>
      </Space>

      <Table<AuditLogRecord>
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
          { title: '时间', dataIndex: 'occurredAt', width: 170 },
          { title: '用户', dataIndex: 'username', width: 120, render: (value?: string) => value || <Tag>匿名/未登录</Tag> },
          { title: 'IP', dataIndex: 'ipAddress', width: 140 },
          { title: '方法', dataIndex: 'httpMethod', width: 80 },
          { title: '路径', dataIndex: 'path', ellipsis: true },
          { title: '权限点', dataIndex: 'permission', ellipsis: true, render: (value?: string) => value || '-' },
          {
            title: '结果',
            dataIndex: 'status',
            width: 90,
            render: (value: string) => <Tag color={value === 'SUCCESS' ? 'green' : 'red'}>{value === 'SUCCESS' ? '成功' : '失败'}</Tag>
          },
          { title: '错误信息', dataIndex: 'errorMessage', ellipsis: true, render: (value?: string) => value || '-' }
        ]}
      />
    </div>
  );
}
