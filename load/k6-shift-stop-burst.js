

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const WORKER_COUNT = Number(__ENV.K6_WORKER_COUNT || 25);
const WORKER_PASSWORD = __ENV.K6_WORKER_PASSWORD || 'WorkerLoad2026!';
const DATE = __ENV.K6_DATE || new Date().toISOString().slice(0, 10);

const stopSuccessRate = new Rate('shift_stop_success_rate');
const stopFailureRate = new Rate('shift_stop_failure_rate');
const stopDuration = new Trend('shift_stop_duration', true);
const unexpectedStopResponses = new Counter('unexpected_stop_responses');
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

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],

  scenarios: {
    shift_stop_burst: {
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
    shift_stop_success_rate: ['rate==1'],
    shift_stop_failure_rate: ['rate==0'],
    shift_stop_duration: ['p(95)<1200', 'p(99)<2000'],
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
      tags: { name: 'POST /login [stop setup]' },
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
  const response = http.get(
    `${BASE_URL}/tracking/me/sessions?date=${DATE}`,
    {
      headers: authHeaders(token),
      tags: { name: 'GET /tracking/me/sessions [stop setup]' },
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

function startSessionForStopTest(token, email) {
  const response = http.post(
    `${BASE_URL}/tracking/sessions/start`,
    null,
    {
      headers: authHeaders(token),
      tags: { name: 'POST /tracking/sessions/start [stop setup]' },
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

export function setup() {
  const workers = [];

  for (let index = 1; index <= WORKER_COUNT; index += 1) {
    const email = `load-worker-${String(index).padStart(2, '0')}@test.com`;
    const token = login(email);

    let sessionId = loadActiveSessionId(token);

    if (sessionId) {
      preparedExistingSessions.add(1);
    } else {
      sessionId = startSessionForStopTest(token, email);
      preparedStartedSessions.add(1);
    }

    workers.push({ email, token, sessionId });
  }

  return { workers };
}

export default function (data) {
  const worker = data.workers[__VU - 1];

  if (!worker) {
    throw new Error(`No prepared worker mapped for VU ${__VU}.`);
  }

  const response = http.post(
    `${BASE_URL}/tracking/sessions/${worker.sessionId}/stop`,
    null,
    {
      headers: authHeaders(worker.token),
      tags: { name: 'POST /tracking/sessions/{id}/stop [burst]' },
    }
  );

  stopDuration.add(response.timings.duration);

  const isExpectedResponse = check(response, {
    'shift stop returns 200': (r) => r.status === 200,
    'shift stop is not 5xx': (r) => r.status < 500,
    'shift stop returns session id': (r) => {
      try {
        const body = r.json();
        return Boolean(body && body.sessionId);
      } catch (_) {
        return false;
      }
    },
  });

  stopSuccessRate.add(isExpectedResponse);
  stopFailureRate.add(!isExpectedResponse);

  if (!isExpectedResponse) {
    unexpectedStopResponses.add(1);
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

  sleep(0.1);
}

export function handleSummary(data) {
  return {
    'load/results/k6-shift-stop-burst-summary.json': JSON.stringify(data, null, 2),
    stdout: textSummary(data),
  };
}

function textSummary(data) {
  const metrics = data.metrics;
  const get = (name, field) => metrics[name]?.values?.[field] ?? 'n/a';

  return `
================ SHIFT STOP BURST SUMMARY =================

Target: ${BASE_URL}
Workers in burst:       ${WORKER_COUNT}

HTTP requests:          ${get('http_reqs', 'count')}
Request failure rate:   ${get('http_req_failed', 'rate')}
HTTP p95:               ${get('http_req_duration', 'p(95)')} ms
HTTP p99:               ${get('http_req_duration', 'p(99)')} ms

Stop p95:               ${get('shift_stop_duration', 'p(95)')} ms
Stop p99:               ${get('shift_stop_duration', 'p(99)')} ms
Stop success rate:      ${get('shift_stop_success_rate', 'rate')}
Stop failure rate:      ${get('shift_stop_failure_rate', 'rate')}
Unexpected stops:       ${get('unexpected_stop_responses', 'count')}
Prepared existing:      ${get('prepared_existing_sessions', 'count')}
Prepared new sessions:  ${get('prepared_started_sessions', 'count')}

============================================================
`;
}