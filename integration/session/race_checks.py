"""Real HTTP and production Lua at controlled store boundaries; no fourteen-day wait."""
import concurrent.futures
import hashlib
import json
import threading
import uuid


def verify_races(api, ports, store, proxy):
    request, login, redis = api.request, api.login, api.redis
    check, error, passed = api.check, api.error, api.passed
    first, second = ports
    subject = "race-session"

    # Independent HTTP connections enter together. The resulting index selects exactly one winner.
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        gate = threading.Barrier(2)
        def enter(port):
            gate.wait()
            return login(port, subject)
        sessions = list(pool.map(enter, ports))
    outcomes = [request(first, "GET", "/auth/users/me", jar)[0] for jar in sessions]
    check(sorted(outcomes) == [200, 401], "concurrent login has exactly one winner")
    current = sessions[outcomes.index(200)]
    keys = api.session_keys(store, current)
    with concurrent.futures.ThreadPoolExecutor(2) as pool:
        gate = threading.Barrier(2)
        def activity(port):
            gate.wait()
            return request(port, "GET", "/auth/users/me", current)
        responses = list(pool.map(activity, ports))
    check(all(r[0] == 200 and api.cookie_values(r) == current for r in responses), "concurrent same-ID activity")
    api.expiry(store, keys)

    # GET has completed on the store; hold that exact response until replacement/revocation completes.
    for mode in ("replace", "revoke"):
        old = login(first, subject)
        old_keys = api.session_keys(store, old)
        mutation = {}
        def mutate():
            if mode == "replace":
                mutation["new"] = login(second, subject)
            else:
                mutation["revoked"] = request(second, "POST", "/auth/sessions/revoke", old)
        proxy.after("GET", mutate)
        error(request(first, "GET", "/auth/users/me", old), 401, "SESSION_INVALID", "RELOGIN")
        if mode == "replace":
            check(request(second, "GET", "/auth/users/me", mutation["new"])[0] == 200,
                  "new session survives preliminary old GET")
        else:
            check(mutation["revoked"][2]["session_revocation"] == "confirmed", "barrier revoke confirmed")
            check(redis(store, "EXISTS", *old_keys[:2]) == 0, "activity cannot recreate revoked records")

    # Complete the opposite order explicitly, with both instance orders.
    for a, b in (ports, tuple(reversed(ports))):
        old = login(a, subject)
        check(request(b, "GET", "/auth/users/me", old)[0] == 200, "activity before replacement")
        new = login(b, subject)
        error(request(a, "GET", "/auth/users/me", old), 401, "SESSION_INVALID", "RELOGIN")
        request(a, "POST", "/auth/sessions/revoke", old)
        check(request(b, "GET", "/auth/users/me", new)[0] == 200, "replacement before old revoke")
        request(a, "POST", "/auth/sessions/revoke", new)
        newer = login(b, subject)
        check(request(a, "GET", "/auth/users/me", newer)[0] == 200, "old revoke before replacement")
        request(b, "POST", "/auth/sessions/revoke", newer)
        error(request(a, "GET", "/auth/users/me", newer), 401, "SESSION_INVALID", "RELOGIN")
    passed("independent login/activity and both replacement/revocation orders, held preliminary GET")

    for corruption in ("unequal", "persistent", "schema"):
        jar = login(first, "corrupt-" + corruption)
        keys = api.session_keys(store, jar)
        if corruption == "unequal":
            redis(store, "PEXPIRE", keys[1], 30000)
        elif corruption == "persistent":
            redis(store, "PERSIST", keys[1])
        else:
            value = json.loads(redis(store, "GET", keys[1]))
            value["schema_version"] = 999
            redis(store, "SET", keys[1], json.dumps(value), "KEEPTTL")
        before = redis(store, "PEXPIRETIME", keys[0])
        for port in ports:
            error(request(port, "GET", "/auth/users/me", jar), 503, "SESSION_UNAVAILABLE", "RETRY_LATER")
        check(redis(store, "PEXPIRETIME", keys[0]) == before, "corruption never renews")

    jar = login(first, "idle-boundary")
    keys = api.session_keys(store, jar)
    before = api.expiry(store, keys)
    for port in ports:
        check(request(port, "GET", "/health", jar)[0] == 200, "public health")
        request(port, "OPTIONS", "/auth/users/me", jar, headers={"Access-Control-Request-Method": "GET"})
    check(api.expiry(store, keys) == before, "public and preflight do not renew")
    # Store time and expiry adjustment occur in the same Lua invocation as the production verifier.
    script = (api.ROOT / "src/main/resources/redis/verify-session.lua").read_text()
    raw = [redis(store, "GET", key) for key in keys[:2]]
    digest = hashlib.sha256(jar["ls_session"].encode()).hexdigest()
    for offset, expected in ((1, keys[2]), (0, "INVALID"), (-1, "INVALID")):
        for key, value in zip(keys, raw):
            redis(store, "SET", key, value, "PX", 30000)
        prefix = "local t=redis.call('TIME'); local n=t[1]*1000+math.floor(t[2]/1000); "
        prefix += "redis.call('PEXPIREAT',KEYS[1],n+%d); redis.call('PEXPIREAT',KEYS[2],n+%d); " % (offset, offset)
        result = redis(store, "EVAL", prefix + script, 2, *keys[:2], keys[2], digest)
        check(result[0] == expected, "exact idle expiry boundary")
        if offset == 1:
            check(int(result[1]) > before - 5000, "before boundary renews")
        else:
            for port in ports:
                error(request(port, "GET", "/auth/users/me", jar), 401, "SESSION_INVALID", "RELOGIN")
    jar = login(first, "domain-error-renewal")
    keys = api.session_keys(store, jar)
    for key in keys[:2]:
        redis(store, "PEXPIREAT", key, before - 1209500000)
    response = request(second, "PATCH", "/auth/users/me", jar, {"display_name": ""})
    check(response[0] == 400, "account validation business error")
    api.check_renewal(response, jar, store, keys)
    passed("TTL mismatch/persistence/schema rejection, exact expiry ±1ms, public non-renewal and domain-error renewal")
