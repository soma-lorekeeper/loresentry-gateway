-- Recheck both records and TTLs before renewal; PEXPIREAT cannot recreate a missing key.
local function hash(value)
    return type(value) == 'string' and #value == 64 and value:match('^[0-9a-f]+$') ~= nil
end
local function uuid(value)
    return type(value) == 'string' and #value == 36
        and value:match('^%x%x%x%x%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%-%x%x%x%x%x%x%x%x%x%x%x%x$') ~= nil
        and value == value:lower()
end
local function current(raw)
    if raw:find(string.char(92), 1, true) then return nil end
    local ok, value = pcall(cjson.decode, raw)
    if not ok or type(value) ~= 'table' or value.schema_version ~= 2 or not hash(value.session_hash) then return nil end
    local count, seen = 0, {}
    for key in raw:gmatch('"([^"\\]+)"%s*:') do
        if seen[key] or (key ~= 'schema_version' and key ~= 'session_hash') then return nil end
        seen[key] = true
        count = count + 1
    end
    local fields = 0
    for _ in pairs(value) do fields = fields + 1 end
    if count ~= 2 or fields ~= 2 then return nil end
    return value
end
local function byId(raw)
    if raw:find(string.char(92), 1, true) then return nil end
    local ok, value = pcall(cjson.decode, raw)
    if not ok or type(value) ~= 'table' or value.schema_version ~= 2 or not uuid(value.user_id)
        or type(value.created_at) ~= 'number' or value.created_at % 1 ~= 0
        or value.created_at <= 0 or value.created_at > 253402300799 then return nil end
    local count, seen = 0, {}
    for key in raw:gmatch('"([^"\\]+)"%s*:') do
        if seen[key] or (key ~= 'schema_version' and key ~= 'user_id' and key ~= 'created_at') then return nil end
        seen[key] = true
        count = count + 1
    end
    local fields = 0
    for _ in pairs(value) do fields = fields + 1 end
    if count ~= 3 or fields ~= 3 then return nil end
    return value
end
local user, digest = ARGV[1], ARGV[2]
if #KEYS ~= 2 or #ARGV ~= 2 or not uuid(user) or not hash(digest)
    or KEYS[1] ~= 'auth:session:{login}:by-id:' .. digest
    or KEYS[2] ~= 'auth:session:{login}:by-user:' .. user then return {'UNAVAILABLE'} end
local raw = redis.pcall('GET', KEYS[1])
local indexed = redis.pcall('GET', KEYS[2])
if type(raw) == 'table' or type(indexed) == 'table' then return {'UNAVAILABLE'} end
if not raw or not indexed then return {'INVALID'} end
local record, index = byId(raw), current(indexed)
if not record or not index or record.user_id ~= user then return {'UNAVAILABLE'} end
local idTtl, userTtl = redis.call('PTTL', KEYS[1]), redis.call('PTTL', KEYS[2])
if idTtl == -1 or userTtl == -1 then return {'UNAVAILABLE'} end
if idTtl <= 0 or userTtl <= 0 or index.session_hash ~= digest then return {'INVALID'} end
if idTtl ~= userTtl then return {'UNAVAILABLE'} end
local time = redis.call('TIME')
local seconds = tonumber(time[1])
local expiry = seconds * 1000 + math.floor(tonumber(time[2]) / 1000) + 1209600000
if seconds <= 0 or expiry > 253402300799999 then return {'UNAVAILABLE'} end
local expires = string.format('%.0f', expiry)
if redis.call('PEXPIREAT', KEYS[1], expires) ~= 1 then return {'UNAVAILABLE'} end
if redis.call('PEXPIREAT', KEYS[2], expires) ~= 1 then return {'UNAVAILABLE'} end
return {user, expires}
