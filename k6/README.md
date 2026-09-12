# Load testing the rate limiter

Two runs. The first measures what the limiter costs a request. The second
measures what happens when Redis is gone.

Everything runs inside Docker Compose, on the compose network. A generator on the
host would add the Docker Desktop network hop to every sample, and that hop is
larger than the Redis round trip being measured.

## Topology

```
k6 ──▶ nginx :8000 ──┬──▶ app1 :8080 ──┐
                     └──▶ app2 :8080 ──┴──▶ redis :6379
```

Round-robin over two JVMs with isolated heaps, sharing one Redis. nginx does the
balancing rather than the k6 script, so load balancing is a property of the
deployment and not of the test harness.

## Run it

```bash
docker compose up --build -d          # redis + app1 + app2 + nginx
docker compose --profile load run --rm k6 run rate-limit-test.js
```

Results land in `k6/summary.txt` and `k6/summary.json`.

The script path is **relative** because the k6 service sets `working_dir: /k6`.
Do not write it as `/k6/rate-limit-test.js`: Git Bash on Windows rewrites a
leading-slash argument into a Windows path (`C:/Program Files/Git/k6/...`) and k6
then cannot find the file.

Knobs, all optional:

```bash
docker compose --profile load run --rm \
  -e RATE=800 -e DURATION=60s -e VUS=400 \
  k6 run rate-limit-test.js
```

## The three scenarios

They run **one after another**, not together. Run in parallel they would contend
for the same Redis and the same connection pool, and each would be measuring the
others.

| Scenario | Path | Rule | What it is for |
|---|---|---|---|
| `warmup` | all three | — | Pays JIT, Lettuce connect, nginx upstream pool and the first `EVAL` of each script, so those land outside every measured window |
| `baseline` | `/api/baseline` | none — in `ratelimiter.exempt-paths` | **The control.** Same LB, same JVM, same filter chain, no Redis call |
| `burst` | `/api/burst` | token bucket, capacity 10000, 10000/s | Cost of an **admitted** request |
| `strict` | `/api/strict` | sliding window log, 10 per second, fail-closed | Cost of a **rejected** request, and proof the limit binds |

Each scenario drives one identity (`X-User-Id`). The limits are per caller, so a
scenario spread over many identities would never reach a ceiling and `/api/strict`
would have nothing to show.

## Reading the output

Real output from this rig (2 JVMs + nginx + Redis on one 16-core host):

```
  scenario    requests      req/s     avg ms     p50 ms     p95 ms     p99 ms
  -------------------------------------------------------------------------
  baseline      60001    2000.03       0.66       0.47       0.94       5.94
  burst         60001    2000.03       1.12       0.82       1.80       9.65
  strict        60001    2000.03       1.03       0.77       1.56       8.21

  THE NUMBER — cost of one atomic distributed limit check
                    avg ms     p50 ms     p95 ms     p99 ms
    admitted       +0.46      +0.35      +0.86      +3.71
    rejected       +0.37      +0.30      +0.62      +2.27
```

- **Throughput** is `req/s` per scenario. Offered load is fixed by `RATE`, so a
  measured rate below it means the system could not keep up — check
  `dropped_iterations` in `summary.json`.
- **Read avg / p50 / p95, not p99.** Below saturation the p99 tail is JVM and OS
  scheduling jitter, and the control endpoint carries it too — early runs here
  produced *negative* p99 deltas. That is the tell that p99 is measuring the
  machine, not the limiter.
- The tags are why the thresholds block exists: declaring a threshold on a tagged
  sub-metric is what makes k6 compute it. `summaryTrendStats` is why p99 appears
  at all — a threshold creates the sub-metric but does not put the percentile in
  the summary object.
- **The delta** (limited minus control) is the whole point. It is one Redis
  round trip plus one Lua execution, and nothing else:
  - rule resolution contributes nothing measurable — it is hash lookups in the
    JVM, off the network entirely;
  - there is no second RTT, because the limiter is embedded in the app rather
    than being a service of its own;
  - `burst` and `strict` come out within noise of each other, because **a 429
    costs the same round trip as a 200** — one call decides and returns either
    way.

  Measured here: **~0.45 ms mean, 0.6–0.9 ms p95**, and flat from 400 to 2,000
  req/s — the cost is a round trip, not a queue. Past ~5,000 req/s the *control*
  endpoint degrades too, which means the rig is saturating rather than the
  limiter.

### Do not judge the run by `http_req_failed`

k6 counts a 429 as a failed request. On `/api/strict` most requests are 429 by
design, so that metric will read alarmingly high and mean nothing. Judge the run
by the explicit counters instead:

```
  responses   200: 24310   429: 11690   503: 0   other: 0
```

`other` is the only one that indicates a problem, and the run has a real
threshold on it: `unexpected_status: count==0`. A 429 is a correct answer. A 500
or a dropped connection is not.

### One thing the run deliberately does not include

The coarse per-IP tier (`ratelimiter.ip.enabled`) is off. Behind nginx every
request arrives from the same address anyway, and enabling it would put a
**second** Redis call on the path, doubling the measured cost and making the
baseline delta no longer mean "one round trip". Turn it on with
`RATELIMITER_IP_ENABLED=true` on `app1`/`app2` if you want to see that doubling —
it is a useful demonstration, but it is not the number this run reports.

## The outage run

```powershell
.\k6\run-failopen.ps1      # Windows
```
```bash
./k6/run-failopen.sh       # bash
```

Starts steady load, stops Redis at t+20s, starts it again at t+40s. The kill has
to happen with load in flight; an outage discovered between runs proves nothing
about what an in-progress request does.

Results land in `k6/fail-open-summary.txt`. What to look for:

```
  phase        p99 ms     avg ms
  --------------------------------
  healthy           4.14       1.38
  degraded         16.34       1.47
  recovered         4.52       1.18

  while redis was down
    /api/burst  fail-open   200 with X-RateLimit-Degraded : 5845
    /api/strict fail-closed 503                           : 5845

  5xx that were not a deliberate 503 : 0
```

### Two different outages, and only one of them needs the timeout

`run-failopen` uses `docker compose stop redis`, which is a **hard down**: the
connection is refused and Lettuce fails in microseconds. Nothing waits, so the
50ms command timeout never comes into play.

The case the timeout exists for is a **blackhole** — the process holds the socket
and never answers. Simulate it with `docker pause ratelimiter-redis` instead of
stopping it, and requests hit the 50ms timeout for real. Run both; they behave
completely differently, and only the second one justifies the timeout.

- **The two endpoints degrade differently, and that is the design.** `/api/burst`
  is fail-open so it keeps serving unmetered; `/api/strict` is fail-closed so it
  becomes unavailable rather than unprotected. Both are configured per rule.
- **`X-RateLimit-Degraded` is on every degraded response.** A decision made
  without the shared counter is still a decision, and it must not be silent.
- **503, not 429.** 429 means the caller exceeded a limit. Reusing it here would
  make degradation indistinguishable from enforcement in every metric downstream,
  including this one.
- **Zero 5xx other than those 503s.** This is the run's real threshold. Before
  Phase 2 a Redis outage propagated out of `tryAcquire` and 500'd the request.
- **The breaker's value shows up under `pause`, not `stop`.** Against a
  blackholed Redis, 20 sequential requests measured individually:

  ```
  breaker ON   0.077 0.055 0.060 0.055 0.058 | 0.006 0.006 0.004 0.005 ...
  breaker OFF  0.058 0.057 0.057 0.060 0.059 | 0.061 0.059 0.058 0.059 ...
                      ^ the 5 that trip it      ^ everything after
  ```

  **~58ms → ~5ms**, with exactly `failure-threshold` requests paying the timeout
  before it opens. Reproduce with the overlay:

  ```bash
  docker compose -f docker-compose.yml -f k6/compose.no-breaker.yml up -d --force-recreate app1 app2
  # ... probe ...
  docker compose up -d --force-recreate app1 app2      # back to normal
  ```

  **Pass both `-f` flags on every compose command while the overlay is active.**
  A plain `docker compose run` re-reads only the base file and silently recreates
  `app1`/`app2` without the override — which will quietly give you a "breaker
  off" run that still has the breaker on.
- **Recovery is unattended.** `recovered` returning to `healthy` numbers, with
  `/api/strict` producing 429s again, is the breaker probing, succeeding, and
  closing on its own.
