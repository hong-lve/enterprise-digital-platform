import {
  ApiOutlined,
  AuditOutlined,
  BookOutlined,
  CheckCircleOutlined,
  CloudServerOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  EditOutlined,
  HddOutlined,
  HistoryOutlined,
  KeyOutlined,
  LogoutOutlined,
  MinusCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  StopOutlined,
  ThunderboltOutlined,
  TeamOutlined,
  UserOutlined
} from '@ant-design/icons';
import {
  Alert,
  Button,
  Card,
  Checkbox,
  Form,
  Input,
  InputNumber,
  Layout,
  Modal,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import { useEffect, useMemo, useState } from 'react';
import ApiDebugger from './ApiDebugger';
import ApplicationManagement from './ApplicationManagement';
import DataSourceManagement from './DataSourceManagement';
import { useAuth } from './AuthGate';
import AdminUserManagement from './AdminUserManagement';
import ApiVersionDrawer from './ApiVersionDrawer';
import DatasetPolicyDrawer from './DatasetPolicyDrawer';
import RuntimeGovernance from './RuntimeGovernance';
import GovernanceCenter from './GovernanceCenter';
import SubscriptionManagement from './SubscriptionManagement';
import DeveloperPortal from './DeveloperPortal';
import {
  changeApiStatus,
  checkOpenApiHealth,
  createApi,
  createDataset,
  errorMessage,
  listApis,
  listApplications,
  listCallLogs,
  listDatasets,
  listDataSources,
  submitApiForApproval,
  updateApi,
  type ApplicationRecord,
  type CallLogRecord,
  type DataApiRecord,
  type DatasetRecord,
  type DataSourceRecord,
  type OpenApiHealth
} from './api';

const { Header, Content, Sider } = Layout;
const { TextArea } = Input;
type ViewKey = 'apis' | 'dataSources' | 'datasets' | 'gateway' | 'portal' | 'runtime' | 'governance' | 'logs' | 'users';

const statusColors: Record<string, string> = {
  PUBLISHED: 'success',
  ACTIVE: 'success',
  DRAFT: 'default',
  OFFLINE: 'warning',
  PENDING_APPROVAL: 'processing',
  REJECTED: 'error',
  ARCHIVED: 'warning'
};

function formatTime(value?: string) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '-';
}

export default function App() {
  const { user, logout, hasPermission } = useAuth();
  const [datasets, setDatasets] = useState<DatasetRecord[]>([]);
  const [dataSources, setDataSources] = useState<DataSourceRecord[]>([]);
  const [apis, setApis] = useState<DataApiRecord[]>([]);
  const [callLogs, setCallLogs] = useState<CallLogRecord[]>([]);
  const [applications, setApplications] = useState<ApplicationRecord[]>([]);
  const [health, setHealth] = useState<OpenApiHealth | null>(null);
  const [activeView, setActiveView] = useState<ViewKey>('apis');
  const [loading, setLoading] = useState(false);
  const [datasetModalOpen, setDatasetModalOpen] = useState(false);
  const [apiModalOpen, setApiModalOpen] = useState(false);
  const [debugApi, setDebugApi] = useState<DataApiRecord | null>(null);
  const [editingApi, setEditingApi] = useState<DataApiRecord | null>(null);
  const [versionApi, setVersionApi] = useState<DataApiRecord | null>(null);
  const [policyDataset, setPolicyDataset] = useState<DatasetRecord | null>(null);
  const [datasetForm] = Form.useForm();
  const [apiForm] = Form.useForm();

  const load = async () => {
    setLoading(true);
    try {
      const [datasetRows, dataSourceRows, apiRows, applicationRows, logRows, healthResult] = await Promise.all([
        listDatasets(),
        listDataSources(),
        listApis(),
        listApplications(),
        listCallLogs(),
        checkOpenApiHealth()
      ]);
      setDatasets(datasetRows);
      setDataSources(dataSourceRows);
      setApis(apiRows);
      setApplications(applicationRows);
      setCallLogs(logRows);
      setHealth(healthResult);
    } catch (error) {
      message.error(errorMessage(error, '加载平台数据失败'));
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  const metrics = useMemo(() => ({
    datasetCount: datasets.length,
    apiCount: apis.length,
    publishedCount: apis.filter((api) => api.status === 'PUBLISHED').length,
    applicationCount: applications.length,
    successRate: callLogs.length
      ? Math.round(callLogs.filter((log) => log.statusCode < 400).length * 100 / callLogs.length)
      : 100
  }), [datasets, apis, applications, callLogs]);

  const submitDataset = async (values: Partial<DatasetRecord>) => {
    try {
      const selectedSource = dataSources.find((item) => item.id === values.connectionId);
      await createDataset(selectedSource ? {
        ...values,
        sourceType: selectedSource.engineType,
        sourceName: selectedSource.name,
        connectionMode: 'MANAGED'
      } : {
        ...values,
        connectionId: undefined,
        sourceType: 'MYSQL',
        sourceName: 'platform-mysql',
        connectionMode: 'PLATFORM'
      });
      message.success('数据集已登记');
      setDatasetModalOpen(false);
      datasetForm.resetFields();
      await load();
    } catch (error) {
      message.error(errorMessage(error, '创建数据集失败'));
    }
  };

  const submitApi = async (values: Partial<DataApiRecord> & { changeSummary?: string }) => {
    try {
      if (editingApi) {
        await updateApi(editingApi.id, {
          ...values,
          changeSummary: values.changeSummary || ''
        });
        message.success('API 新版本草稿已保存');
      } else {
        await createApi(values);
        message.success('API 草稿已创建');
      }
      setApiModalOpen(false);
      setEditingApi(null);
      apiForm.resetFields();
      await load();
    } catch (error) {
      message.error(errorMessage(error, '创建 API 失败'));
    }
  };

  const updateStatus = async (api: DataApiRecord) => {
    try {
      await changeApiStatus(api.id, 'OFFLINE');
      message.success('API 已下线');
      await load();
    } catch (error) {
      message.error(errorMessage(error, '状态变更失败'));
    }
  };

  const submitApproval = async (api: DataApiRecord) => {
    try {
      await submitApiForApproval(api.id);
      message.success(`v${api.version} 已提交审批`);
      await load();
    } catch (error) {
      message.error(errorMessage(error, '提交审批失败'));
    }
  };

  const openApiModal = (api?: DataApiRecord) => {
    setEditingApi(api || null);
    apiForm.setFieldsValue(api ? {
      ...api,
      changeSummary: ''
    } : {
      method: 'GET',
      maxPageSize: 100,
      cacheTtlSeconds: 0,
      parameters: [],
      changeSummary: '创建 API'
    });
    setApiModalOpen(true);
  };

  return (
    <Layout className="app-shell">
      <Sider width={224} theme="light" className="sidebar">
        <div className="brand"><ApiOutlined /><span>数据服务平台</span></div>
        <nav className="nav-list">
          <button className={`nav-item ${activeView === 'apis' ? 'active' : ''}`} onClick={() => setActiveView('apis')}>
            <CloudServerOutlined /> API 管理
          </button>
          <button className={`nav-item ${activeView === 'datasets' ? 'active' : ''}`} onClick={() => setActiveView('datasets')}>
            <DatabaseOutlined /> 数据集
          </button>
          <button className={`nav-item ${activeView === 'dataSources' ? 'active' : ''}`} onClick={() => setActiveView('dataSources')}>
            <HddOutlined /> 数据源
          </button>
          <button className={`nav-item ${activeView === 'gateway' ? 'active' : ''}`} onClick={() => setActiveView('gateway')}>
            <KeyOutlined /> 应用与网关
          </button>
          <button className={`nav-item ${activeView === 'portal' ? 'active' : ''}`} onClick={() => setActiveView('portal')}>
            <BookOutlined /> 开发者门户
          </button>
          <button className={`nav-item ${activeView === 'runtime' ? 'active' : ''}`} onClick={() => setActiveView('runtime')}>
            <DashboardOutlined /> 运行治理
          </button>
          {hasPermission('GOVERNANCE_READ') && (
            <button className={`nav-item ${activeView === 'governance' ? 'active' : ''}`} onClick={() => setActiveView('governance')}>
              <AuditOutlined /> 企业治理
            </button>
          )}
          <button className={`nav-item ${activeView === 'logs' ? 'active' : ''}`} onClick={() => setActiveView('logs')}>
            <HistoryOutlined /> 调用审计
          </button>
          {hasPermission('USER_READ') && (
            <button className={`nav-item ${activeView === 'users' ? 'active' : ''}`} onClick={() => setActiveView('users')}>
              <TeamOutlined /> 管理账号
            </button>
          )}
        </nav>
        <div className="sidebar-status">
          <span className={`status-dot ${health ? 'online' : ''}`} />
          {health ? '服务运行正常' : '服务状态未知'}
        </div>
        <div className="sidebar-account">
          <div className="account-identity">
            <UserOutlined />
            <div>
              <strong>{user.displayName}</strong>
              <span>{user.roles.join(' / ')}</span>
            </div>
          </div>
          <Button type="text" icon={<LogoutOutlined />} title="退出登录" onClick={logout} />
        </div>
      </Sider>

      <Layout>
        <Header className="topbar">
          <div>
            <Typography.Title level={4}>
              {activeView === 'apis' && 'API 管理'}
              {activeView === 'datasets' && '数据集管理'}
              {activeView === 'dataSources' && '数据源管理'}
              {activeView === 'gateway' && '应用与网关'}
              {activeView === 'portal' && '开发者门户'}
              {activeView === 'runtime' && '运行治理'}
              {activeView === 'governance' && '企业治理'}
              {activeView === 'logs' && '调用审计'}
              {activeView === 'users' && '管理账号'}
            </Typography.Title>
            <Typography.Text type="secondary">
              {activeView === 'apis' && '定义、测试和发布受治理的数据接口'}
              {activeView === 'datasets' && '登记经过加工和质量校验的服务数据'}
              {activeView === 'dataSources' && '管理业务数据库连接、测试与运行状态'}
              {activeView === 'gateway' && '统一管理调用方凭证、授权和流量策略'}
              {activeView === 'portal' && '查询已发布 API、订阅额度和标准调用契约'}
              {activeView === 'runtime' && '监控缓存、并发隔离和熔断状态'}
              {activeView === 'governance' && '统一管理生产审批、告警通知与操作审计'}
              {activeView === 'logs' && '追踪每一次接口调用和异常'}
              {activeView === 'users' && '管理登录账号、角色和访问权限'}
            </Typography.Text>
          </div>
          <Space>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
            {activeView === 'datasets' && hasPermission('DATASET_MANAGE') && (
              <Button type="primary" icon={<PlusOutlined />} onClick={() => setDatasetModalOpen(true)}>登记数据集</Button>
            )}
            {activeView === 'apis' && hasPermission('API_MANAGE') && (
              <Button type="primary" icon={<ThunderboltOutlined />} onClick={() => openApiModal()}>新建 API</Button>
            )}
          </Space>
        </Header>

        <Content className="content">
          <section className="metrics">
            <div className="metric"><span>数据集</span><strong>{metrics.datasetCount}</strong></div>
            <div className="metric"><span>API 总数</span><strong>{metrics.apiCount}</strong></div>
            <div className="metric"><span>已发布</span><strong>{metrics.publishedCount}</strong></div>
            <div className="metric"><span>调用应用</span><strong>{metrics.applicationCount}</strong></div>
            <div className="metric"><span>近期成功率</span><strong>{metrics.successRate}%</strong></div>
          </section>

          {!health && (
            <Alert type="warning" showIcon message="运行服务暂不可用" description="请检查 data-service-platform-service。" />
          )}

          {activeView === 'apis' && (
            <Card size="small" className="work-card">
              <Table<DataApiRecord>
                rowKey="id"
                loading={loading}
                dataSource={apis}
                pagination={{ pageSize: 10, showSizeChanger: false }}
                size="middle"
                scroll={{ x: 1320 }}
                columns={[
                  {
                    title: 'API',
                    dataIndex: 'name',
                    width: 220,
                    render: (value, row) => (
                      <div className="primary-cell">
                        <strong>{value}</strong>
                        <span>{row.description || '暂无描述'}</span>
                      </div>
                    )
                  },
                  {
                    title: '请求',
                    width: 260,
                    render: (_, row) => (
                      <Space size={6}>
                        <Tag color={row.method === 'GET' ? 'blue' : 'green'}>{row.method}</Tag>
                        <Typography.Text code>{row.path}</Typography.Text>
                      </Space>
                    )
                  },
                  { title: '数据集', dataIndex: 'datasetId', width: 100, render: (id) => datasets.find((item) => item.id === id)?.name || `#${id}` },
                  { title: '参数', width: 80, render: (_, row) => row.parameters.length },
                  { title: '版本', dataIndex: 'version', width: 80, render: (value) => `v${value}` },
                  {
                    title: '版本状态',
                    dataIndex: 'latestVersionStatus',
                    width: 160,
                    render: (value) => <Tag color={statusColors[value]}>{value}</Tag>
                  },
                  {
                    title: '状态',
                    dataIndex: 'status',
                    width: 110,
                    render: (value) => <Tag color={statusColors[value]}>{value}</Tag>
                  },
                  { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatTime },
                  {
                    title: '操作',
                    fixed: 'right',
                    width: 390,
                    render: (_, row) => (
                      <Space size={2}>
                        {hasPermission('API_MANAGE') && (
                          <>
                            <Button type="link" icon={<PlayCircleOutlined />} onClick={() => setDebugApi(row)}>调试</Button>
                            <Button type="link" icon={<EditOutlined />} onClick={() => openApiModal(row)}>编辑</Button>
                            {row.latestVersionStatus === 'DRAFT' && (
                              <Button type="link" icon={<CheckCircleOutlined />} onClick={() => submitApproval(row)}>提交</Button>
                            )}
                          </>
                        )}
                        <Button type="link" icon={<HistoryOutlined />} onClick={() => setVersionApi(row)}>版本</Button>
                        {hasPermission('API_APPROVE') && row.status === 'PUBLISHED' && (
                          <Button type="link" danger icon={<StopOutlined />} onClick={() => updateStatus(row)}>下线</Button>
                        )}
                      </Space>
                    )
                  }
                ]}
                locale={{ emptyText: '还没有 API，请先登记数据集并创建 API' }}
              />
            </Card>
          )}

          {activeView === 'datasets' && (
            <Card size="small" className="work-card">
              <Table<DatasetRecord>
                rowKey="id"
                loading={loading}
                dataSource={datasets}
                pagination={{ pageSize: 10, showSizeChanger: false }}
                size="middle"
                columns={[
                  {
                    title: '数据集',
                    dataIndex: 'name',
                    render: (value, row) => (
                      <div className="primary-cell">
                        <strong>{value}</strong>
                        <span>{row.description || '暂无描述'}</span>
                      </div>
                    )
                  },
                  { title: '引擎', dataIndex: 'sourceType', width: 110, render: (value) => <Tag>{value}</Tag> },
                  { title: '连接', dataIndex: 'sourceName' },
                  { title: '物理表', dataIndex: 'tableName', render: (value) => <Typography.Text code>{value}</Typography.Text> },
                  { title: '模式', dataIndex: 'connectionMode', width: 110 },
                  { title: '负责人', dataIndex: 'owner', width: 120, render: (value) => value || '-' },
                  { title: '状态', dataIndex: 'status', width: 100, render: (value) => <Tag color={statusColors[value]}>{value}</Tag> },
                  { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatTime },
                  {
                    title: '操作',
                    width: 120,
                    render: (_, row) => (
                      <Button
                        type="link"
                        icon={<SafetyCertificateOutlined />}
                        onClick={() => setPolicyDataset(row)}
                      >
                        访问策略
                      </Button>
                    )
                  }
                ]}
                locale={{ emptyText: '还没有登记可服务的数据集' }}
              />
            </Card>
          )}

          {activeView === 'dataSources' && (
            <Card size="small" className="work-card">
              <DataSourceManagement
                dataSources={dataSources}
                loading={loading}
                canManage={hasPermission('DATASOURCE_MANAGE')}
                onChanged={load}
              />
            </Card>
          )}

          {activeView === 'gateway' && (
            <Card size="small" className="work-card gateway-card">
              <ApplicationManagement
                applications={applications}
                loading={loading}
                canManage={hasPermission('APPLICATION_MANAGE')}
                onChanged={load}
              />
              {hasPermission('SUBSCRIPTION_READ') && (
                <SubscriptionManagement
                  applications={applications}
                  apis={apis}
                  canManage={hasPermission('SUBSCRIPTION_MANAGE')}
                  canApprove={hasPermission('SUBSCRIPTION_APPROVE')}
                  onChanged={load}
                />
              )}
            </Card>
          )}

          {activeView === 'portal' && (
            <Card size="small" className="work-card">
              <DeveloperPortal applications={applications} />
            </Card>
          )}

          {activeView === 'runtime' && (
            <Card size="small" className="work-card">
              <RuntimeGovernance
                apis={apis}
                canManage={hasPermission('API_MANAGE')}
              />
            </Card>
          )}

          {activeView === 'governance' && hasPermission('GOVERNANCE_READ') && (
            <Card size="small" className="work-card">
              <GovernanceCenter
                canManageChannels={hasPermission('GOVERNANCE_MANAGE')}
                canHandleApprovals={hasPermission('CHANGE_APPROVAL_HANDLE')}
              />
            </Card>
          )}

          {activeView === 'logs' && (
            <Card size="small" className="work-card">
              <Table<CallLogRecord>
                rowKey="id"
                loading={loading}
                dataSource={callLogs}
                pagination={{ pageSize: 15, showSizeChanger: false }}
                size="small"
                scroll={{ x: 1100 }}
                columns={[
                  { title: '时间', dataIndex: 'occurredAt', width: 170, render: formatTime },
                  {
                    title: '类型',
                    dataIndex: 'testCall',
                    width: 90,
                    render: (value) => <Tag color={value ? 'blue' : 'purple'}>{value ? '调试' : '开放调用'}</Tag>
                  },
                  { title: 'API 路径', dataIndex: 'apiPath', width: 220, render: (value) => <Typography.Text code>{value}</Typography.Text> },
                  { title: '应用', dataIndex: 'appKey', width: 130, render: (value) => value || '-' },
                  {
                    title: '结果',
                    dataIndex: 'statusCode',
                    width: 90,
                    render: (value) => <Tag color={value < 400 ? 'success' : 'error'}>{value}</Tag>
                  },
                  { title: '耗时', dataIndex: 'elapsedMs', width: 90, render: (value) => `${value} ms` },
                  { title: '行数', dataIndex: 'rowCount', width: 80, render: (value) => value ?? '-' },
                  { title: '客户端', dataIndex: 'clientIp', width: 130, render: (value) => value || '-' },
                  { title: '请求编号', dataIndex: 'requestId', width: 180, ellipsis: true },
                  {
                    title: 'Trace ID',
                    dataIndex: 'traceId',
                    width: 180,
                    ellipsis: true,
                    render: (value) => value
                      ? <Typography.Text copyable={{ text: value }}>{value.slice(0, 12)}</Typography.Text>
                      : '-'
                  },
                  { title: '异常', dataIndex: 'errorMessage', ellipsis: true, render: (value) => value || '-' }
                ]}
                locale={{ emptyText: '暂无调用记录' }}
              />
            </Card>
          )}

          {activeView === 'users' && hasPermission('USER_READ') && (
            <Card size="small" className="work-card">
              <AdminUserManagement
                currentUser={user}
                canManage={hasPermission('USER_MANAGE')}
              />
            </Card>
          )}
        </Content>
      </Layout>

      <Modal
        title="登记数据集"
        open={datasetModalOpen}
        onCancel={() => setDatasetModalOpen(false)}
        onOk={() => datasetForm.submit()}
        okText="登记"
        destroyOnHidden
      >
        <Form
          form={datasetForm}
          layout="vertical"
          initialValues={{ connectionId: 0 }}
          onFinish={submitDataset}
        >
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]}><Input /></Form.Item>
          <Form.Item name="description" label="描述"><Input placeholder="说明数据口径和使用场景" /></Form.Item>
          <Form.Item name="connectionId" label="数据连接" rules={[{ required: true, message: '请选择数据连接' }]}>
            <Select
              options={[
                { value: 0, label: '平台内置 MySQL' },
                ...dataSources
                  .filter((source) => source.status === 'ACTIVE')
                  .map((source) => ({
                    value: source.id,
                    label: `${source.name} · ${source.engineType} · ${source.environment}`
                  }))
              ]}
            />
          </Form.Item>
          <Form.Item name="tableName" label="物理表" rules={[{ required: true, message: '请输入物理表名' }]}>
            <Input placeholder="例如 dws_order_day_summary" />
          </Form.Item>
          <Form.Item name="owner" label="负责人"><Input /></Form.Item>
        </Form>
      </Modal>

      <DatasetPolicyDrawer
        dataset={policyDataset}
        canManage={hasPermission('DATASET_MANAGE')}
        onClose={() => setPolicyDataset(null)}
      />

      <Modal
        title={editingApi ? `编辑数据 API · v${editingApi.version + 1}` : '新建数据 API'}
        open={apiModalOpen}
        onCancel={() => {
          setApiModalOpen(false);
          setEditingApi(null);
        }}
        onOk={() => apiForm.submit()}
        okText="保存草稿"
        width={860}
        destroyOnHidden
      >
        <Form form={apiForm} layout="vertical" onFinish={submitApi}>
          <div className="form-grid">
            <Form.Item name="datasetId" label="数据集" rules={[{ required: true, message: '请选择数据集' }]}>
              <Select options={datasets.map((dataset) => ({ value: dataset.id, label: dataset.name }))} />
            </Form.Item>
            <Form.Item name="name" label="API 名称" rules={[{ required: true, message: '请输入名称' }]}><Input /></Form.Item>
          </div>
          <Form.Item name="description" label="描述"><Input placeholder="说明接口用途和数据口径" /></Form.Item>
          <div className="form-grid path-grid">
            <Form.Item name="method" label="方法" rules={[{ required: true }]}>
              <Select options={['GET', 'POST'].map((value) => ({ value, label: value }))} />
            </Form.Item>
            <Form.Item name="path" label="开放路径" rules={[{ required: true, message: '请输入路径' }]}>
              <Input addonBefore="/openapi" placeholder="/orders/summary" />
            </Form.Item>
          </div>
          <Form.Item
            name="querySql"
            label="查询 SQL"
            extra="仅允许 SELECT；使用 :参数名 绑定条件；请勿编写 LIMIT，平台统一控制分页。"
            rules={[{ required: true, message: '请输入查询 SQL' }]}
          >
            <TextArea
              className="sql-editor"
              autoSize={{ minRows: 5, maxRows: 10 }}
              placeholder={'SELECT id, api_path, elapsed_ms\nFROM data_service_call_log\nWHERE api_path = :apiPath'}
              spellCheck={false}
            />
          </Form.Item>

          <div className="form-section-title">
            <Typography.Text strong>参数规范</Typography.Text>
            <Typography.Text type="secondary">SQL 中使用的命名参数必须在这里定义</Typography.Text>
          </div>
          <Form.List name="parameters">
            {(fields, { add, remove }) => (
              <div className="parameter-definition-list">
                {fields.map((field) => (
                  <div className="parameter-definition" key={field.key}>
                    <Form.Item {...field} name={[field.name, 'name']} rules={[{ required: true, message: '参数名必填' }]}>
                      <Input placeholder="参数名" />
                    </Form.Item>
                    <Form.Item {...field} name={[field.name, 'location']} initialValue="QUERY">
                      <Select options={['QUERY', 'HEADER', 'BODY'].map((value) => ({ value, label: value }))} />
                    </Form.Item>
                    <Form.Item {...field} name={[field.name, 'type']} initialValue="STRING">
                      <Select options={['STRING', 'INTEGER', 'LONG', 'DECIMAL', 'BOOLEAN', 'DATE', 'DATETIME'].map((value) => ({ value, label: value }))} />
                    </Form.Item>
                    <Form.Item {...field} name={[field.name, 'defaultValue']}><Input placeholder="默认值" /></Form.Item>
                    <Form.Item {...field} name={[field.name, 'required']} valuePropName="checked">
                      <Checkbox>必填</Checkbox>
                    </Form.Item>
                    <Button type="text" danger icon={<MinusCircleOutlined />} title="删除参数" onClick={() => remove(field.name)} />
                  </div>
                ))}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ location: 'QUERY', type: 'STRING', required: false })}>
                  添加参数
                </Button>
              </div>
            )}
          </Form.List>

          <div className="form-grid governance-grid">
            <Form.Item name="maxPageSize" label="最大分页条数" rules={[{ required: true }]}>
              <InputNumber min={1} max={500} />
            </Form.Item>
            <Form.Item name="cacheTtlSeconds" label="缓存时间（秒）">
              <InputNumber min={0} max={86400} />
            </Form.Item>
          </div>
          <Form.Item
            name="changeSummary"
            label="变更说明"
            rules={editingApi ? [{ required: true, message: '请输入本次变更内容' }] : []}
          >
            <Input.TextArea
              rows={3}
              placeholder="说明修改内容、原因和可能影响"
            />
          </Form.Item>
          <Alert
            type="info"
            showIcon
            icon={<SafetyCertificateOutlined />}
            message="保存会生成不可变版本快照；提交审批前可调试，审批通过后才影响线上调用。"
          />
        </Form>
      </Modal>

      <ApiDebugger api={debugApi} onClose={() => setDebugApi(null)} onExecuted={load} />
      <ApiVersionDrawer
        api={versionApi}
        user={user}
        canApprove={hasPermission('API_APPROVE')}
        onClose={() => setVersionApi(null)}
        onChanged={load}
      />
    </Layout>
  );
}
