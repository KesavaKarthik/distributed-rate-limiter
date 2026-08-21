--[[
  Sliding window log: at most `limit` requests in ANY trailing window of length W.
  Exact -- no boundary burst, because there is no boundary.

  State is a ZSET per key: one member per ADMITTED request, scored with the
  request's timestamp. "Sliding" is not a background job; it is step 1 below.

  ORDER IS THE ALGORITHM -- and all of it is one script:
    1. ZREMRANGEBYSCORE  drop entries older than W. THIS is the slide.
    2. ZCARD             count what survived.
    3. compare           count + weight <= limit ?
    4. ZADD              only if allowed.
    5. PEXPIRE           so idle keys do not leak.

  Splitting prune/count/add across round trips reintroduces exactly the
  read-modify-write race the token bucket already proved: two instances both
  read count = limit-1 and both add, admitting limit+1.

  A DENIED REQUEST IS NEVER LOGGED (step 4 is inside the branch). Otherwise a
  client that retries keeps pushing its own oldest-entry-expiry forward and locks
  itself out indefinitely.

  WHY THE MEMBER NEEDS A NONCE
  A ZSET member is unique, so two requests logged under the same member collapse
  into one entry and the limit silently inflates. Millisecond scores collide
  easily under load. Redis seeds Lua's PRNG deterministically per script run to
  keep scripts replication-safe, so math.random cannot supply uniqueness -- the
  nonce is passed in. It carries no clock meaning, so it cannot reintroduce the
  skew that keeping time in Redis exists to prevent.

  KEYS[1] : zset key, e.g. ratelimit:swl:user:12345
  ARGV[1] : limit      (max admitted requests per window)
  ARGV[2] : window_ms  (W)
  ARGV[3] : weight     (slots this request costs)
  ARGV[4] : nonce      (unique per request)
  returns : { allowed (1|0), retry_after_ms, remaining }
            retry_after_ms is EXACT here -- the log knows when each entry expires.
]]

local key       = KEYS[1]
local limit     = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local weight    = tonumber(ARGV[3]) or 1
local nonce     = ARGV[4]

local time = redis.call('TIME')
local now_ms = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)
local now_us = (tonumber(time[1]) * 1000000) + tonumber(time[2])

-- 1. Slide. Scores <= now - W are outside the trailing window (now-W, now].
--    %d formatting matters: a 13-digit epoch would otherwise reach Redis in
--    scientific notation and the range would be meaningless.
redis.call('ZREMRANGEBYSCORE', key, '-inf', string.format('%d', now_ms - window_ms))

-- 2. Count what is genuinely inside the window.
local count = redis.call('ZCARD', key)

local allowed = 0
local retry_after_ms = 0

-- 3/4. Decide, and log ONLY on success.
if count + weight <= limit then
  allowed = 1
  for i = 1, weight do
    redis.call('ZADD', key, string.format('%d', now_ms),
      string.format('%d', now_us) .. '-' .. nonce .. '-' .. i)
  end
  count = count + weight
else
  -- Exact retry: we must free (count + weight - limit) slots, so the blocker is
  -- the Nth-oldest entry, which leaves the window W after its own timestamp.
  -- This precision is the log's real advantage -- a counter can only estimate it.
  local need = count + weight - limit
  if need > count then
    -- weight alone exceeds the limit: no amount of waiting helps.
    retry_after_ms = -1
  else
    local entry = redis.call('ZRANGE', key, need - 1, need - 1, 'WITHSCORES')
    if entry and entry[2] then
      retry_after_ms = (tonumber(entry[2]) + window_ms) - now_ms
      if retry_after_ms < 1 then
        retry_after_ms = 1
      end
    else
      retry_after_ms = -1
    end
  end
end

-- 5. Bound memory. W from now is enough: the newest entry any future call could
--    care about was scored `now`, and every call refreshes this.
redis.call('PEXPIRE', key, window_ms)

local remaining = limit - count
if remaining < 0 then
  remaining = 0
end

return { allowed, retry_after_ms, remaining }
