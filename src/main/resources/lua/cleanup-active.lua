local zkey = KEYS[1]
local nowMillis = tonumber(ARGV[1])

local members = redis.call('ZRANGEBYSCORE', zkey, '-inf', nowMillis)
if #members == 0 then
  return 0
end

redis.call('ZREM', zkey, unpack(members))
return #members