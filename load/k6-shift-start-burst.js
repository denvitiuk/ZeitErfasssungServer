import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const WORKER_COUNT = Number(__ENV.K6_WORKER_COUNT || 25);
const WORKER_PASSWORD = __ENV.K6_WORKER_PASSWORD || 'WorkerLoad2026!';
const DATE = __ENV.K6_DATE || new Date().toISOString().slice(0, 10);
const MODE = (__ENV.K6_MODE || 'realistic').toLowerCase();

const startSuccessRate = new Rate('shift_start_success_rate');
const startFailureRate = new Rate('shift_start_failure_rate');
const startDuration = new Trend('shift_start_duration', true);
const unexpectedStartResponses = new Counter('unexpected_start_responses');
const preflightStops = new Counter('preflight_stops');

const allowedLocalTargets = new Set([
  'localhost',
  '127.0.0.1',
  'host.docker.internal',
]);

const targetHost = BASE_URL
  .replace(/^https?:\/\//, '')
  .split('/')[0]
  .split(':')[0];

if (!allowedLocalTargets.has(targetHost) && __ENV.K6_ALLOW_REMOTE !== 'I_UNDERSTAND') {
  throw new Error(
    `Refusing to run against ${BASE_URL}. ` +
      'Use localhost/test infrastructure. For an explicitly authorized remote test, set K6_ALLOW_REMOTE=I_UNDERSTAND.'
  );
}

if (!Number.isInteger(WORKER_COUNT) || WORKER_COUNT < 1 || WORKER_COUNT > 100) {
  throw new Error('K6_WORKER_COUNT must be an integer from 1 to 100.');
}

if (!['realistic', 'cleanup', 'pure'].includes(MODE)) {
  throw new Error("K6_MODE must be one of: realistic, cleanup, pure.");
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],

  scenarios: {
    shift_start_burst: {
      executor: 'per-vu-iterations',
      vus: WORKER_COUNT,
      iterations: 1,
      maxDuration: '45s',
      gracefulStop: '10s',
    },
  },

  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1500', 'p(99)<2500'],
    shift_start_success_rate: ['rate==1'],
    shift_start_failure_rate: ['rate==0'],
    shift_start_duration: ['p(95)<1200', 'p(99)<2000'],
  },
};

function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/json',
  };
}

function login(email) {
  const response = http.post(
    `${BASE_URL}/login`,
    JSON.stringify({ email, password: WORKER_PASSWORD }),
    {
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      tags: { name: 'POST /login [burst setup]' },
    }
  );

  const ok = check(response, {
    'worker login returns 200': (r) => r.status === 200,
  });

  if (!ok) {
    throw new Error(
      `Login failed for ${email}: status=${response.status}, body=${response.body}`
    );
  }

  const payload = response.json();
  if (!payload || !payload.token) {
    throw new Error(`Login response has no token for ${email}: ${response.body}`);
  }

  return payload.token;
}

function stopAnyActiveSessions(token) {
  const history = http.get(
    `${BASE_URL}/tracking/me/sessions?date=${DATE}`,
    {
      headers: authHeaders(token),
      tags: { name: 'GET /tracking/me/sessions [burst preflight]' },
    }
  );

  if (history.status !== 200) {
    throw new Error(
      `Burst preflight could not load sessions: status=${history.status}, body=${history.body}`
    );
  }

  const sessions = history.json();
  if (!Array.isArray(sessions)) {
    throw new Error(`Burst preflight expected an array of sessions, got: ${history.body}`);
  }

  for (const session of sessions) {
    if (!session || !session.isActive || !session.sessionId) continue;

    const stop = http.post(
      `${BASE_URL}/tracking/sessions/${session.sessionId}/stop`,
      null,
      {
        headers: authHeaders(token),
        tags: { name: 'POST /tracking/sessions/{id}/stop [burst preflight]' },
      }
    );

    if (stop.status !== 200) {
      throw new Error(
        `Burst preflight could not stop active session ${session.sessionId}: ` +
          `status=${stop.status}, body=${stop.body}`
      );
    }

    preflightStops.add(1);
  }
}

export function setup() {
  const workers = [];

  for (let index = 1; index <= WORKER_COUNT; index += 1) {
    const email = `load-worker-${String(index).padStart(2, '0')}@test.com`;
    const token = login(email);

    if (MODE === 'realistic' || MODE === 'cleanup') {
      // Cleanup mode makes the following pure burst deterministic.
      // Realistic mode intentionally models the normal cleanup + start workflow.
      stopAnyActiveSessions(token);
    }

    workers.push({ email, token });
  }

  return { workers, mode: MODE };
}

export default function (data) {
  if (data.mode === 'cleanup') {
    // One no-op iteration per VU after setup. The cleanup work has already completed in setup().
    return;
  }

  const worker = data.workers[__VU - 1];

  if (!worker) {
    throw new Error(`No worker credentials mapped for VU ${__VU}.`);
  }

  const response = http.post(
    `${BASE_URL}/tracking/sessions/start`,
    null,
    {
      headers: authHeaders(worker.token),
      tags: { name: 'POST /tracking/sessions/start [burst]' },
    }
  );

  startDuration.add(response.timings.duration);

  const isExpectedResponse = check(response, {
    'shift start returns 200': (r) => r.status === 200,
    'shift start is not 5xx': (r) => r.status < 500,
    'shift start returns session id': (r) => {
      try {
        const body = r.json();
        return Boolean(body && body.sessionId);
      } catch (_) {
        return false;
      }
    },
  });

  startSuccessRate.add(isExpectedResponse);
  startFailureRate.add(!isExpectedResponse);

  if (!isExpectedResponse) {
    unexpectedStartResponses.add(1);
    console.error(
      JSON.stringify({
        vu: __VU,
        worker: worker.email,
        status: response.status,
        body: response.body && response.body.slice(0, 500),
      })
    );
  }

  // Only one request per worker: this is a synchronized start-of-shift burst, not sustained traffic.
  sleep(0.1);
}

export function handleSummary(data) {
  return {
    'load/results/k6-shift-start-burst-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const metrics = data.metrics;
  const get = (name, field) => metrics[name]?.values?.[field] ?? 'n/a';

  return `
================ SHIFT START BURST SUMMARY ================

Target: ${BASE_URL}
Workers in burst:       ${WORKER_COUNT}
Mode:                   ${MODE}

HTTP requests:          ${get('http_reqs', 'count')}
Request failure rate:   ${get('http_req_failed', 'rate')}
HTTP p95:               ${get('http_req_duration', 'p(95)')} ms
HTTP p99:               ${get('http_req_duration', 'p(99)')} ms

Start p95:              ${get('shift_start_duration', 'p(95)')} ms
Start p99:              ${get('shift_start_duration', 'p(99)')} ms
Start success rate:     ${get('shift_start_success_rate', 'rate')}
Start failure rate:     ${get('shift_start_failure_rate', 'rate')}
Unexpected starts:      ${get('unexpected_start_responses', 'count')}
Preflight stale stops:  ${get('preflight_stops', 'count')}

============================================================
`;
}
