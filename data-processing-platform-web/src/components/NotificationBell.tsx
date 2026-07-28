import { BellOutlined } from '@ant-design/icons';
import { Badge, Button, Empty, List, Popover, Typography } from 'antd';
import { useCallback, useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { markAllMessagesRead, markMessageRead, pageMessages, unreadMessageCount, type MessageRecord } from '../api/messages';

// Polling rather than push - this app has no websocket/SSE channel anywhere
// else, so a lightweight interval matches every other "did something change"
// check in this codebase (CdcSourcesPage's status refresh, etc.).
const POLL_INTERVAL_MS = 30000;

export function NotificationBell() {
  const navigate = useNavigate();
  const [unread, setUnread] = useState(0);
  const [messages, setMessages] = useState<MessageRecord[]>([]);
  const [open, setOpen] = useState(false);
  const [loading, setLoading] = useState(false);

  const refreshUnread = useCallback(() => {
    unreadMessageCount()
      .then(setUnread)
      .catch(() => undefined);
  }, []);

  useEffect(() => {
    refreshUnread();
    const timer = window.setInterval(refreshUnread, POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [refreshUnread]);

  const loadMessages = () => {
    setLoading(true);
    pageMessages({ current: 1, pageSize: 10 })
      .then((data) => setMessages(data.records))
      .finally(() => setLoading(false));
  };

  const handleOpenChange = (nextOpen: boolean) => {
    setOpen(nextOpen);
    if (nextOpen) {
      loadMessages();
    }
  };

  const handleClickMessage = (message: MessageRecord) => {
    if (message.readStatus === 'UNREAD') {
      markMessageRead(message.id).then(refreshUnread);
      setMessages((prev) => prev.map((item) => (item.id === message.id ? { ...item, readStatus: 'READ' } : item)));
    }
    setOpen(false);
    if (message.linkUrl) {
      navigate(message.linkUrl);
    }
  };

  const handleMarkAllRead = () => {
    markAllMessagesRead().then(() => {
      refreshUnread();
      setMessages((prev) => prev.map((item) => ({ ...item, readStatus: 'READ' })));
    });
  };

  return (
    <Popover
      trigger="click"
      open={open}
      onOpenChange={handleOpenChange}
      placement="bottomRight"
      title={
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <span>通知</span>
          <Button type="link" size="small" onClick={handleMarkAllRead} disabled={unread === 0}>全部已读</Button>
        </div>
      }
      content={
        <div style={{ width: 360, maxHeight: 400, overflowY: 'auto' }}>
          <List
            loading={loading}
            dataSource={messages}
            locale={{ emptyText: <Empty description="暂无通知" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
            renderItem={(message) => (
              <List.Item
                style={{ cursor: 'pointer', opacity: message.readStatus === 'UNREAD' ? 1 : 0.55 }}
                onClick={() => handleClickMessage(message)}
              >
                <List.Item.Meta
                  title={message.title}
                  description={
                    <>
                      <Typography.Paragraph type="secondary" ellipsis={{ rows: 2 }} style={{ marginBottom: 4 }}>
                        {message.content}
                      </Typography.Paragraph>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>{message.createdAt}</Typography.Text>
                    </>
                  }
                />
              </List.Item>
            )}
          />
        </div>
      }
    >
      <Badge count={unread} size="small">
        <Button aria-label="通知" icon={<BellOutlined />} />
      </Badge>
    </Popover>
  );
}
