package com.company.dataops.console.service.monitoring;

import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

/**
 * Thin read-only wrapper over the remote Docker Engine API, reached through
 * the same SSH tunnel as every other bigdata service (see
 * platform.bigdata.docker-api-url) - the daemon's TCP listener is bound to
 * 127.0.0.1 on the remote host only, never exposed on a public interface.
 * Only ever issues GET requests; nothing here can create, stop, or remove a
 * container.
 */
@Component
public class DockerApiClient {
    private final RestTemplate restTemplate = new RestTemplate();
    private final String baseUrl;

    public DockerApiClient(@Value("${platform.bigdata.docker-api-url:http://localhost:2375}") String baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Summary list of every container (running or stopped) - just enough to get each one's ID for a follow-up inspect() call. */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> listContainers() {
        return restTemplate.getForObject(baseUrl + "/containers/json?all=true", List.class);
    }

    /** Full inspect detail for one container - the only call that carries RestartCount and State.StartedAt. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> inspectContainer(String containerId) {
        return restTemplate.getForObject(baseUrl + "/containers/{id}/json", Map.class, containerId);
    }
}
