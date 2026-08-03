import crypto from 'k6/crypto';
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const baseUrl = __ENV.BASE_URL || 'http://127.0.0.1:8087';
const apiPath = __ENV.API_PATH || '/openapi/demo/orders';
const rawQuery = __ENV.RAW_QUERY || 'page=1&pageSize=20';
const appKey = __ENV.APP_KEY || '';
const appSecret = __ENV.APP_SECRET || '';
const secretVersion = __ENV.SECRET_VERSION || '1';
const errorRate = new Rate('business_errors');
const apiLatency = new Trend('business_latency', true);

export const options = {
  scenarios: {
    steady_capacity: {
      executor: 'ramping-arrival-rate',
      startRate: Number(__ENV.START_RPS || 10),
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 50),
      maxVUs: Number(__ENV.MAX_VUS || 500),
      stages: [
        { target: Number(__ENV.TARGET_RPS || 100), duration: __ENV.RAMP_DURATION || '2m' },
        { target: Number(__ENV.TARGET_RPS || 100), duration: __ENV.HOLD_DURATION || '5m' },
        { target: 0, duration: '30s' }
      ]
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    business_errors: ['rate<0.01'],
    http_req_duration: ['p(95)<500', 'p(99)<1000'],
    dropped_iterations: ['count==0']
  }
};

function signedHeaders(method, path, query, body) {
  const timestamp = String(Date.now());
  const nonce = `${__VU}_${__ITER}_${timestamp}_${Math.random().toString(36).slice(2, 12)}`;
  const bodyHash = crypto.sha256(body || '', 'hex');
  const canonical = [method, path, query, timestamp, nonce, bodyHash].join('\n');
  return {
    'Content-Type': 'application/json',
    'X-App-Key': appKey,
    'X-Secret-Version': secretVersion,
    'X-Timestamp': timestamp,
    'X-Nonce': nonce,
    'X-Signature': crypto.hmac('sha256', appSecret, canonical, 'hex')
  };
}
export default function () {
  if (!appKey || !appSecret) {
    throw new Error('APP_KEY and APP_SECRET are required');
  }
  const started = Date.now();
  const response = http.get(
    `${baseUrl}${apiPath}?${rawQuery}`,
    { headers: signedHeaders('GET', apiPath, rawQuery, '') }
  );
  apiLatency.add(Date.now() - started);
  const ok = check(response, {
    'status is 200': (r) => r.status === 200,
    'response is platform envelope': (r) => {
      try {
        return r.json('code') === 0;
      } catch (_) {
        return false;
      }
    }
  });
  errorRate.add(!ok);
  sleep(Number(__ENV.THINK_TIME_SECONDS || 0));
}
