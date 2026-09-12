import http from 'k6/http';
import exec from 'k6/execution';
import { Counter } from 'k6/metrics';

// Redis is killed while this runs. The point is not that the requests survive —
// it is that the two endpoints survive DIFFERENTLY, because their rules say so.
//
//   /api/burst   fail-open   -> 200, unlimited, availability chosen
//   /api/strict  fail-closed -> 503, unavailable, protection chosen
//
// Neither may ever return 500. Before Phase 2 both would have.
//
// Every sample is tagged with the phase it fell in, so the summary reports
// healthy / degraded / recovered latency separately. The interesting number is
// the degraded p99: with the circuit breaker on it collapses towards baseline
// once the breaker opens, because a request that is not sent to Redis does not
// wait for the command timeout. Set RATELIMITER_BREAKER_ENABLED=false on the app
// to see the same run without it, where every request pays the timeout instead.

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8000';
const RATE = Number(__ENV.RATE || 200);
const KILL_AT = Number(__ENV.KILL_AT || 20);      // seconds into the run
const RESTORE_AT = Number(__ENV.RESTORE_AT || 40);
const TOTAL = Number(__ENV.TOTAL || 65);

// Ignored while phases change over, so a sample taken during the stop or start
// of the container is not attributed to either side of the boundary.
const SETTLE = 3;

export const options = {
  discardResponseBodies: true,
  // Without this k6 does not compute p(99) for the summary, only for thresholds.
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    outage: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: `${TOTAL}s`,
      preAllocatedVUs: 100, maxVUs: 400,
    },
  },
  thresholds: {
    'http_req_duration{phase:healthy}': ['p(99)>=0'],
    'http_req_duration{phase:degraded}': ['p(99)>=0'],
    'http_req_duration{phase:recovered}': ['p(99)>=0'],
    'http_req_duration{endpoint:burst,phase:degraded}': ['p(99)>=0'],
    'http_req_duration{endpoint:strict,phase:degraded}': ['p(99)>=0'],
    // The whole contract of Section 2, as one assertion.
    'server_errors': ['count==0'],
  },
};

const serverErrors = new Counter('server_errors');
const burstAllowed = new Counter('burst_200');
const burstDegraded = new Counter('burst_degraded_200');
const strictAllowed = new Counter('strict_200');
const strictLimited = new Counter('strict_429');
const strictDegraded = new Counter('strict_503');

export default function () {
  const phase = currentPhase();
  if (phase === null) {
    return; // inside a settle window, deliberately unmeasured
  }

  const burst = http.get(`${BASE_URL}/api/burst`, {
    headers: { 'X-User-Id': 'outage-tester' },
    tags: { phase, endpoint: 'burst' },
  });
  const strict = http.get(`${BASE_URL}/api/strict`, {
    headers: { 'X-User-Id': 'outage-tester' },
    tags: { phase, endpoint: 'strict' },
  });

  classifyBurst(burst);
  classifyStrict(strict);
}

function classifyBurst(res) {
  if (res.status >= 500 && res.status !== 503) serverErrors.add(1);
  if (res.status !== 200) return;
  if (res.headers['X-Ratelimit-Degraded']) burstDegraded.add(1);
  else burstAllowed.add(1);
}

function classifyStrict(res) {
  if (res.status >= 500 && res.status !== 503) serverErrors.add(1);
  if (res.status === 200) strictAllowed.add(1);
  else if (res.status === 429) strictLimited.add(1);
  else if (res.status === 503) strictDegraded.add(1);
}

function currentPhase() {
  const t = exec.instance.currentTestRunDuration / 1000;
  if (t < KILL_AT - SETTLE) return 'healthy';
  if (t > KILL_AT + SETTLE && t < RESTORE_AT - SETTLE) return 'degraded';
  if (t > RESTORE_AT + SETTLE) return 'recovered';
  return null;
}

export function handleSummary(data) {
  const report = buildReport(data);
  return {
    stdout: report,
    '/k6/fail-open-summary.txt': report,
    '/k6/fail-open-summary.json': JSON.stringify(data, null, 2),
  };
}

function buildReport(data) {
  const lines = [];
  lines.push('');
  lines.push('REDIS OUTAGE — PER-RULE DEGRADATION');
  lines.push(`  redis stopped at t+${KILL_AT}s, restarted at t+${RESTORE_AT}s, ${RATE} iterations/s`);
  lines.push('');
  lines.push('  phase        p99 ms     avg ms');
  lines.push('  ' + '-'.repeat(32));
  for (const phase of ['healthy', 'degraded', 'recovered']) {
    const v = values(data, `http_req_duration{phase:${phase}}`);
    lines.push('  ' + phase.padEnd(11) + num(v['p(99)']) + num(v.avg));
  }
  lines.push('');
  lines.push('  while redis was down');
  lines.push(`    /api/burst  fail-open   200 with X-RateLimit-Degraded : ${count(data, 'burst_degraded_200')}`);
  lines.push(`    /api/strict fail-closed 503                           : ${count(data, 'strict_503')}`);
  lines.push(`    p99 degraded burst  : ${num(values(data, 'http_req_duration{endpoint:burst,phase:degraded}')['p(99)']).trim()} ms`);
  lines.push(`    p99 degraded strict : ${num(values(data, 'http_req_duration{endpoint:strict,phase:degraded}')['p(99)']).trim()} ms`);
  lines.push('');
  lines.push(`  5xx that were not a deliberate 503 : ${count(data, 'server_errors')}`);
  lines.push('    Must be zero. A limiter whose store is down has to degrade to a');
  lines.push('    decision; a 500 would make it a bigger outage than the one it');
  lines.push('    was added to prevent.');
  lines.push('');
  lines.push('  enforcement after recovery');
  lines.push(`    /api/strict 429 (a real limit, decided against redis) : ${count(data, 'strict_429')}`);
  lines.push('');
  return lines.join('\n') + '\n';
}

function values(data, metric) {
  const m = data.metrics[metric];
  return (m && m.values) || {};
}

function count(data, metric) {
  const m = data.metrics[metric];
  return (m && m.values && m.values.count) || 0;
}

function num(value) {
  return (value || 0).toFixed(2).padStart(11);
}
