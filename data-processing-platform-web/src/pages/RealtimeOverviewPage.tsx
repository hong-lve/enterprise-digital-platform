import { Line } from '@ant-design/plots';
import { DisconnectOutlined, ThunderboltOutlined, WarningOutlined } from '@ant-design/icons';
import { Alert, Button, Card, Col, List, Popconfirm, Row, Space, Tag, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { getAlertTrend, type AlertTrendPoint } from '../api/alertHistory';
import { cancelOrphanedFlinkJob, listOrphanedFlinkJobs, type OrphanedFlinkJob } from '../api/flinkCluster';
import { deleteOrphanedConnector, listOrphanedConnectors, type OrphanedConnector } from '../api/kafkaConnect';
import { getRealtimeOverview, type RealtimeOverview } from '../api/overview';
import { useAuthStore } from '../store/auth';

function localDateKey(date: Date): string {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

// "hour" comes back as "YYYY-MM-DD HH:00:00" - only the hour matters for a
// same-day point (no point showing day/seconds on every tick), but a point
// from an earlier day needs the date too or two different days' "07" points
// would look identical.
function formatHourLabel(hour: string, today: string): string {
  const [datePart, timePart] = hour.split(' ');
  const hourPart = timePart.slice(0, 2);
  return datePart === today ? hourPart : `${hourPart}(${datePart})`;
}

export function RealtimeOverviewPage() {
  const navigate = useNavigate();
  const can = useAuthStore((state) => state.hasPermission);
  const [overview, setOverview] = useState<RealtimeOverview | null>(null);
  const [trend, setTrend] = useState<AlertTrendPoint[]>([]);
  const [orphanedJobs, setOrphanedJobs] = useState<OrphanedFlinkJob[] | null>(null);
  const [orphanedLoading, setOrphanedLoading] = useState(false);
  const [cancelBusyId, setCancelBusyId] = useState<string | null>(null);
  const [orphanedConnectors, setOrphanedConnectors] = useState<OrphanedConnector[] | null>(null);
  const [orphanedConnectorsLoading, setOrphanedConnectorsLoading] = useState(false);
  const [deleteBusyName, setDeleteBusyName] = useState<string | null>(null);

  const loadOrphanedJobs = () => {
    setOrphanedLoading(true);
    listOrphanedFlinkJobs()
      .then(setOrphanedJobs)
      .finally(() => setOrphanedLoading(false));
  };

  const loadOrphanedConnectors = () => {
    setOrphanedConnectorsLoading(true);
    listOrphanedConnectors()
      .then(setOrphanedConnectors)
      .finally(() => setOrphanedConnectorsLoading(false));
  };

  useEffect(() => {
    getRealtimeOverview().then(setOverview);
    getAlertTrend().then(setTrend);
    loadOrphanedJobs();
    loadOrphanedConnectors();
  }, []);

  const handleCancelOrphan = (jobId: string) => {
    setCancelBusyId(jobId);
    cancelOrphanedFlinkJob(jobId)
      .then(() => {
        message.success('已取消，稍后集群会释放对应的 slot');
        loadOrphanedJobs();
      })
      .finally(() => setCancelBusyId(null));
  };

  const handleDeleteOrphanConnector = (connectorName: string) => {
    setDeleteBusyName(connectorName);
    deleteOrphanedConnector(connectorName)
      .then(() => {
        message.success('已删除，Kafka Connect 会停止抓取该连接器的变更');
        loadOrphanedConnectors();
      })
      .finally(() => setDeleteBusyName(null));
  };

  const today = localDateKey(new Date());
  const trendForChart = trend.map((point) => ({ ...point, hour: formatHourLabel(point.hour, today) }));

  const alertingFlinkJobs = overview?.alertingFlinkJobs || [];
  const alertingSqlJobs = overview?.alertingSqlJobs || [];
  const alertingCdcSources = overview?.alertingCdcSources || [];
  const alertingCount = alertingFlinkJobs.length + alertingSqlJobs.length + alertingCdcSources.length;

  return (
    <div className="page-stack">
      <Typography.Title level={3}><ThunderboltOutlined /> 实时计算平台</Typography.Title>
      <Typography.Paragraph type="secondary">
        承接 CDC 采集、Kafka 缓冲、Flink 流作业和实时存储的独立系统，与离线任务流（数据平台）分开部署、分开演进。
      </Typography.Paragraph>

      {alertingCount > 0 && (
        <Alert
          type="error"
          showIcon
          icon={<WarningOutlined />}
          message={`当前有 ${alertingCount} 项持续失败，尚未恢复`}
          description={
            <Row gutter={[16, 16]}>
              {alertingFlinkJobs.length > 0 && (
                <Col xs={24} lg={8}>
                  <Typography.Text strong>Flink 流作业（{alertingFlinkJobs.length}）</Typography.Text>
                  <List
                    size="small"
                    dataSource={alertingFlinkJobs}
                    renderItem={(item) => (
                      <List.Item onClick={() => navigate('/realtime/flink-jobs')} style={{ cursor: 'pointer' }}>
                        <List.Item.Meta
                          title={item.name}
                          description={`负责人：${item.owner || '未指定'}${item.message ? ' · ' + item.message : ''}`}
                        />
                      </List.Item>
                    )}
                  />
                </Col>
              )}
              {alertingSqlJobs.length > 0 && (
                <Col xs={24} lg={8}>
                  <Typography.Text strong>SQL 流作业（{alertingSqlJobs.length}）</Typography.Text>
                  <List
                    size="small"
                    dataSource={alertingSqlJobs}
                    renderItem={(item) => (
                      <List.Item onClick={() => navigate('/realtime/sql-jobs')} style={{ cursor: 'pointer' }}>
                        <List.Item.Meta
                          title={item.name}
                          description={`负责人：${item.owner || '未指定'}${item.message ? ' · ' + item.message : ''}`}
                        />
                      </List.Item>
                    )}
                  />
                </Col>
              )}
              {alertingCdcSources.length > 0 && (
                <Col xs={24} lg={8}>
                  <Typography.Text strong>CDC 数据源（{alertingCdcSources.length}）</Typography.Text>
                  <List
                    size="small"
                    dataSource={alertingCdcSources}
                    renderItem={(item) => (
                      <List.Item onClick={() => navigate('/realtime/cdc-sources')} style={{ cursor: 'pointer' }}>
                        <List.Item.Meta
                          title={item.name}
                          description={`负责人：${item.owner || '未指定'}${item.message ? ' · ' + item.message : ''}`}
                        />
                      </List.Item>
                    )}
                  />
                </Col>
              )}
            </Row>
          }
        />
      )}

      <Card
        title={<span><DisconnectOutlined /> Flink 集群孤儿作业</span>}
        extra={<Button size="small" loading={orphanedLoading} onClick={loadOrphanedJobs}>刷新</Button>}
      >
        {orphanedJobs === null ? null : orphanedJobs.length > 0 ? (
          <>
            <Typography.Paragraph type="secondary">
              以下作业在 Flink 集群上仍在运行，但在本平台的作业记录里找不到对应项（可能是记录被删除时集群不可达，或有人绕过本平台直接提交）——它们会一直占用 slot，直到被手动取消。
            </Typography.Paragraph>
            <List
              dataSource={orphanedJobs}
              renderItem={(job) => (
                <List.Item
                  actions={
                    can('realtime:overview:cancel-orphan')
                      ? [
                          <Popconfirm
                            key="cancel"
                            title="确定取消这个孤儿作业？"
                            description="不会创建保存点，取消后无法恢复。"
                            onConfirm={() => handleCancelOrphan(job.jobId)}
                          >
                            <Button size="small" danger loading={cancelBusyId === job.jobId}>取消</Button>
                          </Popconfirm>
                        ]
                      : []
                  }
                >
                  <List.Item.Meta
                    title={<Space>{job.name}<Tag color="orange">{job.state}</Tag></Space>}
                    description={`Job ID：${job.jobId} · 启动时间：${new Date(job.startTime).toLocaleString()}`}
                  />
                </List.Item>
              )}
            />
          </>
        ) : (
          <Alert type="success" showIcon message="未发现孤儿作业：集群上所有运行中的作业都能在平台记录里找到对应项" />
        )}
      </Card>

      <Card
        title={<span><DisconnectOutlined /> Kafka Connect 孤儿连接器</span>}
        extra={<Button size="small" loading={orphanedConnectorsLoading} onClick={loadOrphanedConnectors}>刷新</Button>}
      >
        {orphanedConnectors === null ? null : orphanedConnectors.length > 0 ? (
          <>
            <Typography.Paragraph type="secondary">
              以下 connector 在 Kafka Connect 上仍在运行，但在本平台的 CDC 数据源记录里找不到对应项——它们会一直抓取变更并写入 Kafka，直到被手动删除。
            </Typography.Paragraph>
            <List
              dataSource={orphanedConnectors}
              renderItem={(connector) => (
                <List.Item
                  actions={
                    can('realtime:overview:delete-orphan-connector')
                      ? [
                          <Popconfirm
                            key="delete"
                            title="确定删除这个孤儿 connector？"
                            description="会停止抓取变更，且无法恢复。"
                            onConfirm={() => handleDeleteOrphanConnector(connector.connectorName)}
                          >
                            <Button size="small" danger loading={deleteBusyName === connector.connectorName}>删除</Button>
                          </Popconfirm>
                        ]
                      : []
                  }
                >
                  <List.Item.Meta
                    title={<Space>{connector.connectorName}<Tag color="orange">{connector.state}</Tag></Space>}
                    description={connector.message || undefined}
                  />
                </List.Item>
              )}
            />
          </>
        ) : (
          <Alert type="success" showIcon message="未发现孤儿 connector：Kafka Connect 上所有连接器都能在平台记录里找到对应项" />
        )}
      </Card>

      <Card title="最近24小时告警趋势">
        <Line data={trendForChart} xField="hour" yField="count" height={240} />
      </Card>
    </div>
  );
}
