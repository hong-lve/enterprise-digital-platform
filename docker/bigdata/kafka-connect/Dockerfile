# syntax=docker/dockerfile:1
#
# debezium/connect doesn't bundle Confluent's Avro converter - Debezium
# itself is converter-agnostic (it hands Kafka Connect a Struct and lets
# whatever key.converter/value.converter is configured serialize it), and
# io.confluent:kafka-connect-avro-converter isn't on Maven Central, only on
# Confluent's own repo. This build stage resolves it (and its transitive
# deps: kafka-avro-serializer, kafka-schema-registry-client, common-config/
# common-utils, avro) via avro-converter-pom.xml, then copies the resulting
# jars into a plugin.path subdirectory - Kafka Connect's plugin scanner
# picks up Converter implementations from there the same way it does
# Connector implementations, giving key.converter/value.converter an
# isolated classloader to load AvroConverter from.
#
# Build from the docker/bigdata/ directory (matching flink/Dockerfile's own
# convention), so this doesn't need its own build context:
#   docker build -f docker/bigdata/kafka-connect/Dockerfile -t data-platform-kafka-connect:3.0.0.Final docker/bigdata/kafka-connect/

FROM maven:3.9-eclipse-temurin-17 AS avro-converter
WORKDIR /build
COPY avro-converter-pom.xml pom.xml
RUN mvn -q dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=/build/plugin

FROM debezium/connect:3.0.0.Final
COPY --from=avro-converter /build/plugin/*.jar /kafka/connect/avro-converter/
