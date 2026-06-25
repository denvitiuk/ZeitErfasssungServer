

# Performance Testing

This document records the local test-backend performance baseline for the tracking flow.

## Scope

The tested workflow covers:

- worker login;
- shift start;
- ZeitPlan assignment matching and `STARTED` transition;
- GPS point writes;
- admin sessions dashboard reads;
- shift stop;
- ZeitPlan assignment `COMPLETED` transition.

## Test Backend

Run the test environment with a pool sized for the expected 25-worker burst:

```bash
DB_MAX_POOL_TEST=25 APP_ENV=test ./gradlew run
```

The test target is local only:

```text
http://127.0.0.1:8080
```

## Environment Variables

```bash
export K6_WORKER_PASSWORD='<test-worker-password>'
export K6_ADMIN_EMAIL='admin@test.com'
export K6_ADMIN_PASSWORD='<test-admin-password>'
```

Do not put production credentials into shell history or committed files.

## 1. Shift Start Burst

```bash
K6_MODE=pure \
K6_WORKER_COUNT=25 \
K6_WORKER_PASSWORD="$K6_WORKER_PASSWORD" \
BASE_URL='http://127.0.0.1:8080' \
k6 run load/k6-shift-start-burst.js
```

Expected baseline with `DB_MAX_POOL_TEST=25`:

```text
Start p95: < 800 ms
Start p99: < 1200 ms
Failure rate: 0%
```

Measured baseline:

```text
Start p95: 395 ms
Start p99: 449 ms
```

## 2. Shift Stop Burst

Run this only after workers have active sessions created by the start burst.

```bash
K6_WORKER_COUNT=25 \
K6_WORKER_PASSWORD="$K6_WORKER_PASSWORD" \
BASE_URL='http://127.0.0.1:8080' \
k6 run load/k6-shift-stop-burst.js
```

Expected baseline:

```text
Stop p95: < 800 ms
Stop p99: < 1200 ms
Failure rate: 0%
```

Measured baseline:

```text
Stop p95: 465 ms
Stop p99: 513 ms
```

Verify business completion after the test:

```sql
SELECT
  a.status,
  COUNT(*) AS count
FROM zeitplan_shift_assignments a
JOIN zeitplan_plans p ON p.id = a.plan_id
WHERE p.company_id = 1
  AND p.title LIKE 'LOAD TEST — ZeitPlan Start Stop%'
GROUP BY a.status
ORDER BY a.status;
```

Expected result:

```text
COMPLETED | 25
```

## 3. Mixed Shift Flow

This is the primary regression test. It runs 25 worker flows for five minutes while one admin refreshes the sessions dashboard every two seconds.

```bash
K6_WORKER_COUNT=25 \
K6_WORKER_PASSWORD="$K6_WORKER_PASSWORD" \
K6_ADMIN_EMAIL="$K6_ADMIN_EMAIL" \
K6_ADMIN_PASSWORD="$K6_ADMIN_PASSWORD" \
K6_FLOW_DURATION_SECONDS=300 \
K6_POINT_INTERVAL_SECONDS=10 \
K6_ADMIN_REFRESH_INTERVAL_SECONDS=2 \
K6_POINTS_ENABLED=true \
BASE_URL='http://127.0.0.1:8080' \
k6 run load/k6-mixed-shift-flow.js
```

Expected thresholds:

```text
HTTP failure rate:  < 1%
Start p95:          < 800 ms
GPS point p95:      < 900 ms
Admin dashboard p95:< 900 ms
Stop p95:           < 800 ms
```

Measured five-minute baseline:

```text
HTTP requests:          943
Request failure rate:   0%
HTTP p95:               424 ms
HTTP p99:               653 ms

Start p95:              686 ms
Start p99:              787 ms

GPS point p95:          304 ms
GPS point p99:          544 ms

Admin dashboard p95:    313 ms
Admin dashboard p99:    490 ms

Stop p95:               176 ms
Stop p99:               194 ms
```

The admin scenario may report one interrupted iteration when k6 ends its timed loop. This is expected and is not an API failure when request failure rate remains zero.

## Pool Sizing Notes

The initial test configuration used a pool size of 10. Under a synchronized 25-worker burst, request latency rose to roughly 1.7–2.2 seconds because requests waited for database connections.

For the local single-instance test backend:

```text
DB_MAX_POOL_TEST=25
```

For production, choose `DB_MAX_POOL` from the database connection budget and the maximum number of service instances:

```text
total possible client connections = DB_MAX_POOL × maximum service instances
```

Start conservatively. For example:

```text
1 instance  → DB_MAX_POOL=20
2 instances → DB_MAX_POOL=10
3 instances → DB_MAX_POOL=6
```

Use the Neon pooled connection endpoint in production and verify the actual database plan limits before increasing these values.

## Correctness Checks

The ZeitPlan matcher supports normal, overnight, and cross-midnight shifts by comparing actual timestamps rather than only local time values:

```text
10:00 → 18:00
22:00 → 06:00
23:00 → 01:00
00:00 → 23:59
```

The stop flow is atomic:

```text
tracking session stop
+ attendance log "out"
+ ZeitPlan assignment COMPLETED
= one database transaction
```