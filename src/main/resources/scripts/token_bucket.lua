--[[
  Token bucket, executed atomically inside Redis. (Full argument: NOTES.md.)

  ATOMIC because Redis is single-threaded and dispatches a script as ONE command,
  so read + refill + check + decrement + write cannot be interleaved. That is what
  closed the naive HMGET -> compute-in-Java -> HSET race, where two instances read
  the same count and one decrement was lost.

  KEYS[1] not a hardcoded key: Redis must see which keys a script touches to route
  it in Cluster mode. Non-key config travels via ARGV.

  TIME read here, not passed in: refill depends on elapsed time, and two app
  instances with skewed clocks would compute different refills for one bucket. One
  clock, Redis's. (This makes the script non-deterministic, which was fatal when
  old Redis replicated scripts by re-running them; Redis 5+ ships the resulting
  writes instead, so replicas never call TIME.)

  KEYS[1] : bucket key, e.g. ratelimit:tb:user:12345
  ARGV[1] : capacity      (max tokens, also the burst size)
  ARGV[2] : refill rate   (tokens per second)
  ARGV[3] : weight        (tokens this request costs)
  returns : { allowed (1|0), retry_after_ms, remaining whole requests }
            retry_after_ms: 0 when allowed, -1 when no retry can ever succeed.
]]

local key         = KEYS[1]
local capacity    = tonumber(ARGV[1])
local refill_rate = tonumber(ARGV[2])
local weight      = tonumber(ARGV[3]) or 1

-- TIME returns { unix_seconds, microseconds_of_this_second }.
local time = redis.call('TIME')
local now_ms = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)

local stored = redis.call('HMGET', key, 'tokens', 'last_refill_ms')
local tokens
local last_refill_ms

if stored[1] == false or stored[2] == false then
  -- HMGET yields false for a missing field: a new client starts with a full bucket.
  tokens = capacity
  last_refill_ms = now_ms
else
  tokens = tonumber(stored[1])
  last_refill_ms = tonumber(stored[2])
end

-- Lazy refill: credit against the OLD timestamp, THEN advance it, THEN decrement.
-- Crediting first is what stops elapsed time being thrown away unpaid.
local elapsed_ms = now_ms - last_refill_ms
if elapsed_ms < 0 then
  -- Only reachable if an operator moves Redis's clock backwards. A negative
  -- credit would DRAIN the bucket, so refuse to refill rather than punish.
  elapsed_ms = 0
end

tokens = math.min(capacity, tokens + (elapsed_ms / 1000) * refill_rate)
last_refill_ms = now_ms

local allowed = 0
if tokens >= weight then
  tokens = tokens - weight
  allowed = 1
end

-- Stored as strings: readable from redis-cli, and the token count keeps its
-- fraction. %d stops Lua rendering a 13-digit epoch in scientific notation.
redis.call('HSET', key,
  'tokens', tostring(tokens),
  'last_refill_ms', string.format('%d', last_refill_ms))

-- Without a TTL every client ever seen keeps a key forever. Safe to expire: after
-- this long idle the refill would have restored a full bucket anyway, and a
-- missing key starts full.
local ttl_seconds = 3600
if refill_rate > 0 then
  ttl_seconds = math.max(60, math.ceil(capacity / refill_rate) * 2)
end
redis.call('EXPIRE', key, ttl_seconds)

-- Short by (weight - tokens), arriving at refill_rate/sec. Ceil so we never
-- advertise a moment that is fractionally too early.
local retry_after_ms = 0
if allowed == 0 then
  if refill_rate > 0 then
    retry_after_ms = math.ceil(((weight - tokens) / refill_rate) * 1000)
  else
    retry_after_ms = -1 -- never refills; caller omits Retry-After
  end
end

-- Floored: 0.7 of a token buys nothing, and whole numbers survive Lua's
-- number -> Redis integer conversion intact. The STORED count keeps its fraction.
local remaining = math.floor(tokens)

return { allowed, retry_after_ms, remaining }
