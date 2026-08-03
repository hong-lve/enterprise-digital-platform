package com.company.flinkjobs.cdcmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.company.flinkjobs.cdcmirror.CdcMirrorSupport.DebeziumAvroRowExtractor;
import io.confluent.kafka.schemaregistry.client.MockSchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.SchemaBuilder;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.junit.jupiter.api.Test;

/**
 * Pins CdcMirrorSupport's Debezium-Avro decoding against real Confluent-wire-format
 * bytes (magic byte + schema id + Avro binary), encoded/decoded via a
 * MockSchemaRegistryClient so no real Kafka/Schema Registry is needed. This is the
 * exact class of bug the session's Avro regression was: decoding logic silently
 * reverted to something that doesn't understand this wire format. A test that only
 * calls the extractor with a hand-built Map (bypassing real Avro bytes entirely)
 * would NOT have caught that regression - it has to go through actual encode+decode.
 */
class DebeziumAvroRowExtractorTest {
    private static final String TOPIC = "mysqldemo.cdc_demo.test_orders_mysql";

    private static final Schema AFTER_SCHEMA = SchemaBuilder.record("After").fields()
        .name("id").type().stringType().noDefault()
        .name("order_no").type().stringType().noDefault()
        .name("amount").type().stringType().noDefault()
        .name("status").type().stringType().noDefault()
        .name("created_at").type().stringType().noDefault()
        .endRecord();

    private static final Schema ENVELOPE_SCHEMA = SchemaBuilder.record("Envelope").fields()
        .name("op").type().stringType().noDefault()
        .name("after").type(AFTER_SCHEMA).noDefault()
        .endRecord();

    // Shared by every test in this class - a schema id registered against one
    // MockSchemaRegistryClient instance means nothing to another, so encode()
    // and decode() must always agree on the same one.
    private final SchemaRegistryClient registryClient = new MockSchemaRegistryClient();

    @Test
    void decodesInsertEnvelopeIntoLowercasedFieldMap() {
        DebeziumAvroRowExtractor extractor = newExtractor();
        byte[] bytes = encode(envelope("c", after("1001", "ORD-1001", "199.90", "PAID", "1700000000000")));

        Map<String, String> row = extractor.map(bytes);

        assertEquals("1001", row.get("id"));
        assertEquals("ORD-1001", row.get("order_no"));
        assertEquals("199.90", row.get("amount"));
        assertEquals("PAID", row.get("status"));
        assertEquals("1700000000000", row.get("created_at"));
    }

    @Test
    void decodesUpdateEnvelopeSameAsInsert() {
        DebeziumAvroRowExtractor extractor = newExtractor();
        byte[] bytes = encode(envelope("u", after("1001", "ORD-1001", "249.90", "SHIPPED", "1700000005000")));

        Map<String, String> row = extractor.map(bytes);

        assertEquals("249.90", row.get("amount"));
        assertEquals("SHIPPED", row.get("status"));
    }

    @Test
    void skipsDeleteEnvelope() {
        DebeziumAvroRowExtractor extractor = newExtractor();
        byte[] bytes = encode(envelope("d", after("1001", "ORD-1001", "199.90", "PAID", "1700000000000")));

        assertNull(extractor.map(bytes));
    }

    @Test
    void skipsTombstoneNullValue() {
        assertNull(newExtractor().map(null));
    }

    @Test
    void skipsMalformedBytesInsteadOfThrowing() {
        assertNull(newExtractor().map(new byte[] {1, 2, 3}));
    }

    private DebeziumAvroRowExtractor newExtractor() {
        KafkaAvroDeserializer deserializer = new KafkaAvroDeserializer(registryClient);
        deserializer.configure(Map.of(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://test",
            KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, false), false);
        return new DebeziumAvroRowExtractor(TOPIC, deserializer);
    }

    private byte[] encode(GenericRecord record) {
        KafkaAvroSerializer serializer = new KafkaAvroSerializer(registryClient);
        serializer.configure(Map.of(KafkaAvroDeserializerConfig.SCHEMA_REGISTRY_URL_CONFIG, "mock://test"), false);
        return serializer.serialize(TOPIC, record);
    }

    private static GenericRecord envelope(String op, GenericRecord after) {
        GenericRecord envelope = new GenericData.Record(ENVELOPE_SCHEMA);
        envelope.put("op", op);
        envelope.put("after", after);
        return envelope;
    }

    private static GenericRecord after(String id, String orderNo, String amount, String status, String createdAt) {
        GenericRecord after = new GenericData.Record(AFTER_SCHEMA);
        after.put("id", id);
        after.put("order_no", orderNo);
        after.put("amount", amount);
        after.put("status", status);
        after.put("created_at", createdAt);
        return after;
    }
}
