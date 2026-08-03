import { BugOutlined, CodeOutlined, DeleteOutlined, DownloadOutlined, EditOutlined, FileZipOutlined, HistoryOutlined, InboxOutlined, ReloadOutlined, RollbackOutlined, UploadOutlined } from '@ant-design/icons';
import Editor, { type OnMount } from '@monaco-editor/react';
import { Alert, Button, Divider, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag, Typography, Upload, message } from 'antd';
import axios from 'axios';
import type { RcFile } from 'antd/es/upload';
import { useEffect, useRef, useState } from 'react';
import {
  compileFlinkJar,
  debugRunFlinkJar,
  deleteFlinkJar,
  deleteFlinkJarVersion,
  flinkJarDownloadUrl,
  listFlinkJarVersions,
  pageFlinkJars,
  recompileFlinkJar,
  reuploadFlinkJar,
  restoreFlinkJarVersion,
  updateFlinkJar,
  uploadFlinkJar,
  type FlinkJarRecord,
  type FlinkJarVersionRecord,
  type JavaBuildTargetType
} from '../api/flinkJars';
import { JAVA_JOB_TEMPLATE } from './javaJobTemplate';
import { useAuthStore } from '../store/auth';

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

interface UploadFormValues {
  name: string;
  description?: string;
}

interface CompileFormValues {
  name: string;
  description?: string;
  className: string;
  programArgs?: string;
  targetType: JavaBuildTargetType;
}

interface EditCodeFormValues {
  className: string;
  programArgs?: string;
  targetType: JavaBuildTargetType;
}

const TARGET_TYPE_OPTIONS: { value: JavaBuildTargetType; label: string }[] = [
  { value: 'CLICKHOUSE', label: 'ClickHouse' },
  { value: 'ORACLE', label: 'Oracle' },
  { value: 'MYSQL', label: 'MySQL' },
  { value: 'REDIS', label: 'Redis' },
  { value: 'DORIS', label: 'Doris' },
  { value: 'ALL', label: '全部（需要用到多种驱动时选这个，jar 包会大一些）' }
];

export function JarPackagesPage() {
  const [jars, setJars] = useState<FlinkJarRecord[]>([]);
  const [loading, setLoading] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [selectedFile, setSelectedFile] = useState<RcFile | null>(null);
  const [uploading, setUploading] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [editingJar, setEditingJar] = useState<FlinkJarRecord | null>(null);
  const [savingEdit, setSavingEdit] = useState(false);
  const [reuploadFile, setReuploadFile] = useState<RcFile | null>(null);
  const [reuploading, setReuploading] = useState(false);
  const [versions, setVersions] = useState<FlinkJarVersionRecord[]>([]);
  const [versionsLoading, setVersionsLoading] = useState(false);
  const [versionBusyId, setVersionBusyId] = useState<number | null>(null);
  const [form] = Form.useForm<UploadFormValues>();
  const [editForm] = Form.useForm<UploadFormValues>();
  const [compileModalOpen, setCompileModalOpen] = useState(false);
  const [sourceCode, setSourceCode] = useState(JAVA_JOB_TEMPLATE);
  const [compiling, setCompiling] = useState(false);
  const [compileError, setCompileError] = useState<string | null>(null);
  const [debugRunning, setDebugRunning] = useState(false);
  const [debugOutput, setDebugOutput] = useState<string | null>(null);
  const [compileForm] = Form.useForm<CompileFormValues>();
  const editorRef = useRef<Parameters<OnMount>[0] | null>(null);
  const [editCodeJar, setEditCodeJar] = useState<FlinkJarRecord | null>(null);
  const [editCodeSource, setEditCodeSource] = useState('');
  const [editCodeSaving, setEditCodeSaving] = useState(false);
  const [editCodeError, setEditCodeError] = useState<string | null>(null);
  const [editCodeDebugRunning, setEditCodeDebugRunning] = useState(false);
  const [editCodeDebugOutput, setEditCodeDebugOutput] = useState<string | null>(null);
  const [editCodeForm] = Form.useForm<EditCodeFormValues>();
  const editCodeEditorRef = useRef<Parameters<OnMount>[0] | null>(null);
  const can = useAuthStore((state) => state.hasPermission);

  const load = () => {
    setLoading(true);
    pageFlinkJars({ current: 1, pageSize: 100 })
      .then((data) => setJars(data.records))
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    load();
  }, []);

  const openUpload = () => {
    setSelectedFile(null);
    form.resetFields();
    setModalOpen(true);
  };

  const openCompile = () => {
    setSourceCode(JAVA_JOB_TEMPLATE);
    setCompileError(null);
    setDebugOutput(null);
    compileForm.resetFields();
    compileForm.setFieldsValue({ className: 'com.company.userjobs.MyCustomJob', targetType: 'REDIS' });
    setCompileModalOpen(true);
  };

  const submitCompile = (values: CompileFormValues) => {
    setCompiling(true);
    setCompileError(null);
    compileFlinkJar(values.name, values.description, values.className, sourceCode, values.targetType)
      .then(() => {
        message.success('编译成功，已保存到 JAR 包管理');
        setCompileModalOpen(false);
        load();
      })
      .catch((error) => {
        const serverMessage = axios.isAxiosError(error) ? error.response?.data?.message : undefined;
        setCompileError(serverMessage || '编译失败，请检查代码');
      })
      .finally(() => setCompiling(false));
  };

  const runDebug = () => {
    const className = compileForm.getFieldValue('className');
    if (!className) {
      message.warning('请先填写入口类全限定名');
      return;
    }
    setDebugRunning(true);
    setCompileError(null);
    setDebugOutput(null);
    debugRunFlinkJar(className, sourceCode, compileForm.getFieldValue('programArgs') ?? '', compileForm.getFieldValue('targetType'))
      .then((result) => setDebugOutput(result.output))
      .catch((error) => {
        const serverMessage = axios.isAxiosError(error) ? error.response?.data?.message : undefined;
        setCompileError(serverMessage || '调试运行失败');
      })
      .finally(() => setDebugRunning(false));
  };

  const openEditCode = (record: FlinkJarRecord) => {
    setEditCodeJar(record);
    setEditCodeSource(record.sourceCode ?? '');
    setEditCodeError(null);
    setEditCodeDebugOutput(null);
    editCodeForm.resetFields();
    editCodeForm.setFieldsValue({ className: record.className, targetType: record.targetType });
  };

  const submitEditCode = (values: EditCodeFormValues) => {
    if (!editCodeJar) return;
    setEditCodeSaving(true);
    setEditCodeError(null);
    recompileFlinkJar(editCodeJar.id, values.className, editCodeSource, values.targetType)
      .then(() => {
        message.success('编译成功，已更新 jar 包');
        setEditCodeJar(null);
        load();
      })
      .catch((error) => {
        const serverMessage = axios.isAxiosError(error) ? error.response?.data?.message : undefined;
        setEditCodeError(serverMessage || '编译失败，请检查代码');
      })
      .finally(() => setEditCodeSaving(false));
  };

  const runEditCodeDebug = () => {
    const className = editCodeForm.getFieldValue('className');
    if (!className) {
      message.warning('请先填写入口类全限定名');
      return;
    }
    setEditCodeDebugRunning(true);
    setEditCodeError(null);
    setEditCodeDebugOutput(null);
    debugRunFlinkJar(className, editCodeSource, editCodeForm.getFieldValue('programArgs') ?? '', editCodeForm.getFieldValue('targetType'))
      .then((result) => setEditCodeDebugOutput(result.output))
      .catch((error) => {
        const serverMessage = axios.isAxiosError(error) ? error.response?.data?.message : undefined;
        setEditCodeError(serverMessage || '调试运行失败');
      })
      .finally(() => setEditCodeDebugRunning(false));
  };

  const submitUpload = (values: UploadFormValues) => {
    if (!selectedFile) {
      message.warning('请先选择要上传的 jar 文件');
      return;
    }
    setUploading(true);
    uploadFlinkJar(selectedFile, values.name, values.description)
      .then(() => {
        message.success('上传成功');
        setModalOpen(false);
        load();
      })
      .finally(() => setUploading(false));
  };

  const handleDelete = (id: number) => {
    setBusyId(id);
    deleteFlinkJar(id)
      .then(() => {
        message.success('已删除');
        load();
      })
      .finally(() => setBusyId(null));
  };

  const loadVersions = (jarId: number) => {
    setVersionsLoading(true);
    listFlinkJarVersions(jarId)
      .then(setVersions)
      .finally(() => setVersionsLoading(false));
  };

  const openEdit = (record: FlinkJarRecord) => {
    setEditingJar(record);
    setReuploadFile(null);
    editForm.setFieldsValue({ name: record.name, description: record.description });
    loadVersions(record.id);
  };

  const submitEdit = (values: UploadFormValues) => {
    if (!editingJar) return;
    setSavingEdit(true);
    updateFlinkJar(editingJar.id, values.name, values.description)
      .then(() => {
        message.success('已保存');
        setEditingJar(null);
        load();
      })
      .finally(() => setSavingEdit(false));
  };

  const submitReupload = () => {
    if (!editingJar || !reuploadFile) {
      message.warning('请先选择要上传的 jar 文件');
      return;
    }
    setReuploading(true);
    reuploadFlinkJar(editingJar.id, reuploadFile)
      .then((updated) => {
        message.success('已上传新版本');
        setEditingJar(updated);
        setReuploadFile(null);
        loadVersions(updated.id);
        load();
      })
      .finally(() => setReuploading(false));
  };

  const handleRestoreVersion = (versionId: number) => {
    if (!editingJar) return;
    setVersionBusyId(versionId);
    restoreFlinkJarVersion(editingJar.id, versionId)
      .then((updated) => {
        message.success('已恢复到该版本');
        setEditingJar(updated);
        load();
      })
      .finally(() => setVersionBusyId(null));
  };

  const handleDeleteVersion = (versionId: number) => {
    if (!editingJar) return;
    setVersionBusyId(versionId);
    deleteFlinkJarVersion(editingJar.id, versionId)
      .then(() => {
        message.success('已删除该版本');
        loadVersions(editingJar.id);
      })
      .finally(() => setVersionBusyId(null));
  };

  return (
    <div className="page-stack">
      <Space className="page-title" align="start">
        <div>
          <Typography.Title level={3}><FileZipOutlined /> JAR 包管理</Typography.Title>
          <Typography.Paragraph type="secondary">
            上传 Flink 作业 jar 包，Flink 流作业页面直接从这里按名称选择，不用手输本地绝对路径。
          </Typography.Paragraph>
        </div>
        <Space>
          <Button icon={<ReloadOutlined />} loading={loading} onClick={load}>刷新</Button>
          {can('realtime:jar:upload') && <Button icon={<CodeOutlined />} onClick={openCompile}>在线编写</Button>}
          {can('realtime:jar:upload') && <Button type="primary" icon={<UploadOutlined />} onClick={openUpload}>上传 JAR 包</Button>}
        </Space>
      </Space>

      <Table<FlinkJarRecord>
        rowKey="id"
        loading={loading}
        dataSource={jars}
        pagination={false}
        scroll={{ x: true }}
        columns={[
          { title: '名称', dataIndex: 'name', ellipsis: true },
          { title: '文件名', dataIndex: 'originalName', ellipsis: true },
          { title: '大小', dataIndex: 'sizeBytes', width: 120, align: 'center', render: (value: number) => formatSize(value) },
          { title: '说明', dataIndex: 'description', render: (value?: string) => value || '-' },
          { title: '上传人', dataIndex: 'uploader', width: 120, render: (value?: string) => value || '-' },
          { title: '上传时间', dataIndex: 'createdAt', width: 180 },
          {
            title: '操作',
            width: 320,
            render: (_, record) => (
              <Space size="small">
                {can('realtime:jar:update') && <Button size="small" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>}
                {can('realtime:jar:upload') && record.sourceCode && (
                  <Button size="small" icon={<CodeOutlined />} onClick={() => openEditCode(record)}>查看/编辑代码</Button>
                )}
                <Button size="small" icon={<DownloadOutlined />} href={flinkJarDownloadUrl(record.id)} target="_blank" rel="noreferrer">下载</Button>
                {can('realtime:jar:delete') && (
                  <Popconfirm title="确定删除这个 JAR 包？" onConfirm={() => handleDelete(record.id)}>
                    <Button size="small" danger icon={<DeleteOutlined />} loading={busyId === record.id}>删除</Button>
                  </Popconfirm>
                )}
              </Space>
            )
          }
        ]}
      />

      <Modal
        title="在线编写 Java 作业"
        open={compileModalOpen}
        onCancel={() => setCompileModalOpen(false)}
        onOk={() => compileForm.submit()}
        confirmLoading={compiling}
        okText="编译并保存"
        width={1360}
        destroyOnClose
        // Monaco measures its container's size once, right when it mounts -
        // the modal is still mid open-animation (container effectively 0x0)
        // at that instant, and since the container's actual CSS size never
        // changes afterward (only the modal's transform/opacity animates),
        // no resize ever fires to make it re-measure on its own. Forcing
        // one layout() call once the open animation finishes (afterOpenChange
        // fires exactly then, confirmed live) is the fix - without it the
        // editor silently renders into a 5x5px box.
        afterOpenChange={(open) => {
          if (open) {
            editorRef.current?.layout();
          }
        }}
      >
        <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
          编译使用固定的类路径（Flink API + Kafka 连接器 + jackson + 本平台已有的各数据库驱动 + CdcMirrorSupport 公共逻辑），不支持引入额外的第三方依赖；如果需要用到这几样之外的库，还是得像现有的 cdc-mirror-* 系列一样手写 Maven 模块再上传。"编译并保存"只编译不执行；"调试运行"会真的把代码跑起来（在服务器上单独开一个进程，最多跑 15 秒后自动强制结束），按右边"程序参数"里填的地址连真实的 Kafka/目标数据源，用来在正式提交到 Flink 集群之前，提前看看代码到底跑不跑得通。
        </Typography.Paragraph>
        {/* Side-by-side instead of everything stacked above the editor - the
            code is what someone actually spends their time looking at/editing
            here, and it used to get squeezed into a small fixed-height box
            below five full-width form fields, forcing scrolling inside the
            modal just to see it. */}
        <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
          <div style={{ width: 300, flexShrink: 0 }}>
            <Form form={compileForm} layout="vertical" onFinish={submitCompile}>
              <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]} extra="Flink 流作业页面选择 jar 包时显示这个名称">
                <Input placeholder="例如：自定义 CDC 同步作业" />
              </Form.Item>
              <Form.Item name="description" label="说明">
                <Input.TextArea rows={2} />
              </Form.Item>
              <Form.Item
                name="className"
                label="入口类全限定名"
                rules={[{ required: true, message: '请输入入口类全限定名' }, { pattern: /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$/, message: '需要是合法的 Java 全限定类名' }]}
                extra="要跟右边代码里 public class 的类名（含包名）完全一致"
              >
                <Input placeholder="com.company.userjobs.MyCustomJob" />
              </Form.Item>
              <Form.Item
                name="targetType"
                label="目标数据源类型"
                rules={[{ required: true, message: '请选择目标数据源类型' }]}
                extra="决定编译时合入哪个已有驱动包 - 选具体某个类型时 jar 包更小；只有代码里同时用到多种驱动才需要选“全部”"
              >
                <Select options={TARGET_TYPE_OPTIONS} />
              </Form.Item>
              <Form.Item
                name="programArgs"
                label="程序参数（仅调试运行使用）"
                extra="调试运行是从这台服务器自己发起的连接，不是从 Docker 网络内部发起 - Kafka/Redis 等地址要填这台机器能访问到的（通常是 localhost + 对外映射端口），跟 Flink 流作业里最终提交时要填的 Docker 网络地址不是一回事"
              >
                <Input placeholder="--kafka-bootstrap localhost:19092 --topic ..." />
              </Form.Item>
            </Form>
            <Button icon={<BugOutlined />} loading={debugRunning} onClick={runDebug} block>调试运行</Button>
            {compileError && <Alert type="error" showIcon message="编译失败" description={<pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{compileError}</pre>} style={{ marginTop: 12 }} />}
            {debugOutput && (
              <pre
                style={{
                  whiteSpace: 'pre-wrap',
                  margin: '12px 0 0',
                  maxHeight: 300,
                  overflow: 'auto',
                  background: '#001529',
                  color: '#d9d9d9',
                  padding: 12,
                  borderRadius: 6,
                  fontSize: 12
                }}
              >
                {debugOutput}
              </pre>
            )}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden' }}>
              <Editor
                height="640px"
                language="java"
                value={sourceCode}
                onChange={(value) => setSourceCode(value ?? '')}
                onMount={(editor) => {
                  editorRef.current = editor;
                  // Modal's open animation may still be finishing when Monaco
                  // itself finishes loading (confirmed live: the two race, and
                  // afterOpenChange alone isn't reliable since it can fire
                  // before this callback sets editorRef) - laying out here,
                  // exactly when the editor instance actually exists, is what
                  // reliably fixes it regardless of which one wins the race.
                  editor.layout();
                }}
                // Container width comes from a flex sibling (the sidebar
                // form), which can still be settling its own layout the
                // instant Monaco's onMount/afterOpenChange manual layout()
                // calls fire - confirmed live: those two land the editor at
                // a stale, narrower width that never corrects itself
                // afterward. automaticLayout keeps Monaco's own internal
                // ResizeObserver watching its container, so it's unaffected
                // by exactly when the one-off manual calls happen to run.
                options={{ minimap: { enabled: false }, fontSize: 13, automaticLayout: true }}
              />
            </div>
          </div>
        </div>
      </Modal>

      <Modal
        title={`查看/编辑代码${editCodeJar ? ` - ${editCodeJar.name}` : ''}`}
        open={!!editCodeJar}
        onCancel={() => setEditCodeJar(null)}
        onOk={() => editCodeForm.submit()}
        confirmLoading={editCodeSaving}
        okText="编译并保存"
        width={1360}
        destroyOnClose
        afterOpenChange={(open) => {
          if (open) {
            editCodeEditorRef.current?.layout();
          }
        }}
      >
        <Typography.Paragraph type="secondary" style={{ marginBottom: 12 }}>
          修改后点击"编译并保存"会重新编译并直接替换这个 jar 包正在使用的文件（旧版本仍保留在"编辑"弹窗的历史版本里，可以恢复）。跟"在线编写"一样，"调试运行"只是先跑一下看看代码通不通，不会保存。
        </Typography.Paragraph>
        <div style={{ display: 'flex', gap: 16, alignItems: 'flex-start' }}>
          <div style={{ width: 300, flexShrink: 0 }}>
            <Form form={editCodeForm} layout="vertical" onFinish={submitEditCode}>
              <Form.Item
                name="className"
                label="入口类全限定名"
                rules={[{ required: true, message: '请输入入口类全限定名' }, { pattern: /^[A-Za-z_][A-Za-z0-9_]*(\.[A-Za-z_][A-Za-z0-9_]*)*$/, message: '需要是合法的 Java 全限定类名' }]}
                extra="要跟右边代码里 public class 的类名（含包名）完全一致"
              >
                <Input placeholder="com.company.userjobs.MyCustomJob" />
              </Form.Item>
              <Form.Item
                name="targetType"
                label="目标数据源类型"
                rules={[{ required: true, message: '请选择目标数据源类型' }]}
                extra="决定编译时合入哪个已有驱动包"
              >
                <Select options={TARGET_TYPE_OPTIONS} />
              </Form.Item>
              <Form.Item
                name="programArgs"
                label="程序参数（仅调试运行使用）"
                extra="调试运行是从这台服务器自己发起的连接，不是从 Docker 网络内部发起 - Kafka/Redis 等地址要填这台机器能访问到的（通常是 localhost + 对外映射端口）"
              >
                <Input placeholder="--kafka-bootstrap localhost:19092 --topic ..." />
              </Form.Item>
            </Form>
            <Button icon={<BugOutlined />} loading={editCodeDebugRunning} onClick={runEditCodeDebug} block>调试运行</Button>
            {editCodeError && <Alert type="error" showIcon message="编译失败" description={<pre style={{ whiteSpace: 'pre-wrap', margin: 0 }}>{editCodeError}</pre>} style={{ marginTop: 12 }} />}
            {editCodeDebugOutput && (
              <pre
                style={{
                  whiteSpace: 'pre-wrap',
                  margin: '12px 0 0',
                  maxHeight: 300,
                  overflow: 'auto',
                  background: '#001529',
                  color: '#d9d9d9',
                  padding: 12,
                  borderRadius: 6,
                  fontSize: 12
                }}
              >
                {editCodeDebugOutput}
              </pre>
            )}
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ border: '1px solid #d9d9d9', borderRadius: 6, overflow: 'hidden' }}>
              <Editor
                height="640px"
                language="java"
                value={editCodeSource}
                onChange={(value) => setEditCodeSource(value ?? '')}
                onMount={(editor) => {
                  editCodeEditorRef.current = editor;
                  editor.layout();
                }}
                // Container width comes from a flex sibling (the sidebar
                // form), which can still be settling its own layout the
                // instant Monaco's onMount/afterOpenChange manual layout()
                // calls fire - confirmed live: those two land the editor at
                // a stale, narrower width that never corrects itself
                // afterward. automaticLayout keeps Monaco's own internal
                // ResizeObserver watching its container, so it's unaffected
                // by exactly when the one-off manual calls happen to run.
                options={{ minimap: { enabled: false }, fontSize: 13, automaticLayout: true }}
              />
            </div>
          </div>
        </div>
      </Modal>

      <Modal
        title="上传 JAR 包"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        onOk={() => form.submit()}
        confirmLoading={uploading}
        destroyOnClose
      >
        <Form form={form} layout="vertical" onFinish={submitUpload}>
          <Form.Item label="文件" required>
            <Upload.Dragger
              accept=".jar"
              maxCount={1}
              beforeUpload={(file) => {
                setSelectedFile(file);
                if (!form.getFieldValue('name')) {
                  form.setFieldsValue({ name: file.name.replace(/\.jar$/i, '') });
                }
                return false;
              }}
              onRemove={() => setSelectedFile(null)}
              fileList={selectedFile ? [selectedFile] : []}
            >
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">点击或拖拽 jar 文件到这里</p>
            </Upload.Dragger>
          </Form.Item>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]} extra="Flink 流作业页面选择 jar 包时显示这个名称，不是文件名">
            <Input placeholder="例如：任务统计作业" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="编辑 JAR 包"
        open={!!editingJar}
        onCancel={() => setEditingJar(null)}
        onOk={() => editForm.submit()}
        confirmLoading={savingEdit}
        width={640}
        destroyOnClose
      >
        <Form form={editForm} layout="vertical" onFinish={submitEdit}>
          <Form.Item name="name" label="名称" rules={[{ required: true, message: '请输入名称' }]} extra="Flink 流作业页面选择 jar 包时显示这个名称，不是文件名">
            <Input placeholder="例如：任务统计作业" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} />
          </Form.Item>
        </Form>

        {can('realtime:jar:upload') && (
          <>
            <Divider>重新上传文件</Divider>
            <Upload.Dragger
              accept=".jar"
              maxCount={1}
              beforeUpload={(file) => {
                setReuploadFile(file);
                return false;
              }}
              onRemove={() => setReuploadFile(null)}
              fileList={reuploadFile ? [reuploadFile] : []}
            >
              <p className="ant-upload-drag-icon"><InboxOutlined /></p>
              <p className="ant-upload-text">点击或拖拽新的 jar 文件到这里，替换当前使用的文件</p>
            </Upload.Dragger>
            <Space style={{ marginTop: 12, marginBottom: 12 }}>
              <Button icon={<UploadOutlined />} loading={reuploading} onClick={submitReupload}>上传新版本</Button>
            </Space>
          </>
        )}

        <Divider><HistoryOutlined /> 历史版本</Divider>
        <Table<FlinkJarVersionRecord>
          rowKey="id"
          size="small"
          loading={versionsLoading}
          dataSource={versions}
          pagination={false}
          columns={[
            { title: '文件名', dataIndex: 'originalName', ellipsis: true },
            { title: '大小', dataIndex: 'sizeBytes', width: 100, render: (value: number) => formatSize(value) },
            { title: '上传人', dataIndex: 'uploader', width: 100, render: (value?: string) => value || '-' },
            { title: '上传时间', dataIndex: 'createdAt', width: 170 },
            {
              title: '操作',
              width: 160,
              render: (_, version) => {
                const isCurrent = editingJar?.storagePath === version.storagePath;
                if (isCurrent) {
                  return <Tag color="blue">当前使用中</Tag>;
                }
                return (
                  <Space size="small">
                    {can('realtime:jar:upload') && (
                      <Button size="small" icon={<RollbackOutlined />} loading={versionBusyId === version.id} onClick={() => handleRestoreVersion(version.id)}>恢复</Button>
                    )}
                    {can('realtime:jar:delete') && (
                      <Popconfirm title="确定删除这个历史版本？" onConfirm={() => handleDeleteVersion(version.id)}>
                        <Button size="small" danger icon={<DeleteOutlined />} loading={versionBusyId === version.id}>删除</Button>
                      </Popconfirm>
                    )}
                  </Space>
                );
              }
            }
          ]}
        />
      </Modal>
    </div>
  );
}
