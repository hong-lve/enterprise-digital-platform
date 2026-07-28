import {
  CheckCircleOutlined,
  EditOutlined,
  LinkOutlined,
  PlusOutlined,
  PoweroffOutlined
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import { useState } from 'react';
import {
  changeDataSourceStatus,
  createDataSource,
  errorMessage,
  testDataSource,
  updateDataSource,
  type DataSourceRecord,
  type SaveDataSourceRequest
} from './api';

interface Props {
  dataSources: DataSourceRecord[];
  loading: boolean;
  canManage: boolean;
  onChanged: () => Promise<void>;
}

const enginePorts: Record<DataSourceRecord['engineType'], number> = {
  MYSQL: 3306,
  ORACLE: 1521,
  DORIS: 9030,
  CLICKHOUSE: 8123
};

const statusColor: Record<string, string> = {
  ACTIVE: 'success',
  DRAFT: 'default',
  DISABLED: 'warning',
  SUCCESS: 'success',
  FAILED: 'error'
};

export default function DataSourceManagement({ dataSources, loading, canManage, onChanged }: Props) {
  const [editing, setEditing] = useState<DataSourceRecord | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [actingId, setActingId] = useState<number | null>(null);
  const [form] = Form.useForm<SaveDataSourceRequest>();
  const engine = Form.useWatch('engineType', form);

  const openCreate = () => {
    setEditing(null);
    form.setFieldsValue({
      engineType: 'MYSQL',
      port: 3306,
      poolMinIdle: 0,
      poolMaxSize: 10,
      connectionTimeoutMs: 10000,
      queryTimeoutSeconds: 10,
      environment: 'DEV'
    });
    setModalOpen(true);
  };

  const openEdit = (source: DataSourceRecord) => {
    setEditing(source);
    form.setFieldsValue({
      name: source.name,
      engineType: source.engineType,
      host: source.host,
      port: source.port,
      databaseName: source.databaseName,
      username: source.username,
      password: undefined,
      poolMinIdle: source.poolMinIdle,
      poolMaxSize: source.poolMaxSize,
      connectionTimeoutMs: source.connectionTimeoutMs,
      queryTimeoutSeconds: source.queryTimeoutSeconds,
      environment: source.environment,
      owner: source.owner
    });
    setModalOpen(true);
  };

  const save = async (values: SaveDataSourceRequest) => {
    setSubmitting(true);
    try {
      if (editing) {
        await updateDataSource(editing.id, values);
        message.success('数据源已更新，请重新测试后启用');
      } else {
        await createDataSource(values);
        message.success('数据源已登记，请先执行连接测试');
      }
      setModalOpen(false);
      form.resetFields();
      await onChanged();
    } catch (error) {
      message.error(errorMessage(error, '保存数据源失败'));
    } finally {
      setSubmitting(false);
    }
  };

  const test = async (source: DataSourceRecord) => {
    setActingId(source.id);
    try {
      await testDataSource(source.id);
      message.success('连接测试成功');
      await onChanged();
    } catch (error) {
      message.error(errorMessage(error, '连接测试失败'));
      await onChanged();
    } finally {
      setActingId(null);
    }
  };

  const changeStatus = async (source: DataSourceRecord) => {
    setActingId(source.id);
    try {
      const action = source.status === 'ACTIVE' ? 'DISABLE' : 'ENABLE';
      await changeDataSourceStatus(source.id, action);
      message.success(action === 'ENABLE' ? '数据源已启用' : '数据源已停用');
      await onChanged();
    } catch (error) {
      message.error(errorMessage(error, '状态变更失败'));
    } finally {
      setActingId(null);
    }
  };

  return (
    <>
      <div className="section-toolbar">
        <div>
          <Typography.Title level={5}>受管数据源</Typography.Title>
          <Typography.Text type="secondary">密码加密保存，查询通过隔离连接池执行</Typography.Text>
        </div>
        {canManage && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新增数据源</Button>
        )}
      </div>
      <Table<DataSourceRecord>
        rowKey="id"
        loading={loading}
        dataSource={dataSources}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        scroll={{ x: 1180 }}
        columns={[
          {
            title: '数据源',
            dataIndex: 'name',
            width: 200,
            render: (value, row) => (
              <div className="primary-cell">
                <strong>{value}</strong>
                <span>{row.host}:{row.port}</span>
              </div>
            )
          },
          { title: '引擎', dataIndex: 'engineType', width: 110, render: (value) => <Tag>{value}</Tag> },
          { title: '数据库 / 服务', dataIndex: 'databaseName', width: 160 },
          { title: '环境', dataIndex: 'environment', width: 90 },
          { title: '连接池', width: 100, render: (_, row) => `${row.poolMinIdle}-${row.poolMaxSize}` },
          { title: '查询超时', dataIndex: 'queryTimeoutSeconds', width: 100, render: (value) => `${value}s` },
          {
            title: '连接测试',
            width: 130,
            render: (_, row) => (
              <Space direction="vertical" size={0}>
                <Tag color={statusColor[row.lastTestStatus || '']}>{row.lastTestStatus || '未测试'}</Tag>
                {row.lastTestMessage && <Typography.Text type="secondary" ellipsis>{row.lastTestMessage}</Typography.Text>}
              </Space>
            )
          },
          { title: '状态', dataIndex: 'status', width: 100, render: (value) => <Tag color={statusColor[value]}>{value}</Tag> },
          { title: '负责人', dataIndex: 'owner', width: 110, render: (value) => value || '-' },
          {
            title: '操作',
            fixed: 'right',
            width: 260,
            render: (_, row) => canManage ? (
              <Space size={0}>
                <Button type="link" icon={<LinkOutlined />} loading={actingId === row.id} onClick={() => test(row)}>测试</Button>
                <Button type="link" icon={<EditOutlined />} onClick={() => openEdit(row)}>编辑</Button>
                <Button
                  type="link"
                  danger={row.status === 'ACTIVE'}
                  icon={row.status === 'ACTIVE' ? <PoweroffOutlined /> : <CheckCircleOutlined />}
                  disabled={row.status !== 'ACTIVE' && row.lastTestStatus !== 'SUCCESS'}
                  onClick={() => changeStatus(row)}
                >
                  {row.status === 'ACTIVE' ? '停用' : '启用'}
                </Button>
              </Space>
            ) : '-'
          }
        ]}
        locale={{ emptyText: '还没有受管数据源' }}
      />

      <Modal
        title={editing ? '编辑数据源' : '新增数据源'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        okText="保存"
        width={760}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={save}>
          <div className="form-grid">
            <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}>
              <Input placeholder="例如 order-prod-ro" />
            </Form.Item>
            <Form.Item name="engineType" label="引擎" rules={[{ required: true }]}>
              <Select
                options={Object.keys(enginePorts).map((value) => ({ value, label: value }))}
                onChange={(value: DataSourceRecord['engineType']) => form.setFieldValue('port', enginePorts[value])}
              />
            </Form.Item>
          </div>
          <div className="form-grid">
            <Form.Item name="host" label="主机地址" rules={[{ required: true, message: '请输入可达地址' }]}>
              <Input placeholder="容器或 K8s Pod 可访问的域名 / IP" />
            </Form.Item>
            <Form.Item name="port" label="端口" rules={[{ required: true }]}>
              <InputNumber min={1} max={65535} style={{ width: '100%' }} />
            </Form.Item>
          </div>
          <Form.Item
            name="databaseName"
            label={engine === 'ORACLE' ? 'Oracle Service / PDB 名称' : '数据库名称'}
            rules={[{ required: true, message: '请输入数据库或服务名' }]}
          >
            <Input placeholder={engine === 'ORACLE' ? '例如 FREEPDB1' : '例如 order_db'} />
          </Form.Item>
          <div className="form-grid">
            <Form.Item name="username" label="只读账号" rules={[{ required: true, message: '请输入用户名' }]}>
              <Input autoComplete="off" />
            </Form.Item>
            <Form.Item
              name="password"
              label="密码"
              rules={editing ? [] : [{ required: true, message: '请输入密码' }]}
              extra={editing ? '留空表示不修改现有密码' : undefined}
            >
              <Input.Password autoComplete="new-password" />
            </Form.Item>
          </div>
          <div className="form-grid">
            <Form.Item name="environment" label="环境" rules={[{ required: true }]}>
              <Select options={['DEV', 'TEST', 'PROD'].map((value) => ({ value, label: value }))} />
            </Form.Item>
            <Form.Item name="owner" label="负责人"><Input /></Form.Item>
          </div>
          <div className="form-grid pool-grid">
            <Form.Item name="poolMinIdle" label="最小空闲连接" rules={[{ required: true }]}>
              <InputNumber min={0} max={50} />
            </Form.Item>
            <Form.Item name="poolMaxSize" label="最大连接数" rules={[{ required: true }]}>
              <InputNumber min={1} max={100} />
            </Form.Item>
            <Form.Item name="connectionTimeoutMs" label="连接超时(ms)" rules={[{ required: true }]}>
              <InputNumber min={1000} max={60000} />
            </Form.Item>
            <Form.Item name="queryTimeoutSeconds" label="查询超时(s)" rules={[{ required: true }]}>
              <InputNumber min={1} max={300} />
            </Form.Item>
          </div>
          <Alert
            type="info"
            showIcon
            message="保存后需要先测试连接，再手动启用；建议使用只读数据库账号。"
          />
        </Form>
      </Modal>
    </>
  );
}
