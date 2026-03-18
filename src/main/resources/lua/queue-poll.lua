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

-- 마지막 poll 시각 갱신
redis.call('ZADD', pollTrackerKey, nowMillis, userId)

-- 대기열 포함 여부 및 순번 확인
local rank = redis.call('ZRANK', waitingKey, userId)
if rank == false then
    return 'NOT_IN_QUEUE'
end

-- 남은 active 슬롯 계산
local activeCount = redis.call('ZCARD', activeKey)
local available = maxTokens - activeCount

-- 입장 가능 여부 판단
if rank < available then
    redis.call('ZREM', waitingKey, userId)
    redis.call('ZREM', pollTrackerKey, userId)
    redis.call('ZADD', activeKey, expireAt, userId)
    redis.call('SET', activeTokenKey, activeToken, 'EX', activeTtlSeconds)
    return 'ADMITTED:' .. activeToken
end

return tostring(rank)