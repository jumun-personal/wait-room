local activeKey  = KEYS[1]
local nowMillis  = tonumber(ARGV[1])
local ttlMillis  = tonumber(ARGV[2])

local expireThreshold = nowMillis - ttlMillis
return redis.call('ZREMRANGEBYSCORE', activeKey, '-inf', expireThreshold)
