import {
  EditOutlined,
  MinusCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined
} from '@ant-design/icons';
import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Switch,
  Table,
  Tag,
  Typography,
  message
} from 'antd';
import { useCallback, useEffect, useState } from 'react';
import {
  errorMessage,
  listContractTestCases,
  listContractTestRuns,
  runContractTestCase,
  saveContractTestCase,
  type ApiVersionRecord,
  type ContractAssertion,
  type ContractTestCase,
  type ContractTestRun,
  type DataApiRecord
} from './api';

interface Props {
  api: DataApiRecord;
  versions: ApiVersionRecord[];
  canManage: boolean;
}

interface CaseForm {
  name: string;
  enabled: boolean;
  parameters: Record<string, string | number | boolean | null | undefined>;
  page: number;
  pageSize: number;
  assertions: ContractAssertion[];
}

const assertionOptions = [
  { value: 'FIELD_EXISTS', label: '字段存在' },
  { value: 'FIELD_NOT_NULL', label: '字段非空' },
  { value: 'FIELD_TYPE', label: '字段类型' },
  { value: 'FIELD_EQUALS', label: '字段值相等' },
  { value: 'ROW_COUNT_MIN', label: '最少返回行数' },
  { value: 'ROW_COUNT_MAX', label: '最多返回行数' },
  { value: 'MAX_ELAPSED_MS', label: '最大响应时间' }
];

export default function ContractTestManagement({ api, versions, canManage }: Props) {
  const [cases, setCases] = useState<ContractTestCase[]>([]);
  const [runs, setRuns] = useState<ContractTestRun[]>([]);
  const [loading, setLoading] = useState(false);
  const [editing, setEditing] = useState<ContractTestCase | null>(null);
  const [modalOpen, setModalOpen] = useState(false);
  const [targetVersion, setTargetVersion] = useState(api.version);
  const [runningCaseId, setRunningCaseId] = useState<number>();
  const [form] = Form.useForm<CaseForm>();

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [caseRows, runRows] = await Promise.all([
        listContractTestCases(api.id),
        listContractTestRuns(api.id)
      ]);
      setCases(caseRows);
      setRuns(runRows);
    } catch (error) {
      message.error(errorMessage(error, '加载契约测试失败'));
    } finally {
      setLoading(false);
    }
  }, [api.id]);

  useEffect(() => {
    setTargetVersion(api.version);
    load();
  }, [api.version, load]);

  const openCase = (testCase?: ContractTestCase) => {
    setEditing(testCase || null);
    form.setFieldsValue(testCase ? {
      name: testCase.name,
      enabled: testCase.enabled,
      parameters: testCase.parameters as CaseForm['parameters'],
      page: testCase.page,
      pageSize: testCase.pageSize,
      assertions: testCase.assertions
    } : {
      enabled: true,
      parameters: Object.fromEntries(api.parameters.map((parameter) => [
        parameter.name,
        parameter.defaultValue || ''
      ])),
      page: 1,
      pageSize: Math.min(20, api.maxPageSize),
      assertions: [
        { type: 'MAX_ELAPSED_MS', expected: '3000' },
        { type: 'ROW_COUNT_MIN', expected: '1' }
      ]
    });
    setModalOpen(true);
  };

  const save = async (values: CaseForm) => {
    try {
      await saveContractTestCase(api.id, editing?.id, values);
      message.success(editing ? '契约测试已更新' : '契约测试已创建');
      setModalOpen(false);
      form.resetFields();
      await load();
    } catch (error) {
      message.error(errorMessage(error, '保存契约测试失败'));
    }
  };

  const run = async (testCase: ContractTestCase) => {
    setRunningCaseId(testCase.id);
    try {
      const result = await runContractTestCase(api.id, testCase.id, targetVersion);
      if (result.status === 'PASSED') {
        message.success(`${testCase.name} 测试通过`);
      } else {
        message.error(result.failureMessage || `${testCase.name} 测试失败`);
      }
      await load();
    } catch (error) {
      message.error(errorMessage(error, '执行契约测试失败'));
    } finally {
      setRunningCaseId(undefined);
    }
  };

  return (
    <section className="contract-tests">
      <div className="governance-heading">
        <div>
          <Typography.Title level={5}>自动化契约测试</Typography.Title>
          <Typography.Text type="secondary">审批发布前自动执行所有已启用用例</Typography.Text>
        </div>
        <Space>
          <Select
            value={targetVersion}
            style={{ width: 120 }}
            options={versions.map((version) => ({
              value: version.versionNo,
              label: `测试 v${version.versionNo}`
            }))}
            onChange={setTargetVersion}
          />
          {canManage && (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openCase()}>
              新建用例
            </Button>
          )}
        </Space>
      </div>
      <Table<ContractTestCase>
        rowKey="id"
        loading={loading}
        dataSource={cases}
        pagination={false}
        size="small"
        columns={[
          {
            title: '测试用例',
            dataIndex: 'name',
            render: (value, row) => (
              <div className="primary-cell">
                <strong>{value}</strong>
                <span>{row.assertions.length} 条断言 · 第 {row.page} 页 / {row.pageSize} 条</span>
              </div>
            )
          },
          {
            title: '状态',
            dataIndex: 'enabled',
            width: 90,
            render: (value) => <Tag color={value ? 'success' : 'default'}>{value ? '启用' : '停用'}</Tag>
          },
          { title: '创建人', dataIndex: 'createdBy', width: 110 },
          {
            title: '最近结果',
            width: 120,
            render: (_, row) => {
              const latest = runs.find((runRow) => runRow.caseId === row.id);
              return latest
                ? <Tag color={latest.status === 'PASSED' ? 'success' : 'error'}>{latest.status}</Tag>
                : '-';
            }
          },
          {
            title: '操作',
            width: 150,
            render: (_, row) => canManage ? (
              <Space>
                <Button type="text" title="编辑" icon={<EditOutlined />} onClick={() => openCase(row)} />
                <Button
                  type="text"
                  title="执行"
                  icon={<PlayCircleOutlined />}
                  loading={runningCaseId === row.id}
                  onClick={() => run(row)}
                />
              </Space>
            ) : '-'
          }
        ]}
        locale={{ emptyText: '尚未配置契约测试，版本将无法通过发布审批' }}
      />

      <div className="contract-run-heading">
        <Typography.Text strong>最近执行记录</Typography.Text>
      </div>
      <Table<ContractTestRun>
        rowKey="id"
        dataSource={runs.slice(0, 10)}
        pagination={false}
        size="small"
        columns={[
          { title: '时间', dataIndex: 'runAt', width: 180, render: (value) => new Date(value).toLocaleString('zh-CN', { hour12: false }) },
          { title: '版本', dataIndex: 'versionNo', width: 80, render: (value) => `v${value}` },
          { title: '结果', dataIndex: 'status', width: 100, render: (value) => <Tag color={value === 'PASSED' ? 'success' : 'error'}>{value}</Tag> },
          { title: '耗时', dataIndex: 'elapsedMs', width: 90, render: (value) => value == null ? '-' : `${value} ms` },
          { title: '行数', dataIndex: 'rowCount', width: 80, render: (value) => value ?? '-' },
          { title: '失败原因', dataIndex: 'failureMessage', ellipsis: true, render: (value) => value || '-' }
        ]}
      />

      <Modal
        title={editing ? '编辑契约测试' : '新建契约测试'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        width={780}
        destroyOnHidden
      >
        <Form form={form} layout="vertical" onFinish={save}>
          <div className="form-grid">
            <Form.Item name="name" label="用例名称" rules={[{ required: true }]}><Input /></Form.Item>
            <Form.Item name="enabled" label="启用" valuePropName="checked"><Switch /></Form.Item>
          </div>
          <div className="form-grid">
            <Form.Item name="page" label="页码" rules={[{ required: true }]}><InputNumber min={1} /></Form.Item>
            <Form.Item name="pageSize" label="每页条数" rules={[{ required: true }]}><InputNumber min={1} max={api.maxPageSize} /></Form.Item>
          </div>
          {api.parameters.length > 0 && (
            <div className="contract-parameter-grid">
              {api.parameters.map((parameter) => (
                <Form.Item
                  key={parameter.name}
                  name={['parameters', parameter.name]}
                  label={`${parameter.name} · ${parameter.type}`}
                  rules={parameter.required ? [{ required: true }] : []}
                >
                  <Input placeholder={parameter.description || parameter.defaultValue || ''} />
                </Form.Item>
              ))}
            </div>
          )}
          <Typography.Text strong>响应断言</Typography.Text>
          <Form.List name="assertions">
            {(fields, { add, remove }) => (
              <Space direction="vertical" className="assertion-list">
                {fields.map((field) => (
                  <div className="assertion-row" key={field.key}>
                    <Form.Item {...field} name={[field.name, 'type']} rules={[{ required: true }]}>
                      <Select placeholder="断言类型" options={assertionOptions} />
                    </Form.Item>
                    <Form.Item {...field} name={[field.name, 'field']}>
                      <Input placeholder="字段路径（字段断言）" />
                    </Form.Item>
                    <Form.Item {...field} name={[field.name, 'expected']}>
                      <Input placeholder="期望值" />
                    </Form.Item>
                    <Button danger type="text" icon={<MinusCircleOutlined />} onClick={() => remove(field.name)} />
                  </div>
                ))}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ type: 'FIELD_EXISTS' })}>
                  添加断言
                </Button>
              </Space>
            )}
          </Form.List>
        </Form>
      </Modal>
    </section>
  );
}
