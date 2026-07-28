import { ClusterOutlined, ReloadOutlined } from '@ant-design/icons';
import { Drawer, Empty, Space, Table, Tag, Timeline, Typography } from 'antd';
import { useEffect, useState } from 'react';
import {
  listContainerEvents,
  listContainerStatus,
  type ContainerEventRecord,
  type ContainerStatusRecord
} from '../api/containerMonitoring';

const stateColor: Record<string, string> = {
  running: 'green',
  exited: 'red',
  restarting: 'orange',
  dead: 'red',
  created: 'default'
};

const eventTypeLabel: Record<string, string> = {
  RESTART: '重启',
  CRASH: '崩溃',
  RECREATED: '容器重建'
};

const eventTypeColor: Record<string, string> = {
  RESTART: 'orange',
  CRASH: 'red',
  RECREATED: 'purple'
};

export function ContainerMonitoringPage() {
  const [containers, setContainers] = useState<ContainerStatusRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [eventsFor, setEventsFor] = useState<string | null>(null);
  const [events, setEvents] = useState<ContainerEventRecord[]>([]);
  const [eventsLoading, setEventsLoading] = useState(false);

  const load = () => {
    setLoading(true);
    listContainerStatus()
      .then(setContainers)
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const openEvents = (name: string) => {
    setEventsFor(name);
    setEventsLoading(true);
    listContainerEvents(name)
      .then(setEvents)
      .finally(() => setEventsLoading(false));
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><ClusterOutlined /> 容器监控</Typography.Title>
          <Typography.Paragraph type="secondary">
            服务器上每个容器的运行状态、重启次数（本次运行 / 累计）和部署节点信息，每 30 秒自动轮询更新。
          </Typography.Paragraph>
        </div>
        <ReloadOutlined onClick={load} />
      </Space>

      <Table<ContainerStatusRecord>
        rowKey="id"
        loading={loading}
        dataSource={containers}
        pagination={false}
        onRow={(record) => ({ onClick: () => openEvents(record.containerName) })}
        columns={[
          { title: '容器名', dataIndex: 'containerName', width: 220 },
          { title: '节点', dataIndex: 'node', width: 160 },
          {
            title: '状态',
            dataIndex: 'state',
            width: 110,
            render: (value: string) => <Tag color={stateColor[value] || 'default'}>{value}</Tag>
          },
          { title: '镜像', dataIndex: 'image', ellipsis: true },
          { title: '本次运行重启次数', dataIndex: 'dockerRestartCount', width: 150, align: 'right' },
          {
            title: '累计重启次数',
            dataIndex: 'cumulativeRestartCount',
            width: 130,
            align: 'right',
            render: (value: number) => <span style={{ color: value > 0 ? '#cf1322' : undefined }}>{value}</span>
          },
          { title: '启动时间', dataIndex: 'startedAt', width: 180, render: (value?: string) => value || '-' },
          { title: '最近轮询时间', dataIndex: 'lastPolledAt', width: 180 }
        ]}
      />

      <Drawer
        title={`容器事件历史：${eventsFor ?? ''}`}
        open={eventsFor !== null}
        onClose={() => setEventsFor(null)}
        width={480}
      >
        {eventsLoading ? null : events.length === 0 ? (
          <Empty description="暂无重启/崩溃记录" />
        ) : (
          <Timeline
            items={events.map((event) => ({
              color: eventTypeColor[event.eventType] || 'blue',
              children: (
                <div>
                  <Space>
                    <Tag color={eventTypeColor[event.eventType] || 'blue'}>{eventTypeLabel[event.eventType] || event.eventType}</Tag>
                    <span>{event.occurredAt}</span>
                  </Space>
                  {event.detail && <div style={{ color: 'rgba(0,0,0,0.65)', marginTop: 4 }}>{event.detail}</div>}
                </div>
              )
            }))}
          />
        )}
      </Drawer>
    </div>
  );
}
