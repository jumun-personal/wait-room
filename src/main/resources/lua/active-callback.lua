local activeKey = KEYS[1]
local activeTokenKey = KEYS[2]
local userId = ARGV[1]
local activeToken = ARGV[2]
local nowMillis = tonumber(ARGV[3])
local activeTtlSeconds = tonumber(ARGV[4])
local action = ARGV[5]

local storedToken = redis.call('GET', activeTokenKey)
if storedToken == false then
  return 'NOT_FOUND'
end
if storedToken ~= activeToken then
  return 'MISMATCH'
end

if action == 'SUCCESS' then
  redis.call('ZREM', activeKey, userId)
  redis.call('DEL', activeTokenKey)
  return 'REMOVED'
end

if action == 'FAIL' then
  if redis.call('ZSCORE', activeKey, userId) == false then
    return 'NOT_ACTIVE'
  end

  local ttlMillis = activeTtlSeconds * 1000
  local expireAt = nowMillis + ttlMillis

  redis.call('ZADD', activeKey, expireAt, userId)
  redis.call('SET', activeTokenKey, activeToken, 'EX', activeTtlSeconds)
  return 'REFRESHED'
end

return 'INVALID_ACTION'