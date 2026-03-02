local zkey = KEYS[1]
local now  = tonumber(ARGV[1])
local ttl  = tonumber(ARGV[2])
local threshold = now - ttl

local members = redis.call('ZRANGEBYSCORE', zkey, '-inf', threshold)
if #members == 0 then
  return 0
end

redis.call('ZREM', zkey, unpack(members))
return #members
