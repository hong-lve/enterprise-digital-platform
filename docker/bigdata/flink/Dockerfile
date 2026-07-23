# syntax=docker/dockerfile:1
#
# Bakes the 6 connector/driver jars that docker-compose.yml bind-mounts into
# the jobmanager/taskmanager/sql-gateway containers today into a single
# image - K8s has no equivalent of a host-path bind mount shared across
# nodes, so these have to live inside the image instead.
#
# Build from the docker/bigdata/ directory (not this flink/ subdirectory),
# so the build context can reach ../flink-lib/:
#   docker build -f docker/bigdata/flink/Dockerfile -t data-platform-flink:1.19-java17 docker/bigdata/

FROM flink:1.19-java17
COPY flink-lib/flink-sql-connector-kafka-3.3.0-1.19.jar /opt/flink/lib/
COPY flink-lib/clickhouse-jdbc-dialect-0.1.0.jar /opt/flink/lib/
COPY flink-lib/flink-doris-connector-1.19-1.6.2.jar /opt/flink/lib/
COPY flink-lib/mysql-connector-j-8.3.0.jar /opt/flink/lib/
COPY flink-lib/ojdbc11-23.9.0.25.07.jar /opt/flink/lib/
COPY flink-lib/redis-table-sink-0.1.0.jar /opt/flink/lib/
