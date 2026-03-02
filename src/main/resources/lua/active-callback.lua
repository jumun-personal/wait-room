local activeKey = KEYS[1]
local activeMetaKey = KEYS[2]
local userId = ARGV[1]
local nowMillis = tonumber(ARGV[2])
local activeTtlSeconds = tonumber(ARGV[3])
local action = ARGV[4]

local storedUserId = redis.call('HGET', activeMetaKey, 'userId')
if storedUserId == false then
  return 'NOT_FOUND'
end
if storedUserId ~= userId then
  return 'MISMATCH'
end

if action == 'SUCCESS' then
  redis.call('ZREM', activeKey, userId)
  redis.call('DEL', activeMetaKey)
  return 'REMOVED'
end

if action == 'FAIL' then
  if redis.call('ZSCORE', activeKey, userId) == false then
    return 'NOT_ACTIVE'
  end
  redis.call('ZADD', activeKey, nowMillis, userId)
  redis.call('EXPIRE', activeMetaKey, activeTtlSeconds)
  return 'REFRESHED'
end

return 'INVALID_ACTION'
