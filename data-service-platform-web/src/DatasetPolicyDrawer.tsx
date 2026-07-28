import {
  DeleteOutlined,
  PlusOutlined,
  SafetyCertificateOutlined,
  SaveOutlined
} from '@ant-design/icons';
import { Button, Drawer, Form, Input, Select, Space, Typography, message } from 'antd';
import { useEffect, useState } from 'react';
import {
  errorMessage,
  getDatasetAccessPolicy,
  updateDatasetAccessPolicy,
  type DatasetAccessPolicy,
  type DatasetRecord
} from './api';

interface Props {
  dataset: DatasetRecord | null;
  canManage: boolean;
  onClose: () => void;
}

const actionOptions = [
  { value: 'MASK', label: '脱敏返回' },
  { value: 'HIDE', label: '禁止返回' }
];

const maskOptions = [
  { value: 'FULL', label: '完全遮盖' },
  { value: 'PARTIAL', label: '保留首尾' },
  { value: 'EMAIL', label: '邮箱脱敏' },
  { value: 'PHONE', label: '手机号脱敏' },
  { value: 'HASH', label: 'SHA-256' }
];

export default function DatasetPolicyDrawer({ dataset, canManage, onClose }: Props) {
  const [form] = Form.useForm<DatasetAccessPolicy>();
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    if (!dataset) {
      return;
    }
    setLoading(true);
    getDatasetAccessPolicy(dataset.id)
      .then((policy) => form.setFieldsValue({
        ...policy,
        columns: policy.columns || []
      }))
      .catch((error) => message.error(errorMessage(error, '加载访问策略失败')))
      .finally(() => setLoading(false));
  }, [dataset, form]);

  const save = async () => {
    if (!dataset) {
      return;
    }
    try {
      const values = await form.validateFields();
      setSaving(true);
      const policy = await updateDatasetAccessPolicy(dataset.id, {
        rowFilterSql: values.rowFilterSql,
        columns: values.columns || []
      });
      if ('pendingApproval' in policy) {
        message.success(`生产策略变更已提交审批，审批单 #${policy.changeRequest.id}`);
        onClose();
      } else {
        form.setFieldsValue(policy);
        message.success('访问策略已生效');
      }
    } catch (error) {
      if (error && typeof error === 'object' && 'errorFields' in error) {
        return;
      }
      message.error(errorMessage(error, '保存访问策略失败'));
    } finally {
      setSaving(false);
    }
  };

  return (
    <Drawer
      title={(
        <Space>
          <SafetyCertificateOutlined />
          <span>{dataset?.name || '数据集'} · 访问策略</span>
        </Space>
      )}
      open={Boolean(dataset)}
      onClose={onClose}
      width={680}
      loading={loading}
      extra={canManage ? (
        <Button type="primary" icon={<SaveOutlined />} loading={saving} onClick={save}>
          保存
        </Button>
      ) : null}
      destroyOnClose
    >
      <Form form={form} layout="vertical" disabled={!canManage}>
        <Form.Item
          name="rowFilterSql"
          label="行过滤条件"
          rules={[{ max: 1000, message: '行过滤条件不能超过 1000 个字符' }]}
        >
          <Input.TextArea
            rows={3}
            placeholder="例如 tenant_code = :_appKey"
            spellCheck={false}
          />
        </Form.Item>

        <div className="policy-section-heading">
          <Typography.Text strong>字段策略</Typography.Text>
        </div>
        <Form.List name="columns">
          {(fields, { add, remove }) => (
            <Space direction="vertical" size={10} className="policy-list">
              {fields.map((field) => (
                <div className="policy-row" key={field.key}>
                  <Form.Item
                    {...field}
                    name={[field.name, 'columnName']}
                    rules={[
                      { required: true, message: '请输入字段名' },
                      { pattern: /^[A-Za-z][A-Za-z0-9_$]*$/, message: '字段名格式不正确' }
                    ]}
                  >
                    <Input placeholder="字段名" />
                  </Form.Item>
                  <Form.Item
                    {...field}
                    name={[field.name, 'action']}
                    rules={[{ required: true, message: '请选择动作' }]}
                  >
                    <Select placeholder="处理动作" options={actionOptions} />
                  </Form.Item>
                  <Form.Item {...field} name={[field.name, 'maskType']}>
                    <Select placeholder="脱敏方式" options={maskOptions} />
                  </Form.Item>
                  {canManage && (
                    <Button
                      icon={<DeleteOutlined />}
                      danger
                      title="删除字段策略"
                      onClick={() => remove(field.name)}
                    />
                  )}
                </div>
              ))}
              {canManage && (
                <Button
                  type="dashed"
                  icon={<PlusOutlined />}
                  onClick={() => add({ action: 'MASK', maskType: 'FULL' })}
                  block
                >
                  添加字段策略
                </Button>
              )}
            </Space>
          )}
        </Form.List>
      </Form>
    </Drawer>
  );
}
