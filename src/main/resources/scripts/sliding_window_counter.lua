--[[
  Sliding window counter: the log's rolling bound at O(1) memory.

  Keeps the CURRENT fixed window's count and the PREVIOUS window's count, and
  blends them by how far into the current window we are:

    decay    = 1 - (elapsed_in_current_window / W)
    estimate = current + previous * decay
    allow if estimate + weight <= limit

  The previous window's influence fades to zero smoothly instead of vanishing at
  a reset. That smooth fade is precisely what kills the fixed-window boundary
  burst, where 2x the limit gets through by straddling a reset.

  IT IS AN APPROXIMATION. It assumes the previous window's requests were spread
  evenly across that window. If they were actually bunched at its start or end,
  the estimate is a little high or low. Bounded and small -- that is the accuracy
  traded away for O(1) memory instead of the log's O(limit).

  WHY ONE HASH AND NOT TWO KEYS
  The current window id changes with time, so a per-window key can only be built
  from a clock. Building it in Java means an app-side timestamp (the skew this
  project designs out); building it inside the script means a key Redis cannot
  see for Cluster routing, since routing works off KEYS. One hash at a stable key
  avoids both: KEYS[1] is fixed, and the window id lives in a FIELD.

  Staleness is handled by comparing that stored id against the derived one, not
  by expiry -- a window two or more behind is read as zero rather than as
  current. The 2W TTL is only memory reclamation, never correctness.

  KEYS[1] : hash key, e.g. ratelimit:swc:user:12345
  ARGV[1] : limit
  ARGV[2] : window_ms (W)
  ARGV[3] : weight
  returns : { allowed (1|0), retry_after_ms, remaining }
]]

local key       = KEYS[1]
local limit     = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])
local weight    = tonumber(ARGV[3]) or 1

local time = redis.call('TIME')
local now_ms = (tonumber(time[1]) * 1000) + math.floor(tonumber(time[2]) / 1000)

local window_id    = math.floor(now_ms / window_ms)
local window_start = window_id * window_ms
local elapsed      = now_ms - window_start
local decay        = 1 - (elapsed / window_ms)

local stored = redis.call('HMGET', key, 'window', 'current', 'previous')
local current  = 0
local previous = 0

if stored[1] ~= false then
  local stored_window = tonumber(stored[1])
  if stored_window == window_id then
    current  = tonumber(stored[2]) or 0
    previous = tonumber(stored[3]) or 0
  elseif stored_window == window_id - 1 then
    -- Rolled over exactly one window: what was current becomes previous.
    previous = tonumber(stored[2]) or 0
    current  = 0
  end
  -- Two or more windows stale: both stay 0. Never read an old count as current.
end

local estimate = current + (previous * decay)

local allowed = 0
local retry_after_ms = 0

if estimate + weight <= limit then
  allowed = 1
  current = current + weight
  estimate = estimate + weight
elseif weight > limit then
  retry_after_ms = -1 -- can never fit, whatever we wait for
else
  local headroom = limit - weight - current
  if headroom >= 0 and previous > 0 then
    -- The previous window is the blocker. Solve decay <= headroom/previous:
    --   elapsed_needed = W * (1 - headroom/previous)
    retry_after_ms = math.ceil((window_ms * (1 - (headroom / previous))) - elapsed)
  else
    -- The CURRENT window alone is already over the limit, so no decay of
    -- `previous` can help. Relief starts at the boundary, where `current`
    -- becomes the previous window and begins decaying in turn.
    local to_boundary = window_start + window_ms - now_ms
    local after_boundary = 0
    if current > 0 then
      after_boundary = window_ms * (1 - ((limit - weight) / current))
      if after_boundary < 0 then
        after_boundary = 0
      end
    end
    retry_after_ms = math.ceil(to_boundary + after_boundary)
  end
  if retry_after_ms < 1 then
    retry_after_ms = 1
  end
end

-- %d keeps a 13-digit window id out of scientific notation and keeps the hash
-- readable from redis-cli.
redis.call('HSET', key,
  'window', string.format('%d', window_id),
  'current', string.format('%d', current),
  'previous', string.format('%d', previous))

-- 2W: long enough that a key idle for one whole window is still around to serve
-- as `previous`, short enough that abandoned clients do not accumulate.
redis.call('PEXPIRE', key, window_ms * 2)

-- Ceil the estimate so a fractional 9.2 reports 10 used, not 9 -- remaining must
-- never over-promise.
local remaining = limit - math.ceil(estimate)
if remaining < 0 then
  remaining = 0
end

return { allowed, retry_after_ms, remaining }
