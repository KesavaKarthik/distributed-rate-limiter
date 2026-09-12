# Distributed Rate Limiter

A rate limiter that stays **exact** across multiple application instances, built on atomic Redis Lua scripts.

![Java](https://img.shields.io/badge/Java-25_LTS-orange)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-4.1-brightgreen)
![Redis](https://img.shields.io/badge/Redis-7-red)
![Tests](https://img.shields.io/badge/tests-41%2F41-success)

Three interchangeable algorithms, config-driven rule resolution, and a per-rule
policy for what to do when Redis is unreachable.

---

## The problem

Rate limiting is a **read-modify-write on shared counter state**. That races, and
where you put the atomicity determines whether the limit actually holds:

| Deployment | Why the obvious fix fails |
|---|---|
| One JVM, many threads | `ConcurrentHashMap` makes the *map* thread-safe, not the bucket logic. The refill-then-decrement still races. `synchronized` fixes it. |
| Many JVMs | A JVM monitor exists in one process's heap. Another instance has no handle to it and cannot contend for it. **Every in-process primitive fails for the same reason.** |

With per-instance in-memory counters the limit isn't merely racy — it is silently
**multiplied by the instance count**. Moving state to Redis makes the race
*fixable*; it doesn't fix it. A `GET` then `SET` leaves the same window open.

The fix is to move the whole read-compute-branch-write **into Redis as one Lua
script**. Redis executes commands single-threaded and dispatches a script as a
single command, so nothing interleaves. There is no longer a gap between the read
and the write for another instance to slip into.

`DistributedRaceTest` fires 200 concurrent requests across two JVMs at a bucket of
capacity 100 and asserts **exactly 100** are admitted. Against the naive
`GET`/`SET` version the same test asserted the opposite — and passed.

---

## Architecture

```
clients ──▶ nginx :8000 ──┬──▶ app1 :8080 ──┐
                          └──▶ app2 :8080 ──┴──▶ redis :6379
```

The limiter runs **inside the application as a servlet filter**, not as a separate
service. A standalone limiter would put an app→limiter network hop in front of the
limiter→Redis hop; embedding it means the Redis round trip is the *only* one on
the request path.

Enforcement is split across two filters:

```
IpRateLimitFilter        coarse, per remote address   ── before auth
IdentityFilter           resolves caller identity     ── the auth boundary
IdentityRateLimitFilter  fine, per key/user/endpoint  ── after auth
```

A caller who never authenticates is exactly the traffic a coarse limit exists to
shed, so that check runs *before* any auth work is spent on it. The fine limit
must run *after*, because it cannot resolve a per-user rule before there is a user.

---

## Quick start

```bash
docker compose up --build -d     # redis + 2 app instances + nginx
curl localhost:8000/api/strict   # 10/sec, sliding window log
```

Requires JDK 25 and Docker. Run the tests with `./mvnw test` (the Testcontainers
suites are skipped automatically if Docker isn't running).

Responses carry `X-RateLimit-Remaining`, `X-RateLimit-Rule` (which rule matched),
and `Retry-After` on rejection, computed from the algorithm's real deficit rather
than a guess.

---

## Configuring limits

A rule maps `(api-key, user, endpoint)` to an algorithm and its numbers. Several
rules can match one request; resolution is **first match on specificity**, a
complete 3 × 2 product of identity × endpoint:

```
api-key+endpoint > user+endpoint > endpoint > api-key > user > global default
```

The global default is the floor, so every request resolves to something.

```yaml
ratelimiter:
  algorithm: tb          # the global default rule
  capacity: 10
  refill-rate: 5.0
  failure-mode: fail-open

  rules:
    endpoints:
      "[/api/strict]":
        algorithm: swl
        limit: 10
        window-ms: 1000
        failure-mode: fail-closed
    users:
      premium: { algorithm: tb, capacity: 1000, refill-rate: 500.0 }
```

Two properties worth naming:

- **An override replaces, it does not tighten.** A per-user rule of 1000/s beats a
  per-endpoint rule of 10/s, because it is more *specific*, not because it is
  larger. Taking the minimum of all matching rules was rejected — it would make
  overrides useless in the direction they are actually used (raising a partner's
  ceiling). Merging has no defined answer at all when two matching rules name
  different algorithms.
- **Resolution never touches Redis.** Rules are configuration: identical on every
  instance, uncontended, changing at deploy. They are indexed at startup into
  nested maps, so resolving one is two hash lookups with no allocation and no
  second round trip.

Malformed rules **fail at startup**, not at request time — including a field the
chosen algorithm cannot use, which is what an `algorithm: tb` next to a `limit:`
typo looks like. A rate limit that is quietly wrong looks exactly like one that is
right until someone is throttled who shouldn't be.

---

## When Redis is unavailable

Putting Redis on the request path buys correctness and sells a hard dependency.
Each rule declares which property it would rather lose:

- **`fail-open`** — allow. Availability over protection. A read endpoint briefly
  unlimited is survivable.
- **`fail-closed`** — reject with **503**. Protection over availability. An
  expensive endpoint going *unmetered* is worse than going *unavailable*.

503 rather than 429 is deliberate: 429 means *you exceeded your limit*, and reusing
it here would make degradation indistinguishable from enforcement in every metric
downstream. Every degraded response also carries `X-RateLimit-Degraded`.

Behind that sit a **50 ms Lettuce command timeout** and a small **circuit
breaker** — after 5 consecutive failures it short-circuits to the rule's failure
mode with no network call at all, then closes itself on a single successful probe.
No Redis failure escapes as a 500.

**Two outage shapes behave completely differently**, and only one needs the
timeout:

| | `docker stop` (hard down) | `docker pause` (blackhole) |
|---|---|---|
| Client sees | connection refused, fails fast | socket open, no reply |
| Timeout relevant? | no — nothing waits | **yes, this is what it is for** |

---

## Measured performance

Two app JVMs, nginx and Redis in Docker Compose on one 16-core host; k6 on the
same Docker network. `/api/baseline` is an exempt path — same load balancer, same
JVM, same filter chain, no Redis call — so subtracting it isolates the limiter.

**2,000 req/s offered, 30 s per scenario:**

| scenario | requests | req/s | avg | p50 | p95 |
|---|---|---|---|---|---|
| baseline *(control)* | 60,001 | 2000.0 | 0.66 | 0.47 | 0.94 |
| admitted | 60,001 | 2000.0 | 1.12 | 0.82 | 1.80 |
| rejected | 60,001 | 2000.0 | 1.03 | 0.77 | 1.56 |

**One atomic distributed limit check costs ~0.45 ms mean, 0.6–0.9 ms p95** — and
it is flat from 400 to 2,000 req/s, which is the point: the cost is a round trip,
not a queue. A rejection costs the same as an admission, because one call decides
either way.

Percentiles above p95 are omitted deliberately. Below saturation the p99 tail is
JVM and OS scheduling jitter that the control carries too — early runs produced
*negative* p99 deltas, which is the tell that p99 measures the machine rather than
the limiter.

**Correctness under load**, which matters more than the latency: the 10/sec
endpoint admitted **exactly 300 requests in 30 s** — in both the 400 and the
2,000 req/s run, across two instances. At 2,000 req/s that is 0.5% admitted,
exactly as configured.

**Degradation**, with Redis killed mid-flight at 400 req/s:

| | healthy p99 | degraded p99 | recovered p99 | fail-open 200s | fail-closed 503s | non-503 5xx |
|---|---|---|---|---|---|---|
| hard down | 4.14 | 16.34 | 4.52 | 5,845 | 5,845 | **0** |
| blackhole | 4.73 | 6.10 | 3.39 | 2,800 | 2,800 | **0** |

Two endpoints degraded *differently from configuration alone*, same outage, same
instant. Recovery was unattended — real 429s resumed once Redis returned, with no
restart.

The circuit breaker, isolated against a blackholed Redis with 20 sequential
requests:

```
breaker ON   0.077 0.055 0.060 0.055 0.058 │ 0.006 0.006 0.004 0.005 ...
breaker OFF  0.058 0.057 0.057 0.060 0.059 │ 0.061 0.059 0.058 0.059 ...
                   ↑ the 5 that trip it      ↑ everything after
```

See [`k6/README.md`](k6/README.md) to reproduce any of this.

---

## Algorithms

All three are atomic Lua scripts returning the same
`{allowed, retryAfterMillis, remaining}` reply, parsed in one place so the
contract cannot drift.

| | Token bucket (`tb`) | Sliding window log (`swl`) | Sliding window counter (`swc`) |
|---|---|---|---|
| Redis structure | hash | sorted set of admitted timestamps | hash of two counters |
| Memory per key | O(1) | O(limit) | O(1) |
| Guarantee | bursts up to capacity | exact rolling bound | approximates the log |
| Retry-After | from real token deficit | exact, from the entry that must expire | from the blocking window |

Two details with real reasons behind them:

- **Time is read inside the script** via Redis `TIME`. If each instance used its
  own clock, skew between them would corrupt refill math — a correctness leak
  reintroduced at the time layer after centralising the count. One clock, shared
  by construction.
- **Keys go through `KEYS[n]`, config through `ARGV[n]`**, so Redis Cluster can
  route by shard. The sliding window counter keeps its window id in a hash *field*
  rather than the key for the same reason.

Scripts are loaded once and invoked by SHA via `EVALSHA`, and the connection and
script cache are warmed at startup — otherwise the first request after a deploy
pays the connect and first `EVAL`, which on a tight timeout surfaces as a spurious
503.

---

## Testing

41 tests, all passing.

| Suite | Proves |
|---|---|
| `TokenBucketRaceTest` | 1,000 tasks / 50 threads against capacity 100 → exactly 100 |
| `DistributedRaceTest` | 200 concurrent requests across 2 JVMs → **exactly 100** |
| `SlidingWindowLog/CounterTest` | exact limits, and that rejected requests are never logged |
| `FailureModeTest` | Redis killed mid-flight: fail-open 200s, fail-closed 503s, **zero 500s**, unattended recovery |
| `RuleResolutionTest` | every rung of the ladder, and that a permissive override wins |
| `RuleValidationTest` | each malformed-config case aborts startup |
| `RateLimitGateTest` | the 200/429/503 contract, and that no Redis failure propagates |
| `RedisCircuitBreakerTest` | threshold, consecutive-reset, single probe, reopen and close |

The distributed suites run two full Spring contexts against a real Redis via
Testcontainers.

---

## Not claimed

Run against a single Redis, not a Cluster — the key design is Cluster-compatible
but untested there. Two instances on one host, so no real network partition or
cross-host clock skew. Authentication is a header-reading stand-in occupying
Spring Security's filter-chain position, not Spring Security itself. Throughput
figures are bounded by the rig: past ~5,000 req/s the *control* endpoint degrades
too, which is the host saturating rather than the limiter.
