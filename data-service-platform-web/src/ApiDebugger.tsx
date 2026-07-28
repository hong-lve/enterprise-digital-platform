import { PlayCircleOutlined } from '@ant-design/icons';
import { Button, Input, InputNumber, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { useEffect, useMemo, useState } from 'react';
import {
  errorMessage,
  testApi,
  type DataApiRecord,
  type ExecutionResult
} from './api';

interface ApiDebuggerProps {
  api: DataApiRecord | null;
  onClose: () => void;
  onExecuted: () => void;
}

export default function ApiDebugger({ api, onClose, onExecuted }: ApiDebuggerProps) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [page, setPage] = useState(1);
  const [pageSize, setPageSize] = useState(20);
  const [executing, setExecuting] = useState(false);
  const [result, setResult] = useState<ExecutionResult | null>(null);

  useEffect(() => {
    const defaults: Record<string, string> = {};
    api?.parameters.forEach((parameter) => {
      defaults[parameter.name] = parameter.defaultValue || '';
    });
    setValues(defaults);
    setPage(1);
    setPageSize(Math.min(20, api?.maxPageSize || 20));
    setResult(null);
  }, [api]);

  const resultColumns = useMemo(() => {
    if (!result?.rows.length) {
      return [];
    }
    return Object.keys(result.rows[0]).map((key) => ({
      title: key,
      dataIndex: key,
      key,
      ellipsis: true,
      render: (value: unknown) => {
        if (value === null || value === undefined) {
          return <Typography.Text type="secondary">NULL</Typography.Text>;
        }
        return typeof value === 'object' ? JSON.stringify(value) : String(value);
      }
    }));
  }, [result]);

  const execute = async () => {
    if (!api) {
      return;
    }
    const missing = api.parameters.find((parameter) => parameter.required && !values[parameter.name]?.trim());
    if (missing) {
      message.warning(`请填写必填参数：${missing.name}`);
      return;
    }
    setExecuting(true);
    try {
      const parameters = Object.fromEntries(
        Object.entries(values).filter(([, value]) => value !== '')
      );
      setResult(await testApi(api.id, { parameters, page, pageSize }));
      onExecuted();
    } catch (error) {
      message.error(errorMessage(error, '接口执行失败'));
    } finally {
      setExecuting(false);
    }
  };

  return (
    <Modal
      title={api ? `接口调试：${api.name}` : '接口调试'}
      open={Boolean(api)}
      onCancel={onClose}
      width={980}
      footer={[
        <Button key="close" onClick={onClose}>关闭</Button>,
        <Button key="run" type="primary" icon={<PlayCircleOutlined />} loading={executing} onClick={execute}>
          执行查询
        </Button>
      ]}
      destroyOnHidden
    >
      {api && (
        <div className="debug-panel">
          <div className="request-line">
            <Tag color={api.method === 'GET' ? 'blue' : 'green'}>{api.method}</Tag>
            <Input value={`/openapi${api.path}`} readOnly />
            <Tag color={api.status === 'PUBLISHED' ? 'success' : 'default'}>{api.status}</Tag>
          </div>

          <div>
            <Typography.Text strong>请求参数</Typography.Text>
            {api.parameters.length ? (
              <Table
                rowKey="name"
                size="small"
                pagination={false}
                className="parameter-table"
                dataSource={api.parameters}
                columns={[
                  { title: '位置', dataIndex: 'location', width: 90, render: (value) => <Tag>{value}</Tag> },
                  {
                    title: '参数',
                    dataIndex: 'name',
                    width: 180,
                    render: (value, row) => <span>{value}{row.required && <span className="required-mark"> *</span>}</span>
                  },
                  { title: '类型', dataIndex: 'type', width: 100 },
                  {
                    title: '参数值',
                    render: (_, row) => (
                      <Input
                        value={values[row.name] || ''}
                        placeholder={row.description || row.defaultValue || `请输入 ${row.name}`}
                        onChange={(event) => setValues((current) => ({ ...current, [row.name]: event.target.value }))}
                      />
                    )
                  }
                ]}
              />
            ) : (
              <div className="inline-empty">此 API 未定义业务参数</div>
            )}
          </div>

          <Space align="center">
            <Typography.Text strong>分页</Typography.Text>
            <Typography.Text type="secondary">页码</Typography.Text>
            <InputNumber min={1} value={page} onChange={(value) => setPage(value || 1)} />
            <Typography.Text type="secondary">每页</Typography.Text>
            <InputNumber
              min={1}
              max={api.maxPageSize}
              value={pageSize}
              onChange={(value) => setPageSize(value || 20)}
            />
            <Typography.Text type="secondary">最大 {api.maxPageSize} 条</Typography.Text>
          </Space>

          <div className="section-heading result-heading">
            <Typography.Text strong>执行结果</Typography.Text>
            {result && (
              <Space>
                <Tag color="success">成功</Tag>
                <Tag>{result.elapsedMs} ms</Tag>
                <Tag>{result.rowCount} 行</Tag>
                <Tag color={result.cacheStatus === 'HIT' ? 'blue' : result.degraded ? 'warning' : 'default'}>
                  {result.cacheStatus}
                </Tag>
                <Typography.Text copyable={{ text: result.requestId }} type="secondary">
                  {result.requestId.slice(0, 12)}
                </Typography.Text>
                {result.traceId && (
                  <Typography.Text copyable={{ text: result.traceId }} type="secondary">
                    Trace {result.traceId.slice(0, 12)}
                  </Typography.Text>
                )}
              </Space>
            )}
          </div>
          {result ? (
            <Table
              rowKey={(row) => String(row.id ?? row.request_id ?? JSON.stringify(row))}
              size="small"
              pagination={false}
              scroll={{ x: 'max-content', y: 320 }}
              columns={resultColumns}
              dataSource={result.rows}
              locale={{ emptyText: '查询成功，没有返回数据' }}
            />
          ) : (
            <div className="result-empty">填写参数后执行查询，结果和审计编号将在这里显示</div>
          )}
        </div>
      )}
    </Modal>
  );
}
