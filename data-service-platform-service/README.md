# Data Service Platform Service

Backend runtime and management service for externally consumable data APIs.

Responsibilities:

- Register serviceable datasets backed by curated result tables.
- Register API definitions bound to datasets.
- Authorize app/API access and record call logs.
- Execute safe, parameterized and paginated SELECT queries against managed serving datasets.
- Manage calling applications, one-time secrets, API grants and QPS policies.
- Enforce HMAC-SHA256 signatures, timestamp windows and nonce replay protection.
- Persist success, failure, authorization and throttling events in the call audit log.

Local defaults:

- Port: `8087`
- Metadata database: `data_service_db` on the existing local MySQL at `localhost:13306`

Current scaffold endpoints:

- `GET /openapi/health`
- `GET /openapi/**`

Public calls must use the signing protocol documented in
[`../docs/openapi-signing.md`](../docs/openapi-signing.md). Administrative endpoints
must not be exposed by the public API gateway.
- `GET /data-service-admin/datasets`
- `POST /data-service-admin/datasets`
- `GET /data-service-admin/apis`
- `POST /data-service-admin/apis`

Run:

```bash
mvn spring-boot:run
```
