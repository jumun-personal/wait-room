local activeKey = KEYS[1]
local waitingKey = KEYS[2]
local pollTrackerKey = KEYS[3]
local activeTokenKey = KEYS[4]
local userId = ARGV[1]
local nowMillis = tonumber(ARGV[2])
local activeToken = ARGV[3]
local activeTtlSeconds = tonumber(ARGV[4])

-- 만료된 active 정리
redis.call('ZREMRANGEBYSCORE', activeKey, '-inf', nowMillis)

-- 이미 active 상태인지 확인
local activeExpireAt = redis.call('ZSCORE', activeKey, userId)
if activeExpireAt ~= false then
    local existingToken = redis.call('GET', activeTokenKey)
    if existingToken then
        return 'ALREADY_ACTIVE:' .. existingToken
    end

    local remainingMillis = tonumber(activeExpireAt) - nowMillis
    if remainingMillis > 0 then
        local remainingSeconds = math.max(1, math.ceil(remainingMillis / 1000))
        redis.call('SET', activeTokenKey, activeToken, 'EX', remainingSeconds)
        return 'ALREADY_ACTIVE:' .. activeToken
    end
end

local existingRank = redis.call('ZRANK', waitingKey, userId)
if existingRank == false then
    redis.call('ZADD', waitingKey, nowMillis, userId)
end

redis.call('ZADD', pollTrackerKey, nowMillis, userId)

local rank = redis.call('ZRANK', waitingKey, userId)
return 'QUEUED:' .. tostring(rank)
