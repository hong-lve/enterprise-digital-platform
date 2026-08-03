package com.company.dataops.console.service.kafka;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.springframework.stereotype.Component;

/**
 * Best-effort field-level diff between two versions of a Debezium Avro
 * envelope schema - Schema Registry's own /compatibility endpoint (see
 * SchemaRegistryClient) already enforces compatibility correctly regardless
 * of schema shape, so it stays the sole authority on "is this safe"; this
 * class only produces the human-readable summary of *what* changed (which
 * fields disappeared, changed type, or got added). If a schema isn't shaped
 * the way Debezium's AvroConverter normally shapes it, this degrades to an
 * empty diff rather than throwing, since nothing safety-critical depends on
 * it succeeding.
 */
@Component
public class AvroSchemaDiffService {

    public FieldDiff diff(String oldSchemaJson, String newSchemaJson) {
        Map<String, Schema> oldFields = fieldsOf(oldSchemaJson);
        Map<String, Schema> newFields = fieldsOf(newSchemaJson);

        List<String> removed = oldFields.keySet().stream().filter(name -> !newFields.containsKey(name)).toList();
        List<String> added = newFields.keySet().stream().filter(name -> !oldFields.containsKey(name)).toList();
        List<FieldTypeChange> typeChanges = oldFields.keySet().stream()
            .filter(newFields::containsKey)
            .filter(name -> !typeSignature(oldFields.get(name)).equals(typeSignature(newFields.get(name))))
            .map(name -> new FieldTypeChange(name, typeSignature(oldFields.get(name)), typeSignature(newFields.get(name))))
            .toList();

        return new FieldDiff(removed, added, typeChanges);
    }

    /**
     * Debezium's AvroConverter wraps the actual row shape in an envelope
     * ({before, after, source, op, ts_ms, transaction, ...}) where "after" is
     * a union of null and the real table record - unwraps to that record's
     * fields. Returns an empty map if the schema isn't shaped this way, same
     * degrade-quietly reasoning as the class javadoc.
     */
    private Map<String, Schema> fieldsOf(String schemaJson) {
        try {
            Schema envelope = new Schema.Parser().parse(schemaJson);
            Schema.Field afterField = envelope.getField("after");
            if (afterField == null) {
                return Map.of();
            }
            Schema afterUnion = afterField.schema();
            Schema afterRecord = afterUnion.getType() == Schema.Type.UNION
                ? afterUnion.getTypes().stream().filter(candidate -> candidate.getType() == Schema.Type.RECORD).findFirst().orElse(null)
                : afterUnion;
            if (afterRecord == null || afterRecord.getType() != Schema.Type.RECORD) {
                return Map.of();
            }
            Map<String, Schema> fields = new LinkedHashMap<>();
            for (Schema.Field field : afterRecord.getFields()) {
                fields.put(field.name(), field.schema());
            }
            return fields;
        } catch (Exception exception) {
            return Map.of();
        }
    }

    private String typeSignature(Schema schema) {
        if (schema.getType() == Schema.Type.UNION) {
            return schema.getTypes().stream().map(Schema::getType).map(Enum::name).sorted().reduce((a, b) -> a + "|" + b).orElse("UNION");
        }
        return schema.getType().name();
    }

    public record FieldTypeChange(String field, String oldType, String newType) {
    }

    public record FieldDiff(List<String> removedFields, List<String> addedFields, List<FieldTypeChange> typeChanges) {
        public boolean isEmpty() {
            return removedFields.isEmpty() && addedFields.isEmpty() && typeChanges.isEmpty();
        }
    }
}
