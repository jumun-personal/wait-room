local waitingKey = KEYS[1]
local pollTrackerKey = KEYS[2]
local metaPrefix = ARGV[1]
local nowMillis  = tonumber(ARGV[2])
local threshold  = tonumber(ARGV[3])
local batchSize  = tonumber(ARGV[4])

local expireThreshold = nowMillis - threshold
local staleMembers = redis.call('ZRANGEBYSCORE', pollTrackerKey, '-inf', expireThreshold, 'LIMIT', 0, batchSize)

local removed = 0
for _, userId in ipairs(staleMembers) do
    redis.call('ZREM', waitingKey, userId)
    redis.call('ZREM', pollTrackerKey, userId)
    redis.call('DEL', metaPrefix .. userId)
    removed = removed + 1
end
return removed
