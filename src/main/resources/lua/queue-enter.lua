local activeKey   = KEYS[1]
local waitingKey  = KEYS[2]
local pollTrackerKey = KEYS[3]
local userId      = ARGV[1]
local nowMillis   = ARGV[2]
local maxTokens   = tonumber(ARGV[3])
local activeToken = ARGV[4]
local activeMetaTtl = tonumber(ARGV[5])

-- 이미 활성 상태인가? (ZSET: ZSCORE로 존재 확인)
if redis.call('ZSCORE', activeKey, userId) ~= false then
    return 'ALREADY_ACTIVE'
end

-- 이미 대기열에 있는가?
local existingRank = redis.call('ZRANK', waitingKey, userId)
if existingRank ~= false then
    return 'QUEUED:' .. tostring(existingRank)
end

-- 토큰 여유가 있는가? (ZSET: ZCARD로 카운트)
local activeCount = redis.call('ZCARD', activeKey)
if activeCount < maxTokens then
    redis.call('ZADD', activeKey, nowMillis, userId)
    redis.call('HSET', KEYS[4], 'userId', userId)
    redis.call('EXPIRE', KEYS[4], activeMetaTtl)
    return 'ACTIVE:' .. activeToken
end

-- 대기열에 추가
redis.call('ZADD', waitingKey, nowMillis, userId)
redis.call('ZADD', pollTrackerKey, nowMillis, userId)
local rank = redis.call('ZRANK', waitingKey, userId)
return 'QUEUED:' .. tostring(rank)
