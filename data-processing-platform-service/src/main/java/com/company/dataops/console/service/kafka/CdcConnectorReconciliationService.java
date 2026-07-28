package com.company.dataops.console.service.kafka;

import com.company.dataops.console.entity.CdcSourceEntity;
import com.company.dataops.console.mapper.CdcSourceMapper;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Mirrors FlinkClusterReconciliationService's reasoning for the Kafka
 * Connect side of the platform: a cdc_source row's connector can outlive
 * its DB row the same way a Flink job can outlive its flink_stream_job/
 * flink_sql_job row (delete()'s kafkaConnectClient.delete() call is
 * best-effort and swallows failures so a Kafka-Connect-unreachable delete
 * doesn't block removing the local row) - and a connector left running
 * with nothing to disable it keeps capturing binlog and producing to Kafka
 * indefinitely, with no UI anywhere showing it exists.
 */
@Component
public class CdcConnectorReconciliationService {
    private final KafkaConnectClient kafkaConnectClient;
    private final CdcSourceMapper cdcSourceMapper;

    public CdcConnectorReconciliationService(KafkaConnectClient kafkaConnectClient, CdcSourceMapper cdcSourceMapper) {
        this.kafkaConnectClient = kafkaConnectClient;
        this.cdcSourceMapper = cdcSourceMapper;
    }

    public List<OrphanedConnectorView> listOrphanedConnectors() {
        Set<String> knownConnectorNames = new HashSet<>();
        cdcSourceMapper.selectList(null).stream()
            .map(CdcSourceEntity::getConnectorName)
            .filter(name -> name != null)
            .forEach(knownConnectorNames::add);

        return kafkaConnectClient.listConnectorNames().stream()
            .filter(name -> !knownConnectorNames.contains(name))
            .map(name -> {
                KafkaConnectClient.ConnectorStatus status = kafkaConnectClient.status(name);
                return new OrphanedConnectorView(name, status.state(), status.message());
            })
            .toList();
    }

    // Unlike CdcSourceController.delete()'s best-effort call to the same
    // KafkaConnectClient.delete() (which must not block removing a DB row
    // just because Kafka Connect happens to be unreachable), this is the
    // only thing this action does - a silent failure here would leave the
    // user believing an orphan is gone when it's still running.
    public void delete(String connectorName) {
        try {
            kafkaConnectClient.delete(connectorName);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "删除 connector 失败：" + exception.getMessage());
        }
    }

    public record OrphanedConnectorView(String connectorName, String state, String message) {
    }
}
