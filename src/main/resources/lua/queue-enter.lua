local activeKey = KEYS[1]
local waitingKey = KEYS[2]
local pollTrackerKey = KEYS[3]
local activeTokenKey = KEYS[4]
local userId = ARGV[1]
local nowMillis = tonumber(ARGV[2])
local maxTokens = tonumber(ARGV[3])
local activeToken = ARGV[4]
local activeTtlSeconds = tonumber(ARGV[5])

local ttlMillis = activeTtlSeconds * 1000
local expireAt = nowMillis + ttlMillis

-- 만료된 active 정리
redis.call('ZREMRANGEBYSCORE', activeKey, '-inf', nowMillis)

-- 이미 active 상태인지 확인
local existingToken = redis.call('GET', activeTokenKey)
if redis.call('ZSCORE', activeKey, userId) ~= false then
    if existingToken then
        return 'ALREADY_ACTIVE:' .. existingToken
    end
    return 'ALREADY_ACTIVE'
end

-- 이미 대기열에 있는지 확인
local existingRank = redis.call('ZRANK', waitingKey, userId)
if existingRank ~= false then
    return 'QUEUED:' .. tostring(existingRank)
end

-- active 슬롯 여유 확인
local activeCount = redis.call('ZCARD', activeKey)
if activeCount < maxTokens then
    redis.call('ZADD', activeKey, expireAt, userId)
    redis.call('SET', activeTokenKey, activeToken, 'EX', activeTtlSeconds)
    return 'ACTIVE:' .. activeToken
end

-- 대기열 등록
redis.call('ZADD', waitingKey, nowMillis, userId)
redis.call('ZADD', pollTrackerKey, nowMillis, userId)

local rank = redis.call('ZRANK', waitingKey, userId)
return 'QUEUED:' .. tostring(rank)