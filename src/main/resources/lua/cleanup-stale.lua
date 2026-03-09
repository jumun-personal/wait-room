local waitingKey = KEYS[1]
local pollTrackerKey = KEYS[2]
local nowMillis  = tonumber(ARGV[1])
local threshold  = tonumber(ARGV[2])
local batchSize  = tonumber(ARGV[3])

local expireThreshold = nowMillis - threshold
local staleMembers = redis.call('ZRANGEBYSCORE', pollTrackerKey, '-inf', expireThreshold, 'LIMIT', 0, batchSize)

local removed = 0
for _, userId in ipairs(staleMembers) do
    redis.call('ZREM', waitingKey, userId)
    redis.call('ZREM', pollTrackerKey, userId)
    removed = removed + 1
end
return removed
