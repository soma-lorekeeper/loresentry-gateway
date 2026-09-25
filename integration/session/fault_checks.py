"""Real driver/network failures, bounded revocation, ACL and sensitive-value checks."""
import hashlib
import json
import secrets
import socket
import time
from redis_proxy import frame


def verify_faults(api, ports, store, proxy, auth_proxy, counts, auth_source):
    request, login, redis = api.request, api.login, api.redis
    check, error, passed = api.check, api.error, api.passed
    first, second = ports
    jar = login(first, "faults")
    keys = api.session_keys(store, jar)

    def disconnect():
        raise OSError("injected connection loss")

    # Each fault is applied to the real Lettuce connection. Late headers never authenticate.
    for command, phase, action in (("AUTH", "before", lambda: time.sleep(.75)),
                                   ("GET", "after", lambda: time.sleep(.75)),
                                   ("EVAL", "after", lambda: time.sleep(.75)),
                                   ("EVAL", "after", disconnect),
                                   ("EVAL", "before", disconnect)):
        before_calls = counts.copy()
        before_eval = proxy.counts["EVAL"]
        proxy.arm(command, action, phase)
        started = time.monotonic()
        response = request(first, "GET", "/auth/users/me", jar)
        elapsed = time.monotonic() - started
        error(response, 503, "SESSION_UNAVAILABLE", "RETRY_LATER")
        check(elapsed < .9, "BFF failure within 500ms budget plus HTTP scheduling tolerance")
        if command in ("AUTH", "GET") or action != disconnect:
            check(elapsed >= .35, "delayed request exercised deadline")
        time.sleep(.3)
        check(counts == before_calls, "failed verification never reaches internal service")
        check(proxy.counts["EVAL"] - before_eval == (1 if command == "EVAL" else 0),
              "no EVAL replay after delay or disconnect")
        check(request(second, "GET", "/auth/users/me", jar)[0] == 200, "new request recovers with fresh connection")
    passed("real driver connection/GET/EVAL delays and response loss: bounded fail-closed, no replay, fresh-request recovery")

    # Authenticate using the same restricted account, preserving RESP errors without printing values.
    def denied(*args):
        def send(sock, values):
            parts = [str(x).encode() for x in values]
            sock.sendall(b"*%d\r\n" % len(parts) + b"".join(b"$%d\r\n" % len(x) + x + b"\r\n" for x in parts))
        with socket.create_connection(("127.0.0.1", store), timeout=2) as sock:
            stream = sock.makefile("rb")
            send(sock, ["AUTH", "bff", "bff-test-password"])
            check(frame(stream)[0].startswith(b"+OK"), "restricted credential initialization")
            send(sock, args)
            check(frame(stream)[0].startswith(b"-NOPERM"), "restricted command/key denied")
    denied("SET", keys[0], "blocked")
    denied("DEL", keys[0])
    denied("GET", "auth:oauth:forbidden")
    redis(store, "ACL", "SETUSER", "bff", "-pexpireat")
    before = api.expiry(store, keys)
    try:
        for port in ports:
            error(request(port, "GET", "/auth/users/me", jar), 503, "SESSION_UNAVAILABLE", "RETRY_LATER")
        check(api.expiry(store, keys) == before, "ACL error cannot renew")
    finally:
        redis(store, "ACL", "SETUSER", "bff", "+pexpireat")

    # Lua runtime errors do not roll back earlier writes; unequal records must fail closed.
    script = (api.ROOT / "src/main/resources/redis/verify-session.lua").read_text()
    broken = script.replace("if redis.call('PEXPIREAT', KEYS[2], expires) ~= 1 then return {'UNAVAILABLE'} end",
                            "error('injected partial renewal')")
    check(broken != script, "partial renewal injection point")
    digest = hashlib.sha256(jar["ls_session"].encode()).hexdigest()
    time.sleep(.005)
    try:
        redis(store, "EVAL", broken, 2, *keys[:2], keys[2], digest)
        raise AssertionError("partial renewal must fail")
    except AssertionError as failure:
        check(str(failure) == "fixture Redis command rejected", "injected runtime failure")
    for port in ports:
        error(request(port, "GET", "/auth/users/me", jar), 503, "SESSION_UNAVAILABLE", "RETRY_LATER")
    # New subject avoids intentionally corrupted prior state.
    old = login(first, "partial-login")
    old_keys = api.session_keys(store, old)
    new_id = api.b64(secrets.token_bytes(32))
    api.secret_values.add(new_id)
    new_hash = hashlib.sha256(new_id.encode()).hexdigest()
    new_key = "auth:session:{login}:by-id:" + new_hash
    script = (auth_source / "src/main/resources/redis/login-session.lua").read_text()
    broken = script.replace("redis.call('SET', KEYS[2], index, 'PXAT', expiry)", "error('injected partial login')")
    check(script != broken, "partial login injection point")
    try:
        redis(store, "EVAL", broken, 2, new_key, old_keys[1], old_keys[2], new_hash)
        raise AssertionError("partial login must fail")
    except AssertionError as failure:
        check(str(failure) == "fixture Redis command rejected", "injected login runtime failure")
    check(redis(store, "EXISTS", new_key) == 1, "partial login orphan exists")
    error(request(first, "GET", "/auth/users/me", {"ls_session": new_id}), 401, "SESSION_INVALID", "RELOGIN")
    check(request(second, "GET", "/auth/users/me", old)[0] == 200, "partial login preserves old index")
    passed("restricted ACL and partial Lua writes cannot authenticate incomplete state")

    # Auth shares a reconnecting driver; lost login response must not replay its session EVAL.
    prepared = request(first, "GET", "/auth/oauth/google/prepare")
    query = api.urllib.parse.parse_qs(api.urllib.parse.urlsplit(prepared[1]["Location"]).query)
    code = api.b64(json.dumps({"subject": "lost-login", "nonce": query["nonce"][0],
                              "challenge": query["code_challenge"][0]}).encode())
    before_eval = auth_proxy.counts["EVAL"]
    auth_proxy.after("EVAL", disconnect)
    response = request(first, "GET", "/auth/oauth/google/callback?" + api.urllib.parse.urlencode(
        {"state": query["state"][0], "code": code}), api.cookie_values(prepared))
    check(response[0] == 303 and "result=success" not in response[1]["Location"], "unknown login is not success")
    check("ls_session" not in api.cookie_values(response), "unknown login issues no session cookie")
    time.sleep(.6)
    check(auth_proxy.counts["EVAL"] == before_eval + 1, "Auth reconnect does not replay login EVAL")
    jar = login(first, "bounded-revoke")
    keys = api.session_keys(store, jar)
    before_eval = auth_proxy.counts["EVAL"]
    before_calls = counts[("POST", "/auth/sessions/revoke")]
    remaining = [3]
    def stalled_get():
        remaining[0] -= 1
        if remaining[0]:
            auth_proxy.after("GET", stalled_get)
        time.sleep(.75)
    auth_proxy.after("GET", stalled_get)
    started = time.monotonic()
    response = request(first, "POST", "/auth/sessions/revoke", jar)
    elapsed = time.monotonic() - started
    check(response[0] == 503 and response[2]["session_revocation"] == "unconfirmed", "bounded revocation unknown result")
    check(not api.cookie_values(response) and bool(response[1].get_all("Set-Cookie")), "unknown revocation still clears browser cookie")
    check(elapsed <= 2.3, "Auth two-second budget plus HTTP scheduling tolerance")
    check(counts[("POST", "/auth/sessions/revoke")] == before_calls + 1, "BFF never retries Auth HTTP")
    time.sleep(1)
    check(auth_proxy.counts["EVAL"] == before_eval, "late revoke GET cannot begin deletion")
    check(redis(store, "EXISTS", *keys[:2]) == 2, "unconfirmed pre-write revocation retains records")
    passed("Auth reconnect without login replay and bounded revocation unknown result with cookie deletion")

    # Application logs must not expose IDs or fixture credentials; expected cookie headers are excluded.
    forbidden = api.secret_values | {"bff-test-password", "isolated-test", "fixture-only"}
    for name in api.containers:
        if name.endswith("-auth") or "-bff-" in name:
            output = api.subprocess.run(["docker", "logs", name], text=True, capture_output=True, check=True)
            logs = output.stdout + output.stderr
            check(not any(value in logs for value in forbidden), "application logs exclude session IDs and credentials")
    passed("application logs contain no issued raw session IDs or fixture secrets")
