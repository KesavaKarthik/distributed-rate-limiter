import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// What this measures, and why it is built this way.
//
// The claim Phase 1 made is that correctness costs one Redis round trip plus one
// Lua execution per request, and nothing else. That is only checkable against a
// control: /api/baseline is in ratelimiter.exempt-paths, so it runs through the
// same load balancer, the same JVM, the same connector and the same warmed-up
// filter chain as the limited endpoints, with the Redis call removed. The
// difference between its p99 and a limited endpoint's p99 IS the cost of the
// limiter. A separate run with the limiter disabled could not be subtracted with
// the same confidence, because it would be a different JVM in a different state.
//
// Scenarios run SEQUENTIALLY, not in parallel. Running them together would put
// them in contention for the same Redis and the same connection pool, and each
// would then be measuring the others.

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8000';
const RATE = Number(__ENV.RATE || 400);          // requests/sec, aggregate
const DURATION = __ENV.DURATION || '30s';
const VUS = Number(__ENV.VUS || 200);

// One principal per scenario, deliberately. The limits are per caller, so a
// scenario that spread itself over many identities would never reach a ceiling
// and /api/strict would have nothing to demonstrate.
const BURST_USER = 'burst-tester';
const STRICT_USER = 'strict-tester';

const WARMUP = '10s';
const SLOT = durationSeconds(DURATION) + 2;

export const options = {
  discardResponseBodies: true,
  // k6 only computes the percentiles named here. Declaring a threshold on p(99)
  // creates the sub-metric but does NOT put p(99) in the summary object, so
  // without this line the report reads 0.00 for every p99.
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
  scenarios: {
    // Pays the one-off costs — JIT, Lettuce connect, nginx upstream pool, the
    // first EVAL of each script — so they land outside every measured window.
    warmup: {
      executor: 'constant-arrival-rate',
      rate: 50, timeUnit: '1s', duration: WARMUP,
      preAllocatedVUs: 20, exec: 'warmup', startTime: '0s',
    },
    baseline: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: VUS, exec: 'baseline',
      startTime: WARMUP, tags: { scenario: 'baseline' },
    },
    burst: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: VUS, exec: 'burst',
      startTime: offset(WARMUP, SLOT), tags: { scenario: 'burst' },
    },
    strict: {
      executor: 'constant-arrival-rate',
      rate: RATE, timeUnit: '1s', duration: DURATION,
      preAllocatedVUs: VUS, exec: 'strict',
      startTime: offset(WARMUP, SLOT * 2), tags: { scenario: 'strict' },
    },
  },

  // These are not pass/fail gates. Declaring a threshold on a tagged sub-metric
  // is what makes k6 compute and report that sub-metric at all, which is the
  // only way to get a per-scenario p99 out of the summary.
  thresholds: {
    'http_req_duration{scenario:baseline}': ['p(99)>=0'],
    'http_req_duration{scenario:burst}': ['p(99)>=0'],
    'http_req_duration{scenario:strict}': ['p(99)>=0'],
    'http_reqs{scenario:baseline}': ['count>=0'],
    'http_reqs{scenario:burst}': ['count>=0'],
    'http_reqs{scenario:strict}': ['count>=0'],
    // The real gate: the limiter must never fail a request outright. A 429 is a
    // correct answer; a 500 or a dropped connection is not.
    'unexpected_status': ['count==0'],
  },
};

const allowed = new Counter('status_200');
const limited = new Counter('status_429');
const degraded = new Counter('status_503');
const unexpected = new Counter('unexpected_status');

export function warmup() {
  http.get(`${BASE_URL}/api/baseline`);
  http.get(`${BASE_URL}/api/burst`, { headers: { 'X-User-Id': BURST_USER } });
  http.get(`${BASE_URL}/api/strict`, { headers: { 'X-User-Id': STRICT_USER } });
}

// The control: no rule resolves to it, no Redis call is made.
export function baseline() {
  const res = http.get(`${BASE_URL}/api/baseline`);
  record(res);
  check(res, { 'baseline is never limited': (r) => r.status === 200 });
}

// Provisioned above the arrival rate on purpose, so nearly every request is
// admitted. This scenario is measuring what an ALLOWED request costs.
export function burst() {
  const res = http.get(`${BASE_URL}/api/burst`, { headers: { 'X-User-Id': BURST_USER } });
  record(res);
  check(res, { 'burst is admitted': (r) => r.status === 200 });
}

// A hard rolling ceiling far below the arrival rate, so most requests are
// rejected. Note that a 429 costs the SAME Redis round trip as a 200 — the
// script decides and returns in one call either way — so mixing them does not
// skew the latency figure.
export function strict() {
  const res = http.get(`${BASE_URL}/api/strict`, { headers: { 'X-User-Id': STRICT_USER } });
  record(res);
  check(res, { 'strict answers 200 or 429': (r) => r.status === 200 || r.status === 429 });
}

function record(res) {
  if (res.status === 200) allowed.add(1);
  else if (res.status === 429) limited.add(1);
  else if (res.status === 503) degraded.add(1);
  else unexpected.add(1);
}

function durationSeconds(d) {
  const match = /^(\d+)(s|m)$/.exec(d);
  if (!match) return 30;
  return Number(match[1]) * (match[2] === 'm' ? 60 : 1);
}

function offset(warmup, extraSeconds) {
  return `${durationSeconds(warmup) + extraSeconds}s`;
}

export function handleSummary(data) {
  const report = buildReport(data);
  return {
    stdout: report,
    '/k6/summary.txt': report,
    '/k6/summary.json': JSON.stringify(data, null, 2),
  };
}

function buildReport(data) {
  const seconds = durationSeconds(DURATION);
  const rows = ['baseline', 'burst', 'strict'].map((name) => scenarioRow(data, name, seconds));

  const base = rows.find((r) => r.name === 'baseline');
  const burstRow = rows.find((r) => r.name === 'burst');
  const strictRow = rows.find((r) => r.name === 'strict');

  const lines = [];
  lines.push('');
  lines.push('RATE LIMITER LOAD TEST');
  lines.push(`  target ${BASE_URL} via round-robin over 2 instances, ${RATE} req/s offered for ${DURATION} each`);
  lines.push('');
  lines.push('  scenario    requests      req/s     avg ms     p50 ms     p95 ms     p99 ms');
  lines.push('  ' + '-'.repeat(73));
  for (const row of rows) {
    lines.push(
      '  ' + row.name.padEnd(11) +
      String(row.count).padStart(8) +
      fmt(row.rps, 11) + fmt(row.avg, 11) + fmt(row.p50, 11) + fmt(row.p95, 11) + fmt(row.p99, 11)
    );
  }
  lines.push('');
  lines.push('  responses   200: ' + count(data, 'status_200') +
             '   429: ' + count(data, 'status_429') +
             '   503: ' + count(data, 'status_503') +
             '   other: ' + count(data, 'unexpected_status'));
  lines.push('');
  lines.push('  THE NUMBER — cost of one atomic distributed limit check');
  lines.push('  (limited endpoint minus the exempt control, same run, same JVM)');
  lines.push('');
  lines.push('                    avg ms     p50 ms     p95 ms     p99 ms');
  lines.push('  ' + '-'.repeat(57));
  lines.push('    admitted ' + delta(burstRow, base));
  lines.push('    rejected ' + delta(strictRow, base));
  lines.push('');
  lines.push('    That difference is one Redis round trip plus one Lua execution.');
  lines.push('    Rule resolution is not in it: that is map lookups in the JVM, off');
  lines.push('    the network entirely. Reject and admit cost the same because both');
  lines.push('    are decided by the same single call.');
  lines.push('');
  lines.push('    Read avg/p50/p95, not p99. Below saturation the p99 tail is');
  lines.push('    JVM and OS scheduling jitter, which the control carries too --');
  lines.push('    so the p99 column can and does come out negative. It measures');
  lines.push('    the machine, not the limiter.');
  lines.push('');
  return lines.join('\n') + '\n';
}

function scenarioRow(data, name, seconds) {
  const duration = data.metrics[`http_req_duration{scenario:${name}}`];
  const reqs = data.metrics[`http_reqs{scenario:${name}}`];
  const values = (duration && duration.values) || {};
  const count = (reqs && reqs.values && reqs.values.count) || 0;
  return {
    name,
    count,
    rps: count / seconds,
    avg: values.avg || 0,
    p50: values.med || 0,
    p95: values['p(95)'] || 0,
    p99: values['p(99)'] || 0,
  };
}

function count(data, metric) {
  const m = data.metrics[metric];
  return (m && m.values && m.values.count) || 0;
}

function fmt(value, width) {
  return value.toFixed(2).padStart(width);
}

function delta(row, base) {
  return [row.avg - base.avg, row.p50 - base.p50, row.p95 - base.p95, row.p99 - base.p99]
    .map((d) => (d >= 0 ? '+' : '') + d.toFixed(2))
    .map((d) => d.padStart(11))
    .join('');
}
