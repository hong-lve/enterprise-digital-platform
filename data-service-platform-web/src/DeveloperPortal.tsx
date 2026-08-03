import {
  BookOutlined,
  CopyOutlined,
  DownloadOutlined
} from '@ant-design/icons';
import {
  Button,
  Descriptions,
  Drawer,
  Select,
  Space,
  Table,
  Tabs,
  Tag,
  Typography,
  message
} from 'antd';
import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  errorMessage,
  getDeveloperPortalOpenApi,
  listApiSubscriptions,
  listDeveloperPortalApis,
  type ApiParameter,
  type ApiSubscriptionRecord,
  type ApplicationRecord,
  type DataApiRecord
} from './api';

interface Props {
  applications: ApplicationRecord[];
}

type SampleLanguage = 'Java' | 'Python' | 'JavaScript';

export default function DeveloperPortal({ applications }: Props) {
  const [apis, setApis] = useState<DataApiRecord[]>([]);
  const [subscriptions, setSubscriptions] = useState<ApiSubscriptionRecord[]>([]);
  const [selectedAppId, setSelectedAppId] = useState<number>();
  const [documentApi, setDocumentApi] = useState<DataApiRecord | null>(null);
  const [openApi, setOpenApi] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    try {
      const [apiRows, subscriptionRows] = await Promise.all([
        listDeveloperPortalApis(),
        listApiSubscriptions()
      ]);
      setApis(apiRows);
      setSubscriptions(subscriptionRows);
    } catch (error) {
      message.error(errorMessage(error, '加载开发者门户失败'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  useEffect(() => {
    if (selectedAppId === undefined && applications.length) {
      setSelectedAppId(applications[0].id);
    }
  }, [applications, selectedAppId]);

  const selectedApp = applications.find((item) => item.id === selectedAppId);
  const subscriptionByApi = useMemo(() => new Map(
    subscriptions
      .filter((item) => item.appId === selectedAppId)
      .map((item) => [item.apiId, item])
  ), [subscriptions, selectedAppId]);

  const openDocumentation = async (api: DataApiRecord) => {
    setDocumentApi(api);
    try {
      setOpenApi(await getDeveloperPortalOpenApi(api.id));
    } catch (error) {
      message.error(errorMessage(error, '加载 OpenAPI 文档失败'));
    }
  };

  const download = (name: string, content: string, type = 'text/plain') => {
    const url = URL.createObjectURL(new Blob([content], { type }));
    const anchor = window.document.createElement('a');
    anchor.href = url;
    anchor.download = name;
    anchor.click();
    URL.revokeObjectURL(url);
  };

  const samples = documentApi && selectedApp
    ? buildSamples(documentApi, selectedApp.appKey, selectedApp.secretVersion)
    : null;

  return (
    <>
      <div className="portal-toolbar">
        <div>
          <Typography.Title level={5}>API 目录</Typography.Title>
          <Typography.Text type="secondary">已发布接口、订阅状态和调用契约</Typography.Text>
        </div>
        <Select
          value={selectedAppId}
          placeholder="选择调用应用"
          style={{ width: 260 }}
          options={applications.map((item) => ({ value: item.id, label: `${item.name} (${item.appKey})` }))}
          onChange={setSelectedAppId}
        />
      </div>
      <Table<DataApiRecord>
        rowKey="id"
        loading={loading}
        dataSource={apis}
        pagination={{ pageSize: 12 }}
        columns={[
          {
            title: 'API',
            dataIndex: 'name',
            render: (value, row) => (
              <div className="primary-cell">
                <strong>{value}</strong>
                <span>{row.description || '暂无描述'}</span>
              </div>
            )
          },
          {
            title: '访问路径',
            dataIndex: 'path',
            width: 300,
            render: (value, row) => (
              <Space>
                <Tag color="blue">{row.method}</Tag>
                <Typography.Text code>/openapi{value}</Typography.Text>
              </Space>
            )
          },
          {
            title: '版本',
            dataIndex: 'publishedVersion',
            width: 90,
            render: (value) => `v${value}`
          },
          {
            title: '订阅状态',
            width: 130,
            render: (_, row) => {
              const subscription = subscriptionByApi.get(row.id);
              const status = subscription?.status || 'NOT_SUBSCRIBED';
              return <Tag color={status === 'APPROVED' ? 'success' : status === 'PENDING' ? 'processing' : 'default'}>{status}</Tag>;
            }
          },
          {
            title: '今日额度',
            width: 180,
            render: (_, row) => {
              const subscription = subscriptionByApi.get(row.id);
              return subscription?.status === 'APPROVED'
                ? `${subscription.dailyUsed.toLocaleString()} / ${subscription.dailyLimit.toLocaleString()}`
                : '-';
            }
          },
          {
            title: '操作',
            width: 110,
            render: (_, row) => (
              <Button type="link" icon={<BookOutlined />} onClick={() => openDocumentation(row)}>
                接口文档
              </Button>
            )
          }
        ]}
      />

      <Drawer
        title={documentApi ? `${documentApi.name} · v${documentApi.publishedVersion}` : '接口文档'}
        open={Boolean(documentApi)}
        onClose={() => {
          setDocumentApi(null);
          setOpenApi(null);
        }}
        width={820}
        extra={openApi && documentApi ? (
          <Button
            icon={<DownloadOutlined />}
            onClick={() => download(
              `${documentApi.name}-openapi.json`,
              JSON.stringify(openApi, null, 2),
              'application/json'
            )}
          >
            OpenAPI
          </Button>
        ) : null}
      >
        {documentApi && (
          <>
            <Descriptions size="small" column={2} bordered>
              <Descriptions.Item label="请求方法">{documentApi.method}</Descriptions.Item>
              <Descriptions.Item label="发布版本">v{documentApi.publishedVersion}</Descriptions.Item>
              <Descriptions.Item label="接口地址" span={2}>/openapi{documentApi.path}</Descriptions.Item>
              <Descriptions.Item label="最大分页">{documentApi.maxPageSize}</Descriptions.Item>
              <Descriptions.Item label="缓存时间">{documentApi.cacheTtlSeconds || 0} 秒</Descriptions.Item>
            </Descriptions>
            <Tabs items={[
              {
                key: 'parameters',
                label: '参数',
                children: (
                  <Table<ApiParameter>
                    rowKey={(row) => `${row.location}-${row.name}`}
                    dataSource={documentApi.parameters}
                    pagination={false}
                    size="small"
                    columns={[
                      { title: '参数名', dataIndex: 'name' },
                      { title: '位置', dataIndex: 'location', width: 90 },
                      { title: '类型', dataIndex: 'type', width: 100 },
                      { title: '必填', dataIndex: 'required', width: 70, render: (value) => value ? '是' : '否' },
                      { title: '说明', dataIndex: 'description', render: (value) => value || '-' }
                    ]}
                  />
                )
              },
              ...(['Java', 'Python', 'JavaScript'] as SampleLanguage[]).map((language) => ({
                key: language,
                label: language,
                children: samples ? (
                  <div className="code-sample">
                    <div>
                      <Typography.Text type="secondary">HMAC-SHA256 签名调用示例</Typography.Text>
                      <Space>
                        <Button
                          size="small"
                          icon={<CopyOutlined />}
                          onClick={() => navigator.clipboard.writeText(samples[language])}
                        >
                          复制
                        </Button>
                        <Button
                          size="small"
                          icon={<DownloadOutlined />}
                          onClick={() => download(`${documentApi.name}-${language.toLowerCase()}.txt`, samples[language])}
                        >
                          下载
                        </Button>
                      </Space>
                    </div>
                    <pre><code>{samples[language]}</code></pre>
                  </div>
                ) : null
              })),
              {
                key: 'openapi',
                label: 'OpenAPI',
                children: (
                  <div className="code-sample">
                    <pre><code>{openApi ? JSON.stringify(openApi, null, 2) : '加载中...'}</code></pre>
                  </div>
                )
              }
            ]} />
          </>
        )}
      </Drawer>
    </>
  );
}

function buildSamples(api: DataApiRecord, appKey: string, secretVersion: number): Record<SampleLanguage, string> {
  const path = `/openapi${api.path}`;
  const method = api.method.toUpperCase();
  return {
    Java: `String appKey = "${appKey}";
String appSecret = System.getenv("DATA_SERVICE_APP_SECRET");
String timestamp = String.valueOf(System.currentTimeMillis());
String nonce = UUID.randomUUID().toString().replace("-", "");
String bodyHash = sha256Hex(new byte[0]);
String canonical = String.join("\\n", "${method}", "${path}", "", timestamp, nonce, bodyHash);
String signature = hmacSha256Hex(appSecret, canonical);

HttpRequest request = HttpRequest.newBuilder(URI.create(BASE_URL + "${path}"))
    .header("X-App-Key", appKey)
    .header("X-Timestamp", timestamp)
    .header("X-Nonce", nonce)
    .header("X-Signature", signature)
    .header("X-Secret-Version", "${secretVersion}")
    .method("${method}", HttpRequest.BodyPublishers.noBody())
    .build();`,
    Python: `import hashlib, hmac, os, time, uuid, requests

app_key = "${appKey}"
secret = os.environ["DATA_SERVICE_APP_SECRET"]
timestamp = str(int(time.time() * 1000))
nonce = uuid.uuid4().hex
body_hash = hashlib.sha256(b"").hexdigest()
canonical = "\\n".join(["${method}", "${path}", "", timestamp, nonce, body_hash])
signature = hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()

response = requests.${method.toLowerCase()}(
    BASE_URL + "${path}",
    headers={"X-App-Key": app_key, "X-Timestamp": timestamp,
             "X-Nonce": nonce, "X-Signature": signature,
             "X-Secret-Version": "${secretVersion}"}
)`,
    JavaScript: `import crypto from 'node:crypto';

const appKey = '${appKey}';
const secret = process.env.DATA_SERVICE_APP_SECRET;
const timestamp = String(Date.now());
const nonce = crypto.randomUUID().replaceAll('-', '');
const bodyHash = crypto.createHash('sha256').update('').digest('hex');
const canonical = ['${method}', '${path}', '', timestamp, nonce, bodyHash].join('\\n');
const signature = crypto.createHmac('sha256', secret).update(canonical).digest('hex');

const response = await fetch(BASE_URL + '${path}', {
  method: '${method}',
  headers: { 'X-App-Key': appKey, 'X-Timestamp': timestamp,
             'X-Nonce': nonce, 'X-Signature': signature,
             'X-Secret-Version': '${secretVersion}' }
});`
  };
}
