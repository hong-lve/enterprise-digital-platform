# API Gateway Service

Company-facing edge service for data APIs.

Responsibilities:

- Route `/openapi/**` traffic to `data-service-platform-service`.
- Reject incomplete and stale signed requests at the edge.
- Delegate authoritative HMAC verification, nonce replay protection, API authorization and QPS limits to the data-service runtime.
- Never expose `/data-service-admin/**` through the public gateway.
- Expose gateway health and route visibility through Actuator.

Local defaults:

- Port: `8086`
- Data service target: `DATA_SERVICE_URL`, default `http://localhost:8087`

Run:

```bash
mvn spring-boot:run
```

Example:

```bash
curl http://localhost:8086/openapi/health
```
