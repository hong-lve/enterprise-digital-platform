package com.company.flinkjobs.cdcmirror;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CdcMirrorSupportTest {

    @Test
    void returnsValueWhenKeyFoundMidArray() {
        String[] args = {"--kafka-bootstrap", "kafka:9092", "--topic", "my-topic"};
        assertEquals("kafka:9092", CdcMirrorSupport.arg(args, "kafka-bootstrap", "default"));
        assertEquals("my-topic", CdcMirrorSupport.arg(args, "topic", "default"));
    }

    @Test
    void returnsDefaultWhenKeyAbsent() {
        String[] args = {"--topic", "my-topic"};
        assertEquals("default", CdcMirrorSupport.arg(args, "sink-host", "default"));
    }

    @Test
    void returnsDefaultWhenKeyIsLastElementWithNoFollowingValue() {
        String[] args = {"--topic", "my-topic", "--sink-host"};
        assertEquals("default", CdcMirrorSupport.arg(args, "sink-host", "default"));
    }

    @Test
    void returnsDefaultForEmptyArgsArray() {
        assertEquals("default", CdcMirrorSupport.arg(new String[0], "topic", "default"));
    }

    @Test
    void returnsFirstMatchWhenKeyDuplicated() {
        String[] args = {"--topic", "first", "--topic", "second"};
        assertEquals("first", CdcMirrorSupport.arg(args, "topic", "default"));
    }

    @Test
    void doesNotMatchKeyAsSubstringOfAnotherKey() {
        String[] args = {"--sink-host-extra", "value"};
        assertEquals("default", CdcMirrorSupport.arg(args, "sink-host", "default"));
    }
}
