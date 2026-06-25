import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.BASE_URL || 'http://127.0.0.1:8080').replace(/\/$/, '');
const WORKER_COUNT = Number(__ENV.K6_WORKER_COUNT || 25);
const WORKER_PASSWORD = __ENV.K6_WORKER_PASSWORD || 'WorkerLoad2026!';
const ADMIN_EMAIL = __ENV.K6_ADMIN_EMAIL;
const ADMIN_PASSWORD = __ENV.K6_ADMIN_PASSWORD;
const DATE = __ENV.K6_DATE || new Date().toISOString().slice(0, 10);
const FLOW_DURATION_SECONDS = Number(__ENV.K6_FLOW_DURATION_SECONDS || 300);
const POINT_INTERVAL_SECONDS = Number(__ENV.K6_POINT_INTERVAL_SECONDS || 10);
const ADMIN_REFRESH_INTERVAL_SECONDS = Number(__ENV.K6_ADMIN_REFRESH_INTERVAL_SECONDS || 2);
const POINTS_ENABLED = (__ENV.K6_POINTS_ENABLED || 'true').toLowerCase() === 'true';

const allowedLocalTargets = new Set(['localhost', '127.0.0.1', 'host.docker.internal']);
const targetHost = BASE_URL.replace(/^https?:\/\//, '').split('/')[0].split(':')[0];

if (!allowedLocalTargets.has(targetHost) && __ENV.K6_ALLOW_REMOTE !== 'I_UNDERSTAND') {
  throw new Error(
    `Refusing to run against ${BASE_URL}. ` +
      'Use localhost/test infrastructure. For an explicitly authorized remote test, set K6_ALLOW_REMOTE=I_UNDERSTAND.'
  );
}

if (!Number.isInteger(WORKER_COUNT) || WORKER_COUNT < 1 || WORKER_COUNT > 100) {
  throw new Error('K6_WORKER_COUNT must be an integer from 1 to 100.');
}

if (!Number.isFinite(FLOW_DURATION_SECONDS) || FLOW_DURATION_SECONDS < 30 || FLOW_DURATION_SECONDS > 3600) {
  throw new Error('K6_FLOW_DURATION_SECONDS must be from 30 to 3600.');
}

if (!Number.isFinite(POINT_INTERVAL_SECONDS) || POINT_INTERVAL_SECONDS < 1 || POINT_INTERVAL_SECONDS > 300) {
  throw new Error('K6_POINT_INTERVAL_SECONDS must be from 1 to 300.');
}

if (!Number.isFinite(ADMIN_REFRESH_INTERVAL_SECONDS) || ADMIN_REFRESH_INTERVAL_SECONDS < 1 || ADMIN_REFRESH_INTERVAL_SECONDS > 300) {
  throw new Error('K6_ADMIN_REFRESH_INTERVAL_SECONDS must be from 1 to 300.');
}

if (!ADMIN_EMAIL || !ADMIN_PASSWORD) {
  throw new Error('K6_ADMIN_EMAIL and K6_ADMIN_PASSWORD are required.');
}

const startSuccessRate = new Rate('mixed_start_success_rate');
const startFailureRate = new Rate('mixed_start_failure_rate');
const startDuration = new Trend('mixed_start_duration', true);

const pointSuccessRate = new Rate('mixed_point_success_rate');
const pointFailureRate = new Rate('mixed_point_failure_rate');
const pointDuration = new Trend('mixed_point_duration', true);

const adminSuccessRate = new Rate('mixed_admin_sessions_success_rate');
const adminFailureRate = new Rate('mixed_admin_sessions_failure_rate');
const adminDuration = new Trend('mixed_admin_sessions_duration', true);

const stopSuccessRate = new Rate('mixed_stop_success_rate');
const stopFailureRate = new Rate('mixed_stop_failure_rate');
const stopDuration = new Trend('mixed_stop_duration', true);

const unexpectedResponses = new Counter('mixed_unexpected_responses');

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)'],
  scenarios: {
    worker_shift_flow: {
      executor: 'per-vu-iterations',
      vus: WORKER_COUNT,
      iterations: 1,
      maxDuration: `${FLOW_DURATION_SECONDS + 90}s`,
      gracefulStop: '20s',
      exec: 'workerShiftFlow',
    },
    admin_dashboard: {
      executor: 'constant-vus',
      vus: 1,
      duration: `${FLOW_DURATION_SECONDS + 20}s`,
      gracefulStop: '10s',
      exec: 'adminDashboardFlow',
    },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<1200', 'p(99)<2000'],
    mixed_start_success_rate: ['rate==1'],
    mixed_start_failure_rate: ['rate==0'],
    mixed_start_duration: ['p(95)<800', 'p(99)<1200'],
    mixed_admin_sessions_success_rate: ['rate==1'],
    mixed_admin_sessions_failure_rate: ['rate==0'],
    mixed_admin_sessions_duration: ['p(95)<900', 'p(99)<1500'],
    mixed_stop_success_rate: ['rate==1'],
    mixed_stop_failure_rate: ['rate==0'],
    mixed_stop_duration: ['p(95)<800', 'p(99)<1200'],
  },
};

function authHeaders(token) {
  return {
    Authorization: `Bearer ${token}`,
    Accept: 'application/json',
  };
}

function jsonHeaders(token) {
  return {
    ...authHeaders(token),
    'Content-Type': 'application/json',
  };
}

function login(email, password, tagName) {
  const response = http.post(
    `${BASE_URL}/login`,
    JSON.stringify({ email, password }),
    {
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
      },
      tags: { name: tagName },
    }
  );

  const ok = check(response, {
    'login returns 200': (r) => r.status === 200,
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
    workers.push({
      index,
      email,
      token: login(email, WORKER_PASSWORD, 'POST /login [mixed worker setup]'),
    });
  }

  return {
    workers,
    adminToken: login(ADMIN_EMAIL, ADMIN_PASSWORD, 'POST /login [mixed admin setup]'),
  };
}

export function workerShiftFlow(data) {
  const worker = data.workers[__VU - 1];
  if (!worker) {
    throw new Error(`No worker credentials mapped for VU ${__VU}.`);
  }

  const start = http.post(
    `${BASE_URL}/tracking/sessions/start`,
    null,
    {
      headers: authHeaders(worker.token),
      tags: { name: 'POST /tracking/sessions/start [mixed]' },
    }
  );

  startDuration.add(start.timings.duration);

  let sessionId = null;
  const startOk = check(start, {
    'mixed start returns 200': (r) => r.status === 200,
    'mixed start returns session id': (r) => {
      try {
        const body = r.json();
        sessionId = body?.sessionId || null;
        return Boolean(sessionId);
      } catch (_) {
        return false;
      }
    },
  });

  startSuccessRate.add(startOk);
  startFailureRate.add(!startOk);

  if (!startOk) {
    unexpectedResponses.add(1);
    console.error(`mixed start failed worker=${worker.email} status=${start.status} body=${start.body}`);
    return;
  }

  worker.sessionId = sessionId;

  const stopAt = Date.now() + FLOW_DURATION_SECONDS * 1000;

  while (Date.now() < stopAt) {
    if (POINTS_ENABLED) {
      const point = http.post(
        `${BASE_URL}/tracking/points`,
        JSON.stringify(pointPayload(worker, __ITER)),
        {
          headers: jsonHeaders(worker.token),
          tags: { name: 'POST /tracking/points [mixed]' },
        }
      );

      pointDuration.add(point.timings.duration);
      const pointOk = check(point, {
        'mixed point is not 5xx': (r) => r.status < 500,
        'mixed point returns 2xx': (r) => r.status >= 200 && r.status < 300,
      });

      pointSuccessRate.add(pointOk);
      pointFailureRate.add(!pointOk);

      if (!pointOk) {
        unexpectedResponses.add(1);
        console.error(`mixed point failed worker=${worker.email} status=${point.status} body=${point.body}`);
      }
    }

    sleep(POINT_INTERVAL_SECONDS);
  }

  const stop = http.post(
    `${BASE_URL}/tracking/sessions/${sessionId}/stop`,
    null,
    {
      headers: authHeaders(worker.token),
      tags: { name: 'POST /tracking/sessions/{id}/stop [mixed]' },
    }
  );

  stopDuration.add(stop.timings.duration);
  const stopOk = check(stop, {
    'mixed stop returns 200': (r) => r.status === 200,
  });

  stopSuccessRate.add(stopOk);
  stopFailureRate.add(!stopOk);

  if (!stopOk) {
    unexpectedResponses.add(1);
    console.error(`mixed stop failed worker=${worker.email} status=${stop.status} body=${stop.body}`);
  }
}

export function adminDashboardFlow(data) {
  while (true) {
    const response = http.get(
      `${BASE_URL}/tracking/sessions?date=${DATE}`,
      {
        headers: authHeaders(data.adminToken),
        tags: { name: 'GET /tracking/sessions [mixed admin]' },
      }
    );

    adminDuration.add(response.timings.duration);
    const adminOk = check(response, {
      'mixed admin sessions returns 200': (r) => r.status === 200,
      'mixed admin sessions returns array': (r) => {
        try {
          return Array.isArray(r.json());
        } catch (_) {
          return false;
        }
      },
    });

    adminSuccessRate.add(adminOk);
    adminFailureRate.add(!adminOk);

    if (!adminOk) {
      unexpectedResponses.add(1);
      console.error(`mixed admin request failed status=${response.status} body=${response.body}`);
    }

    sleep(ADMIN_REFRESH_INTERVAL_SECONDS);
  }
}

export function handleSummary(data) {
  const metrics = data.metrics;
  const get = (name, field) => metrics[name]?.values?.[field] ?? 'n/a';

  const pointSection = POINTS_ENABLED
    ? `\nPoint p95:              ${get('mixed_point_duration', 'p(95)')} ms\nPoint p99:              ${get('mixed_point_duration', 'p(99)')} ms\nPoint success rate:     ${get('mixed_point_success_rate', 'rate')}\nPoint failure rate:     ${get('mixed_point_failure_rate', 'rate')}\n`
    : '\nPoints:                 disabled\n';

  return {
    'load/results/k6-mixed-shift-flow-summary.json': JSON.stringify(data, null, 2),
    stdout: `
================ MIXED SHIFT FLOW SUMMARY ================

Target: ${BASE_URL}
Workers:                ${WORKER_COUNT}
Flow duration:          ${FLOW_DURATION_SECONDS} sec
Points enabled:         ${POINTS_ENABLED}
Point interval:         ${POINT_INTERVAL_SECONDS} sec
Admin refresh interval: ${ADMIN_REFRESH_INTERVAL_SECONDS} sec

HTTP requests:          ${get('http_reqs', 'count')}
Request failure rate:   ${get('http_req_failed', 'rate')}
HTTP p95:               ${get('http_req_duration', 'p(95)')} ms
HTTP p99:               ${get('http_req_duration', 'p(99)')} ms

Start p95:              ${get('mixed_start_duration', 'p(95)')} ms
Start p99:              ${get('mixed_start_duration', 'p(99)')} ms
Start success rate:     ${get('mixed_start_success_rate', 'rate')}
Start failure rate:     ${get('mixed_start_failure_rate', 'rate')}
${pointSection}
Admin dashboard p95:    ${get('mixed_admin_sessions_duration', 'p(95)')} ms
Admin dashboard p99:    ${get('mixed_admin_sessions_duration', 'p(99)')} ms
Admin success rate:     ${get('mixed_admin_sessions_success_rate', 'rate')}
Admin failure rate:     ${get('mixed_admin_sessions_failure_rate', 'rate')}

Stop p95:               ${get('mixed_stop_duration', 'p(95)')} ms
Stop p99:               ${get('mixed_stop_duration', 'p(99)')} ms
Stop success rate:      ${get('mixed_stop_success_rate', 'rate')}
Stop failure rate:      ${get('mixed_stop_failure_rate', 'rate')}
Unexpected responses:   ${get('mixed_unexpected_responses', 'count')}

===========================================================
`,
  };
}