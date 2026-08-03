import exec from 'k6/execution';
import { options as baseOptions, default as capacityScenario } from './openapi-capacity.js';

export const options = {
  ...baseOptions,
  scenarios: {
    spike: {
      executor: 'ramping-arrival-rate',
      startRate: 10,
      timeUnit: '1s',
      preAllocatedVUs: Number(__ENV.PRE_ALLOCATED_VUS || 100),
      maxVUs: Number(__ENV.MAX_VUS || 1000),
      stages: [
        { target: 20, duration: '30s' },
        { target: Number(__ENV.SPIKE_RPS || 500), duration: '10s' },
        { target: Number(__ENV.SPIKE_RPS || 500), duration: '1m' },
        { target: 20, duration: '20s' },
        { target: 0, duration: '10s' }
      ]
    }
  },
  thresholds: {
    http_req_failed: ['rate<0.05'],
    http_req_duration: ['p(95)<1000', 'p(99)<2000']
  }
};

export default function () {
  capacityScenario();
  if (exec.scenario.iterationInTest === 0) {
    console.log(`spike test started at ${new Date().toISOString()}`);
  }
}
