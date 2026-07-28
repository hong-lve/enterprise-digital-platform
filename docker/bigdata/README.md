> **本地筛减说明**：这份 README 是从上游项目原样复制过来的，但本目录的
> `docker-compose.yml` 已经去掉了 `hive-metastore`/`hive-server2`/
> `spark-master`/`spark-worker` 四个服务——本仓库只有
> `enterprise-portal-service` 和 `data-processing-platform-service` 两个后端，代码里
> 没有任何地方引用 Spark/Hive（没有 `SparkSubmissionClient`，没有
> `SPARK_JOB`/`FLINK_JOB` 任务流节点，没有 Hive JDBC 数据源），所以下面文档
> 里涉及 Hive/Spark 的部分（端口表前两行、Spark 相关行、"验证 Hive/Spark"、
> "SPARK_JOB/FLINK_JOB 冒烟测试"）在本仓库里不适用，仅作历史参考。同时
> `mysql-init/01-restore.sql`（个人历史数据快照）也没有复制过来，本地是从
> 空库启动，由各服务自己的 Flyway 迁移建表。

# 本地 Hive / Spark / Flink / Kafka / ClickHouse 测试环境

�?`data-processing-platform-service`（离线批处理：Hive/Spark/Flink）和 `data-processing-platform-service`（实时：CDC/Kafka/Flink 流作�?ClickHouse）接入真实大数据组件用的最小化本地测试环境。不需�?Hadoop/HDFS/YARN 集群：Hive 用内�?Derby 元数据库 + 本地文件系统�?warehouse；Spark、Flink 都跑 standalone 模式；Kafka �?KRaft 单节点模式（不用额外�?Zookeeper）；ClickHouse 单节点、不做分�?副本。仅用于功能联调，不代表生产部署方式�?
## 启动

```bash
cd docker/bigdata
docker compose up -d
```

首次启动会拉取镜像，之后 `docker compose up -d` / `docker compose down` 即可�?
## 端口与连接方�?
宿主机端口特意避开了本项目自己用的 8080-8085（六个后端服务）�?5173-5178（六个前端），互不冲突�?
| 组件 | 地址 | 说明 |
| --- | --- | --- |
| Hive Metastore | `thrift://localhost:9083` | Spark/Flink 以后�?Hive Catalog 时用 |
| HiveServer2 JDBC | `jdbc:hive2://localhost:10001` | 元数据浏�?/ SQL 工作台可以直接当一个新的数据源类型接入。宿主机端口从默认的 10000 改成�?10001，因�?10000 常被企业 VPN/安全客户端（比如深信服）占用 |
| HiveServer2 Web UI | http://localhost:10002 | 看查询状�?|
| Spark Master RPC | `spark://localhost:7077` | 提交 Spark 作业�?|
| Spark Master Web UI | http://localhost:18080 | 看集群和作业状�?|
| Spark Master REST Submission API | http://localhost:6066 | `data-processing-platform-service` �?`SparkSubmissionClient` 提交/轮询 SPARK_JOB 任务走这个接口，Standalone Master 自带、默认开启，不需要额外起 Livy |
| Spark Worker Web UI | http://localhost:18081 | |
| Flink Web UI / REST API | http://localhost:18082 | 提交 JAR、查作业状态都走这�?REST 接口，`data-processing-platform-service` �?`FlinkRestClient`（批作业）和 `data-processing-platform-service` �?`FlinkStreamSubmissionClient`（流作业）共用同一个集�?|
| Flink SQL Gateway REST API | http://localhost:18084 | `data-processing-platform-service` �?`FlinkSqlGatewayClient` 用这个做交互�?SQL 查询（`v1/sessions`/`statements`/`result` 那套会话协议），�?remote 模式提交到上面同一�?Flink 集群，不是单独的 mini-cluster |
| Kafka Broker（容器内�?| `kafka:9092` | `kafka-connect`/`kafka-ui` 在同一�?docker 网络里用这个地址 |
| Kafka Broker（宿主机�?| `localhost:19092` | 本地调试工具 / 以后阶段3 Flink 作业消费�?|
| Kafka Connect REST API | http://localhost:18083 | `data-processing-platform-service` �?`KafkaConnectClient` 部署/管理 Debezium connector 走这个接口。默认端�?8083 �?`insight-screen-service` 后端冲突，改成了 18083 |
| Kafka UI | http://localhost:18090 | �?topic、消息内容、connector 状态，不用只靠 REST 调试 |
| ClickHouse HTTP 接口 | http://localhost:8123 | JDBC 走这个，`data-processing-platform-service` �?`ClickHouseQueryService` 用的就是它，账号 `realtime`/`realtime123`，库 `realtime_analytics` |
| ClickHouse 原生协议 | `localhost:9000` | �?`clickhouse-client` 手动建表/调试用，不是平台代码会连的端�?|
| MinIO S3 API | http://localhost:19000 | `data-processing-platform-service` �?`JarStorageService` �?Flink 作业 JAR 用这个，账号 `minioadmin`/`minioadmin` |
| MinIO 控制�?| http://localhost:19001 | 浏览桶内�?手动排查用的 Web UI，账号同�?|

`spark-master`/`spark-worker` 都挂载了 `./spark-apps` 到容器内 `/opt/spark-apps`，放要提交的作业 JAR——Standalone REST 提交�?`appResource` 必须是运�?driver 的那个容器（也就是某�?worker）本地能访问到的路径，所以两边都挂了同一个目录�?
`flink-jobmanager`/`flink-taskmanager` 都挂载了一个 `flink-state` 卷到 `/opt/flink/state`，配置 `state.checkpoints.dir`/`state.savepoints.dir` 指向这个目录——`data-processing-platform-service` 的流作业停止时用 stop-with-savepoint（不是硬 cancel），savepoint 存这里，下次启动能接着跑。`flink-taskmanager` 有 20 个 task slot，`taskmanager.memory.process.size` 设为 8192m（原来没配置这一项时 Flink 默认只给 1728m 总进程内存，20 个槽位分下来每个槽位堆内存只有个位数 MB，实际跑不了几个并发作业；已在有更大内存的宿主机上实测验证：8 个流作业同时启动全部达到 RUNNING，未出现 OOM）。本地资源紧张的开发机上如果内存不够，可以调低这个值或减少 `taskmanager.numberOfTaskSlots`。
## MySQL：现在是 docker-compose 里的容器，不是装在宿主机�?
这几个后端服务共用的 MySQL（含共享�?`platform_auth` 库）现在是这�?compose 里的 `mysql` 服务（`mysql:8.0`），不再要求你自己在宿主机装一个。发布在 **13306**（不是标准的 3306），避免跟你机器上可能还装着的其�?MySQL 冲突——各服务 `application.yml` �?`MYSQL_PORT` 默认值已经改�?13306，本地跑这些服务不需要额外配置�?
数据用具名卷 `mysql-data` 持久化，容器重启/`docker compose down`（不�?`-v`）不会丢数据�?
首次启动（数据卷为空）时会自动跑 `docker/bigdata/mysql-init/` 下的两个脚本�?- `01-restore.sql`：从旧的宿主�?MySQL 导出�?6 个库（`data_platform_db`/`insight_screen_db`/`ops_admin_db`/`platform_auth`/`data_platform_db`/`screen_admin_db`）的快照，把当时的数据（登录用户、JAR、数据源配置等）原样带过来�?- `02-post-restore-fixes.sql`：重建下面的 `debezium` CDC 账号（导出快照只包含�?6 个业务库，不�?`mysql` 系统库里的账号），并把已有的那条 MySQL 类型 CDC 数据源的地址�?`host.docker.internal` 改成 `mysql`（现�?Kafka Connect �?MySQL 是同一�?compose 网络里的兄弟容器，不再需要通过宿主机地址互通）�?
之后重启容器只会复用这个卷，两个脚本都不会重跑（MySQL 官方镜像的行为：`/docker-entrypoint-initdb.d/*` 只在数据目录为空时执行一次）�?
官方 `mysql:8.0` 镜像默认就开着 `log_bin=ON`/`binlog_format=ROW`（实测确认，不需要额外配置），CDC 开箱可用。Debezium 专用账号（最小权限，不直接用 root）已经在 `02-post-restore-fixes.sql` 里建好：`debezium`/`debezium123`�?
**新建 CDC 数据源时**，`数据库地址` 现在�?**`mysql`**（compose 里的服务名）——不再是 `host.docker.internal`，也不是 `localhost`（`localhost` 会指�?Kafka Connect 容器自己）�?
## 验证环境是否正常

**Hive**（用任意 JDBC 客户端，比如 DataGrip 新建一�?Hive 数据源，或者用 beeline）：

```bash
docker exec -it bigdata-hive-server2 beeline -u jdbc:hive2://localhost:10000
```

进去后跑�?
```sql
CREATE TABLE demo (id INT, name STRING);
INSERT INTO demo VALUES (1, 'test');
SELECT * FROM demo;
```

**Spark**：打开 http://localhost:18080 ，应该能看到 1 �?Worker 已注册�?
**Flink**：打开 http://localhost:18082 ，应该能看到 Task Managers 里有 1 台、Task Slots �?2 个�?
**Kafka Connect**�?
```bash
curl http://localhost:18083/connectors
```

没部署过 connector 的话应该返回 `[]`�?
## 端到端冒烟测试：CDC（Debezium�?
前提：MySQL 容器已经跑起来（binlog 默认开着，`debezium` 账号已经�?`02-post-restore-fixes.sql` 建好，见上）�?
1. �?`data-processing-platform-web` �?CDC 数据�?页面新建一条：数据库地址�?`mysql`，端�?`3306`（容器内部端口，不是发布到宿主机�?13306），用户�?密码�?`debezium`/`debezium123`，数据库名和表按需要填（挑一张不影响其他服务的小表测试即可），点"启动"�?2. 状态应该在几秒内变�?`RUNNING`；打开 http://localhost:18090 ，应该能�?Topics 里看到一个新 topic（名字是 `<topic_prefix>.<数据库名>.<表名>`）�?3. 对这张表跑一�?`INSERT`/`UPDATE`/`DELETE`，几秒内应该能在 Kafka UI 的这�?topic 的消息列表里看到对应的变更事件（Debezium 标准 JSON 结构，带 `before`/`after`/`op`/`source` 字段）�?4. �?停止"，connector 应该变成 `PAUSED`，之后再改这张表不应该再产生新消息�?
## 端到端冒烟测试：SPARK_JOB / FLINK_JOB

`data-processing-platform-service` 已经接了 Spark/Flink 批作业提交（任务流里�?`SPARK_JOB`/`FLINK_JOB` 节点类型），不用自己写作�?JAR，两个镜像都自带示例 JAR，可以直接拿来验证链路通不通�?
**Spark**：把镜像自带�?`spark-examples` JAR 复制�?`./spark-apps`（挂载进容器的目录）�?
```bash
docker cp bigdata-spark-master:/opt/spark/examples/jars/ ./spark-apps-tmp
cp ./spark-apps-tmp/spark-examples_*.jar ./spark-apps/
```

在任务流画布新建一�?SPARK_JOB 节点�?- `jarPath`: `/opt/spark-apps/spark-examples_2.12-3.5.8.jar`（文件名以实际拷贝出来的为准�?- `mainClass`: `org.apache.spark.examples.SparkPi`
- `appArgs`: `10`

执行后到 http://localhost:18080 看这�?application 是否 completed�?
**Flink**：镜像内自带 `/opt/flink/examples/batch/WordCount.jar`，容器内路径可以直接填（Flink 提交是走 HTTP multipart 上传 JAR 内容，不需要共享卷，但 `data-processing-platform-service` 进程本身要能读到这个文件——本地跑服务的话，先 `docker cp bigdata-flink-jobmanager:/opt/flink/examples/batch/WordCount.jar .` 拷到本地再在节点里填本地路径）。新�?FLINK_JOB 节点�?- `jarPath`: 上一步拷贝出来的本地路径
- `entryClass`: 留空（用 JAR 自带�?manifest 里的 Main-Class 即可�?- `parallelism`: `1`

执行后到 http://localhost:18082 �?Completed Jobs 里看结果�?
## 端到端冒烟测试：Flink 流作业生命周期（data-processing-platform-service�?
跟上�?FLINK_JOB 批作业测试用的镜像自带示例不同——批作业�?`WordCount.jar`（跑完就结束），流作业用 `StateMachineExample.jar`（自己生成数据、一直跑下去，专门用来测"长驻作业"这类生命周期）：

```bash
docker cp bigdata-flink-jobmanager:/opt/flink/examples/streaming/StateMachineExample.jar .
```

�?`data-processing-platform-web` �?Flink 流作�?页面新建一个：
- `jarPath`：上一步拷贝出来的本地路径（是 `data-processing-platform-service` 进程本地能读到的路径，不是容器内路径�?- `entryClass`：留空（�?JAR 自带�?Main-Class�?- 其余用默认值即�?
�?启动"，确认：
1. 状态变�?`RUNNING`，http://localhost:18082 �?Running Jobs 里能看到这个作业，Checkpoints 标签页能看到按配置间隔在�?checkpoint�?2. �?停止"，状态变�?`已停止`，`savepointPath` 字段有值（悬浮在状态标签上能看到）�?3. 再点"启动"，Flink UI 里这次提交的作业详情应该能看到是�?savepoint 恢复的（不是从头开始生成数据）�?4. 故意�?`jarPath` 改错再启动一次，验证失败路径：状态应该变�?`失败`，悬浮状态标签能看到具体报错�?
## 端到端冒烟测试：实时数据查询（ClickHouse�?
这个平台不写 Flink→ClickHouse 的具体写入代码（那部�?JAR 用户自己提供），所以验证的�?查询这条链路通不�?，不是完整的实时计算流程。先�?`clickhouse-client` 手动建张测试表、塞几条数据�?
```bash
docker exec -it bigdata-clickhouse clickhouse-client --user realtime --password realtime123 --database realtime_analytics
```

进去后跑�?
```sql
CREATE TABLE order_events (
  order_id UInt64,
  status String,
  amount Decimal(10,2),
  event_time DateTime
) ENGINE = MergeTree()
ORDER BY (order_id, event_time);

INSERT INTO order_events VALUES (1001, 'PAID', 199.00, now());
```

�?`data-processing-platform-web` �?实时数据查询"页面�?1. 左侧表列表应该能看到 `order_events`，点一下会自动填一�?`SELECT * FROM order_events LIMIT 100`�?2. �?执行"，确认能查出刚插入的数据�?3. �?SQL 换成 `DROP TABLE order_events` �?`INSERT INTO ...` 再执行，应该被拒绝（"只允许执�?SELECT 查询"）——这个页面是只读的，建表/写入必须�?`clickhouse-client`�?
## Flink �?ClickHouse 写入的幂等模�?
`flink-jobs/task-stats-job` 这类 Flink 作业�?ClickHouse sink 通常不参�?Flink �?checkpoint 两阶段提交协议（普�?`RichSinkFunction` 里裸�?JDBC `INSERT` 就是这样）。这意味着作业�?checkpoint 恢复时（TaskManager 挂掉重启、或者任何触�?Flink 自身重启策略的失败），已经写�?ClickHouse 的某个窗口结果可能被重新计算、重新发一遍，产生物理重复行。`task_execution_stats` 表已经用这个模式修过一次，可以照抄�?
```sql
CREATE TABLE task_execution_stats
(
    `window_start` DateTime,
    `result` String,
    `event_count` UInt64,
    `updated_at` DateTime DEFAULT now()
)
ENGINE = ReplacingMergeTree(updated_at)
ORDER BY (window_start, result)
```

sink 每次写入时把 `updated_at` 设成实际写入时刻（不是窗口时间）——同一�?`(window_start, result)` 被重复投递时，物理上晚写入的那次自然拿到更大�?`updated_at`，被 `ReplacingMergeTree` 选中�?*不要�?`SummingMergeTree`**：Flink 重算后发出的是窗口的完整计数，不是增量，`SummingMergeTree` 会把重复的两次加在一起、把数字算错�?
**关键代价**：`ReplacingMergeTree` 的去重只在后�?merge 时发生，紧跟在一次重复写入之后立刻查询仍可能看到多行。查询这类表要显式加 `FINAL`（`SELECT ... FROM task_execution_stats FINAL`）才能保证拿到去重后的正确结果，`FINAL` 会在查询时做一次同步合并，比普通查询慢，数据量小的表可以接受。平台的 `ClickHouseQueryService`/"实时数据查询"页面是通用只读查询框，不会替业务表自动�?`FINAL`——用不用、怎么用是业务表自己的责任�?
## 下一步：接入代码

这个环境目前接了：Hive JDBC（元数据浏览/SQL 工作台）、Spark/Flink 批作业提交（`data-processing-platform-service` 任务�?SPARK_JOB/FLINK_JOB 节点）、CDC 采集（`data-processing-platform-service` �?CDC 数据源管理，Debezium �?Kafka）、Flink 流作业生命周期管理（提交/停止+savepoint/重启策略）、实时数据查询（ClickHouse，只读）、Flink SQL Gateway（交互式查询 Kafka topic，CREATE TABLE + SELECT）、Flink/CDC 失败告警（站内消�?+ Webhook 外发，OK/ALERTING 边沿触发）、实时血缘（CDC �?�?Kafka topic �?Flink 作业 �?ClickHouse 表）、Flink 提交前集群容量检查、环境标�?生产环境操作门禁（逻辑隔离，非物理集群隔离）。`flink-jobs/task-stats-job` 是一个真实跑通的示例业务作业（读 CDC topic、聚合、幂等写�?ClickHouse），证明了这条链路且�?`ReplacingMergeTree` 处理�?checkpoint 重算导致的重复写入，见上一节�?
还没做的�?*Spark SQL（交互式查询�?*——思路�?Hive，但这套环境�?Spark 没起 Thrift Server（没暴露 JDBC 端口），要接的话需要在 spark-master 或额外容器里�?`start-thriftserver.sh` 并指�?`hive-metastore:9083`；这属于离线侧（`data-processing-platform-service`），不在 `data-processing-platform-service` 范围内�?
想先做哪一步告诉我，我再动代码�?