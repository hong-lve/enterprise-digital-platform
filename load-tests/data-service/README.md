# Data service load tests

These k6 scenarios call the real signed OpenAPI endpoint, so authentication,
nonce protection, Redis rate limiting, routing, cache, SQL execution and call
logging are measured together.

```powershell
$env:BASE_URL='http://127.0.0.1:8087'
$env:API_PATH='/openapi/orders/query'
$env:RAW_QUERY='page=1&pageSize=20&customerId=10001'
$env:APP_KEY='replace-with-app-key'
$env:APP_SECRET='replace-with-app-secret'
$env:TARGET_RPS='100'
k6 run --summary-export capacity-summary.json openapi-capacity.js
```

Run `openapi-spike.js` after the steady test to verify recovery from a sudden
traffic burst. A capacity result is acceptable only when k6 thresholds pass,
pod CPU/memory remain below 70%, the database pool is not exhausted, Redis
latency stays stable, and the SLO alert/recovery events match the test window.
