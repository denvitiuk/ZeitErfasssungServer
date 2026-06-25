import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const WORKER_COUNT = Number(__ENV.K6_WORKER_COUNT || 25);
const WORKER_PASSWORD = __ENV.K6_WORKER_PASSWORD || 'WorkerLoad2026!';
const DURATION = __ENV.K6_DURATION || '30s';
const POINT_INTERVAL_SECONDS = Number(__ENV.K6_POINT_INTERVAL_SECONDS || 1);
const CLEANUP = (__ENV.K6_CLEANUP || 'false').toLowerCase() === 'true';

const pointSuccessRate = new Rate('tracking_point_success_rate');
const pointFailureRate = new Rate('tracking_point_failure_rate');
const pointDuration = new Trend('tracking_point_duration', true);
const unexpectedPointResponses = new Counter('unexpected_point_responses');
const preparedExistingSessions = new Counter('prepared_existing_sessions');
const preparedStartedSessions = new Counter('prepared_started_sessions');

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

if (!Number.isFinite(POINT_INTERVAL_SECONDS) || POINT_INTERVAL_SECONDS < 0.2) {
  throw new Error('K6_POINT_INTERVAL_SECONDS must be at least 0.2 seconds.');
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],

  scenarios: {
    tracking_points: {
      executor: 'constant-vus',
      vus: WORKER_COUNT,
      duration: DURATION,
      gracefulStop: '10s',
    },
  },

  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1200', 'p(99)<2000'],
    tracking_point_success_rate: ['rate==1'],
    tracking_point_failure_rate: ['rate==0'],
    tracking_point_duration: ['p(95)<900', 'p(99)<1500'],
  },
};

function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/json',
    'Content-Type': 'application/json',
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
      tags: { name: 'POST /login [points setup]' },
    }
  );

  const ok = check(response, {
    'worker login returns 200': (r) => r.status === 200,
  });

  if (!ok) {
    throw new Error(`Login failed for ${email}: status=${response.status}, body=${response.body}`);
  }

  const body = response.json();
  if (!body?.token) {
    throw new Error(`Login response has no token for ${email}: ${response.body}`);
  }

  return body.token;
}

function loadActiveSessionId(token) {
  const today = new Date().toISOString().slice(0, 10);
  const response = http.get(
    `${BASE_URL}/tracking/me/sessions?date=${today}`,
    {
      headers: authHeaders(token),
      tags: { name: 'GET /tracking/me/sessions [points setup]' },
    }
  );

  if (response.status !== 200) {
    throw new Error(`Could not load worker sessions: status=${response.status}, body=${response.body}`);
  }

  const sessions = response.json();
  if (!Array.isArray(sessions)) {
    throw new Error(`Expected a sessions array, got: ${response.body}`);
  }

  const active = sessions.find((session) => session?.isActive && session?.sessionId);
  return active?.sessionId || null;
}

function startSessionForPointsTest(token, email) {
  const response = http.post(
    `${BASE_URL}/tracking/sessions/start`,
    null,
    {
      headers: authHeaders(token),
      tags: { name: 'POST /tracking/sessions/start [points setup]' },
    }
  );

  const ok = check(response, {
    'setup start returns 200': (r) => r.status === 200,
  });

  if (!ok) {
    throw new Error(
      `Could not prepare active session for ${email}: status=${response.status}, body=${response.body}`
    );
  }

  const body = response.json();
  if (!body?.sessionId) {
    throw new Error(`Setup start response has no sessionId for ${email}: ${response.body}`);
  }

  return body.sessionId;
}

function pointPayload(worker, iteration) {
  const workerOffset = worker.index * 0.0001;
  const drift = iteration * 0.000001;

  return {
    sessionId: worker.sessionId,
    lat: 52.520008 + workerOffset + drift,
    lon: 13.404954 + workerOffset + drift,
    speedMps: 1.4,
    headingDeg: 90,
    tsEpochSeconds: Math.floor(Date.now() / 1000),
  };
}

export function setup() {
  const workers = [];

  for (let index = 1; index <= WORKER_COUNT; index += 1) {
    const email = `load-worker-${String(index).padStart(2, '0')}@test.com`;
    const token = login(email);
    let sessionId = loadActiveSessionId(token);

    if (sessionId) {
      preparedExistingSessions.add(1);
    } else {
      sessionId = startSessionForPointsTest(token, email);
      preparedStartedSessions.add(1);
    }

    workers.push({ index, email, token, sessionId });
  }

  return { workers };
}

export default function (data) {
  const worker = data.workers[__VU - 1];

  if (!worker) {
    throw new Error(`No prepared worker mapped for VU ${__VU}.`);
  }

  const response = http.post(
    `${BASE_URL}/tracking/points`,
    JSON.stringify(pointPayload(worker, __ITER)),
    {
      headers: authHeaders(worker.token),
      tags: { name: 'POST /tracking/points [load]' },
    }
  );

  pointDuration.add(response.timings.duration);

  const isExpectedResponse = check(response, {
    'tracking point returns 200': (r) => r.status === 200,
    'tracking point is not 5xx': (r) => r.status < 500,
  });

  pointSuccessRate.add(isExpectedResponse);
  pointFailureRate.add(!isExpectedResponse);

  if (!isExpectedResponse) {
    unexpectedPointResponses.add(1);
    console.error(
      JSON.stringify({
        vu: __VU,
        worker: worker.email,
        sessionId: worker.sessionId,
        status: response.status,
        body: response.body && response.body.slice(0, 500),
      })
    );
  }

  sleep(POINT_INTERVAL_SECONDS);
}

export function teardown(data) {
  if (!CLEANUP) {
    return;
  }

  for (const worker of data.workers) {
    http.post(
      `${BASE_URL}/tracking/sessions/${worker.sessionId}/stop`,
      null,
      {
        headers: authHeaders(worker.token),
        tags: { name: 'POST /tracking/sessions/{id}/stop [points teardown]' },
      }
    );
  }
}

export function handleSummary(data) {
  return {
    'load/results/k6-tracking-points-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const metrics = data.metrics;
  const get = (name, field) => metrics[name]?.values?.[field] ?? 'n/a';

  return `
================ TRACKING POINTS LOAD SUMMARY =============

Target: ${BASE_URL}
Workers:                ${WORKER_COUNT}
Duration:               ${DURATION}
Point interval:         ${POINT_INTERVAL_SECONDS} sec

HTTP requests:          ${get('http_reqs', 'count')}
Request failure rate:   ${get('http_req_failed', 'rate')}
HTTP p95:               ${get('http_req_duration', 'p(95)')} ms
HTTP p99:               ${get('http_req_duration', 'p(99)')} ms

Point p95:              ${get('tracking_point_duration', 'p(95)')} ms
Point p99:              ${get('tracking_point_duration', 'p(99)')} ms
Point success rate:     ${get('tracking_point_success_rate', 'rate')}
Point failure rate:     ${get('tracking_point_failure_rate', 'rate')}
Unexpected points:      ${get('unexpected_point_responses', 'count')}
Prepared existing:      ${get('prepared_existing_sessions', 'count')}
Prepared new sessions:  ${get('prepared_started_sessions', 'count')}
Cleanup active sessions:${CLEANUP}

============================================================
`;
}