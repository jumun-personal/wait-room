local activeKey   = KEYS[1]
local waitingKey  = KEYS[2]
local pollTrackerKey = KEYS[3]
local userId      = ARGV[1]
local nowMillis   = ARGV[2]
local maxTokens   = tonumber(ARGV[3])
local activeToken = ARGV[4]
local activeMetaTtl = tonumber(ARGV[5])

-- 이미 활성 상태면 바로 허용 (ZSET: ZSCORE로 존재 확인)
if redis.call('ZSCORE', activeKey, userId) ~= false then
    return 'ALREADY_ACTIVE'
end

redis.call('ZADD', pollTrackerKey, nowMillis, userId)

-- 대기열 순위 확인
local rank = redis.call('ZRANK', waitingKey, userId)
if rank == false then
    return 'NOT_IN_QUEUE'
end

-- 입장 가능 여부 (ZSET: ZCARD로 카운트)
local activeCount = redis.call('ZCARD', activeKey)
local available = maxTokens - activeCount
if rank < available then
    redis.call('ZREM', waitingKey, userId)
    redis.call('ZREM', pollTrackerKey, userId)
    redis.call('ZADD', activeKey, nowMillis, userId)
    redis.call('EXPIRE', KEYS[4], activeMetaTtl)
    return 'ADMITTED:' .. activeToken
end

return tostring(rank)
