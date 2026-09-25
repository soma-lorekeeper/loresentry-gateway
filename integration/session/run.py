#!/usr/bin/env python3
"""Isolated real Auth/PostgreSQL/Redis or Valkey + two BFF processes. No production credentials.

Requires Linux, Docker, Python 3 and a local Auth Git checkout. Only the
external Google provider is simulated; Auth's OIDC/DB/session code is real.
"""
import argparse
import base64
import collections
import hashlib
import http.client
import http.cookies
import http.server
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[2]
AUTH_REF = "980a27e4935cdc6f7bc1e15940368842dd15295f"
CONTENT_REF = "d26a3d3a244bdebb79375290b9d032f232da5563"
JAVA = "eclipse-temurin:21-jdk-alpine"
containers = []
results = []


def command(*args, **kwargs):
    return subprocess.run(args, check=True, text=True, capture_output=True, **kwargs).stdout.strip()


def check(condition, label):
    if not condition:
        raise AssertionError(label)  # Never include cookies, tokens, payloads or credentials.


def passed(label):
    results.append(label)
    print("PASS " + label, flush=True)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def docker(name, *args):
    command("docker", "run", "-d", "--name", name, *args)
    containers.append(name)
    return name


def published_port(name, port):
    return int(command("docker", "port", name, str(port)).rsplit(":", 1)[1])


def request(port, method, path, cookies=None, body=None, headers=None):
    fields = {"Origin": "http://localhost:3000"}
    if method in ("POST", "PUT", "PATCH", "DELETE"):
        fields["X-LS-CSRF"] = "1"
    if cookies:
        fields["Cookie"] = "; ".join(k + "=" + v for k, v in cookies.items())
    if body is not None:
        fields["Content-Type"] = "application/json"
    fields.update(headers or {})
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=15)
    try:
        conn.request(method, path, json.dumps(body) if body is not None else None, fields)
        response = conn.getresponse()
        data = response.read()
        return response.status, response.headers, json.loads(data) if data else None
    finally:
        conn.close()


def wait_http(port):
    for _ in range(150):
        try:
            if request(port, "GET", "/health")[0] == 200:
                return
        except (OSError, http.client.HTTPException):
            pass
        time.sleep(0.2)
    raise AssertionError("service startup timed out; inspect isolated logs")


def cookie_values(response):
    jar = http.cookies.SimpleCookie()
    for value in response[1].get_all("Set-Cookie", []):
        jar.load(value)
    return {key: value.value for key, value in jar.items() if value.value}


def error(response, status, code, action):
    check(response[0] == status, code + " status")
    check(response[2].get("code") == code, code + " code")
    check(response[2].get("next_action") == action, code + " action")
    check(not response[1].get_all("Set-Cookie"), code + " must preserve cookies")


def protected_error(port, cookies, status, code, action):
    for path in ("/auth/users/me", "/projects"):
        error(request(port, "GET", path, cookies), status, code, action)


def redis(port, *args):
    def read(stream):
        kind = stream.read(1)
        line = stream.readline().rstrip(b"\r\n")
        if kind == b"-":
            raise AssertionError("fixture Redis command rejected")
        if kind == b"$":
            size = int(line)
            if size == -1:
                return None
            data = stream.read(size)
            stream.read(2)
            return data.decode()
        if kind == b"*":
            return [read(stream) for _ in range(int(line))]
        if kind == b":":
            return int(line)
        return line.decode()
    with socket.create_connection(("127.0.0.1", port), timeout=3) as sock:
        encoded = [str(arg).encode() for arg in args]
        sock.sendall(b"*%d\r\n" % len(encoded) + b"".join(
            b"$%d\r\n" % len(arg) + arg + b"\r\n" for arg in encoded))
        return read(sock.makefile("rb"))


def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=").decode()


def login(port, subject):
    prepared = request(port, "GET", "/auth/oauth/google/prepare")
    check(prepared[0] == 302, "prepare redirect")
    location = urllib.parse.urlsplit(prepared[1]["Location"])
    check(location.hostname == "accounts.google.com", "fixed Google destination")
    query = urllib.parse.parse_qs(location.query)
    code = b64(json.dumps({"subject": subject, "nonce": query["nonce"][0],
                           "challenge": query["code_challenge"][0]}).encode())
    callback = request(port, "GET", "/auth/oauth/google/callback?" + urllib.parse.urlencode(
        {"state": query["state"][0], "code": code}), cookie_values(prepared))
    check(callback[0] == 303 and callback[1]["Location"] == "http://localhost:3000/login?result=success",
          "callback success redirect")
    jar = cookie_values(callback)
    check(set(jar) == {"ls_session"}, "callback auth cookie names")
    check(callback[1]["Cache-Control"] == "no-store", "callback no-store")
    return jar


def session_keys(redis_port, cookies):
    raw = cookies["ls_session"]
    check(len(raw) == 43 and b64(base64.urlsafe_b64decode(raw + "=")) == raw,
          "canonical 256-bit session ID")
    digest = hashlib.sha256(raw.encode("ascii")).hexdigest()
    by_id = "auth:session:{login}:by-id:" + digest
    record = json.loads(redis(redis_port, "GET", by_id))
    check(record["schema_version"] == 2, "session schema")
    by_user = "auth:session:{login}:by-user:" + record["user_id"]
    index = json.loads(redis(redis_port, "GET", by_user))
    check(index == {"schema_version": 2, "session_hash": digest}, "hash-only current index")
    check(raw not in json.dumps([record, index]), "raw ID absent from records")
    return by_id, by_user, record["user_id"]


def expiry(redis_port, keys):
    values = [redis(redis_port, "PEXPIRETIME", key) for key in keys[:2]]
    check(values[0] == values[1] and values[0] > 0, "identical absolute expiry")
    return values[0]


def check_renewal(response, cookies, redis_port, keys):
    check(cookie_values(response) == cookies, "activity keeps same session ID")
    jar = http.cookies.SimpleCookie()
    for value in response[1].get_all("Set-Cookie", []):
        jar.load(value)
    seconds, micros = redis(redis_port, "TIME")
    remaining = expiry(redis_port, keys) - int(seconds) * 1000 - int(micros) // 1000
    age = int(jar["ls_session"]["max-age"])
    check(1209595_000 < remaining <= 1209600_000, "activity resets fourteen-day idle expiry")
    check(0 < age <= 1209600 and abs(age * 1000 - remaining) < 1500,
          "cookie lifetime matches server remaining expiry")
    check(jar["ls_session"]["httponly"] and jar["ls_session"]["samesite"] == "Strict"
          and jar["ls_session"]["path"] == "/" and not jar["ls_session"]["domain"],
          "session cookie security attributes")
    check(response[1]["Cache-Control"] == "no-store", "activity no-store")


def verify(ports, redis_port, counts, redis_name):
    first, second = ports
    old = login(first, "session-smoke")
    keys = session_keys(redis_port, old)
    for port in ports:
        previous = expiry(redis_port, keys)
        profile = request(port, "GET", "/auth/users/me", old)
        check(profile[0] == 200, "session account lookup")
        check(cookie_values(profile) == old, "activity keeps the same session ID")
        check(profile[2]["id"] == keys[2], "profile matches stored user")
        check_renewal(profile, old, redis_port, keys)
        check(expiry(redis_port, keys) >= previous, "expiry never moves backwards")
    changed = request(first, "PATCH", "/auth/users/me", old, {"display_name": "세션 통합"})
    check(changed[0] == 200, "account update")
    check_renewal(changed, old, redis_port, keys)
    fresh = login(second, "session-smoke")
    check(redis(redis_port, "PTTL", keys[0]) > 0, "old ID has TTL after replacement")
    check(fresh != old, "new login replaces the session ID")
    for port in ports:
        error(request(port, "GET", "/auth/users/me", old), 401, "SESSION_INVALID", "RELOGIN")
    revoked = request(first, "POST", "/auth/sessions/revoke", old)
    check(revoked[0] == 200 and revoked[2]["session_revocation"] == "confirmed", "old session revocation")
    check(request(second, "GET", "/auth/users/me", fresh)[0] == 200, "old logout preserves new login")
    session_keys_before_logout = session_keys(redis_port, fresh)
    revoked = request(second, "POST", "/auth/sessions/revoke", fresh)
    check(revoked[0] == 200 and revoked[2]["session_revocation"] == "confirmed", "current logout confirmed")
    check(not cookie_values(revoked), "logout expires session cookie")
    for port in ports:
        error(request(port, "GET", "/auth/users/me", fresh), 401, "SESSION_INVALID", "RELOGIN")
    check(redis(redis_port, "EXISTS", *session_keys_before_logout[:2]) == 0,
          "logout deletes current records")
    check(not any("tokens" in path or "jwks" in path for _, path in counts),
          "no legacy authentication upstream routes")
    passed("single-session login, two-BFF account access, replacement and logout")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--auth-source", type=Path, default=ROOT.parent / "loresentry-authentication")
    parser.add_argument("--auth-ref", default=AUTH_REF)
    parser.add_argument("--content-source", type=Path, help="Also test real Content using an isolated source snapshot")
    parser.add_argument("--content-ref", default=CONTENT_REF)
    parser.add_argument("--redis-image", choices=["redis:7.4-alpine", "valkey/valkey:9.0.6-alpine"],
                        default="valkey/valkey:9.0.6-alpine")
    parser.add_argument("--gradle-cache", type=Path, default=Path("/tmp/loresentry-auth-gradle"))
    args = parser.parse_args()
    scratch = Path(tempfile.mkdtemp(prefix="bff-auth-integration-"))
    scratch.chmod(0o700)
    prefix = "bff-it-" + uuid.uuid4().hex[:10]
    proxy = None
    print("Isolated logs/report: " + str(scratch), flush=True)
    auth_commit = command("git", "-C", str(args.auth_source), "rev-parse", args.auth_ref + "^{commit}")
    content_commit = None
    try:
        auth = scratch / "auth"
        auth.mkdir()
        archive = subprocess.run(["git", "-C", str(args.auth_source), "archive", auth_commit],
                                 check=True, capture_output=True).stdout
        subprocess.run(["tar", "-x", "-C", str(auth)], input=archive, check=True)
        fixture_dir = auth / "src/main/java/com/loresentry/authentication/fixture"
        fixture_dir.mkdir()
        shutil.copy(Path(__file__).with_name("GoogleFixture.java"), fixture_dir)
        sources = [auth, ROOT]
        if args.content_source:
            content_commit = command("git", "-C", str(args.content_source), "rev-parse", args.content_ref + "^{commit}")
            content = scratch / "content"
            content.mkdir()
            archive = subprocess.run(["git", "-C", str(args.content_source), "archive", content_commit],
                                     check=True, capture_output=True).stdout
            subprocess.run(["tar", "-x", "-C", str(content)], input=archive, check=True)
            sources.append(content)
        args.gradle_cache.mkdir(parents=True, exist_ok=True)
        for source in sources:
            print("Building isolated " + source.name, flush=True)
            build = subprocess.run(["docker", "run", "--rm", "--user", f"{os.getuid()}:{os.getgid()}",
                             "-e", "GRADLE_USER_HOME=/gradle", "-v", str(source) + ":/workspace",
                             "-v", str(args.gradle_cache.resolve()) + ":/gradle", "-w", "/workspace",
                             JAVA, "./gradlew", "--no-daemon", "--max-workers=2", "bootJar"], text=True, capture_output=True)
            (scratch / (source.name + "-build.log")).write_text(build.stdout + build.stderr)
            check(build.returncode == 0, "build failed; inspect isolated build log")
        pg = docker(prefix + "-pg", "-p", "127.0.0.1::5432", "-e", "POSTGRES_DB=authentication",
                    "-e", "POSTGRES_USER=integration", "-e", "POSTGRES_PASSWORD=isolated-test",
                    "postgres:18.4-alpine")
        redis_config = scratch / "redis.conf"
        redis_config.write_text('bind 0.0.0.0\nprotected-mode no\nsave ""\nappendonly no\n'
                               'user default on nopass ~* +@all\n'
                               'user bff on >bff-test-password ~auth:session:{login}:* -@all +get +eval +time +pttl +pexpireat +auth +ping +hello +client|setinfo +client|setname\n')
        valkey = docker(prefix + "-valkey", "-p", "127.0.0.1::6379", "-v", str(redis_config) + ":/etc/redis.conf:ro",
                        args.redis_image, "redis-server" if args.redis_image.startswith("redis:") else "valkey-server", "/etc/redis.conf")
        redis_port = published_port(valkey, 6379)
        auth_port = free_port()
        auth_env = scratch / "auth.env"
        auth_env.write_text("\n".join([
            "SPRING_PROFILES_ACTIVE=local", "SERVER_ADDRESS=127.0.0.1", f"SERVER_PORT={auth_port}",
            "DB_HOST=127.0.0.1", f"DB_PORT={published_port(pg, 5432)}", "DB_NAME=authentication",
            "DB_USERNAME=integration", "DB_PASSWORD=isolated-test", "SPRING_DATA_REDIS_HOST=127.0.0.1",
            f"SPRING_DATA_REDIS_PORT={redis_port}",
            "AUTH_GOOGLE_CLIENT_ID=test-client", "AUTH_GOOGLE_CLIENT_SECRET=fixture-only",
            "AUTH_GOOGLE_REDIRECT_URI=http://localhost:8000/auth/oauth/google/callback"]) + "\n")
        auth_env.chmod(0o600)
        auth_jar = next(path for path in (auth / "build/libs").glob("*.jar") if "-plain" not in path.name)
        docker(prefix + "-auth", "--network", "host", "--env-file", str(auth_env),
               "-v", str(auth_jar) + ":/app.jar:ro", JAVA, "java", "-jar", "/app.jar")
        wait_http(auth_port)
        content_port = None
        if content_commit:
            command("docker", "exec", pg, "createdb", "-U", "integration", "content")
            content_port = free_port()
            content_env = scratch / "content.env"
            content_env.write_text("\n".join([
                "SERVER_ADDRESS=127.0.0.1", f"SERVER_PORT={content_port}", "DB_HOST=127.0.0.1",
                f"DB_PORT={published_port(pg, 5432)}", "DB_NAME=content", "DB_USERNAME=integration",
                "DB_PASSWORD=isolated-test", "AWS_EC2_METADATA_DISABLED=true", "AWS_ACCESS_KEY_ID=fixture-only",
                "AWS_SECRET_ACCESS_KEY=fixture-only", "MEDIA_BUCKET=fixture-only",
                "MEDIA_PUBLIC_BASE_URL=https://media.example.test"]) + "\n")
            content_env.chmod(0o600)
            content_jar = next(path for path in (content / "build/libs").glob("*.jar") if "-plain" not in path.name)
            docker(prefix + "-content", "--network", "host", "--env-file", str(content_env),
                   "-v", str(content_jar) + ":/app.jar:ro", JAVA, "java", "-jar", "/app.jar")
            wait_http(content_port)
        counts = collections.Counter()

        class Forward(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_):
                pass

            def do_request(self):
                counts[(self.command, self.path)] += 1
                if self.headers.get("Transfer-Encoding", "").lower() == "chunked":
                    chunks = []
                    while True:
                        size = int(self.rfile.readline().split(b";", 1)[0], 16)
                        if size == 0:
                            while self.rfile.readline() != b"\r\n":
                                pass
                            break
                        chunks.append(self.rfile.read(size))
                        check(self.rfile.read(2) == b"\r\n", "fixture chunk delimiter")
                    data = b"".join(chunks)
                else:
                    data = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                fields = {name: value for name, value in self.headers.items()
                          if name.lower() not in ("host", "transfer-encoding", "connection", "content-length")}
                fields["Content-Length"] = str(len(data))
                destination = auth_port if self.path.startswith("/auth/") or content_port is None else content_port
                conn = http.client.HTTPConnection("127.0.0.1", destination, timeout=12)
                try:
                    conn.request(self.command, self.path, data, fields)
                    response = conn.getresponse()
                    body = response.read()
                    self.send_response(response.status)
                    for name, value in response.getheaders():
                        if name.lower() not in ("transfer-encoding", "connection", "content-length"):
                            self.send_header(name, value)
                    self.send_header("Content-Length", str(len(body)))
                    self.end_headers()
                    self.wfile.write(body)
                finally:
                    conn.close()

            do_GET = do_POST = do_PATCH = do_PUT = do_DELETE = do_request

        proxy = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Forward)
        threading.Thread(target=proxy.serve_forever, daemon=True).start()
        bff_env = scratch / "bff.env"
        internal = "http://127.0.0.1:" + str(proxy.server_port)
        bff_env.write_text("\n".join([
            "SPRING_PROFILES_ACTIVE=local", "SERVER_ADDRESS=127.0.0.1", "BFF_AUTH_BASE_URL=" + internal, "BFF_CONTENT_BASE_URL=" + internal,
            "BFF_GRAPH_BASE_URL=" + internal, "BFF_CHAT_BASE_URL=" + internal,
            "BFF_SESSION_REDIS_HOST=127.0.0.1", f"BFF_SESSION_REDIS_PORT={redis_port}",
            "BFF_SESSION_REDIS_USERNAME=bff", "BFF_SESSION_REDIS_PASSWORD=bff-test-password", "BFF_SESSION_REDIS_TLS=false"]) + "\n")
        bff_env.chmod(0o600)
        bff_jar = next(path for path in (ROOT / "build/libs").glob("*.jar") if "-plain" not in path.name)
        ports = []
        for index in range(2):
            port = free_port()
            docker(prefix + f"-bff-{index}", "--network", "host", "--env-file", str(bff_env), "-e", f"SERVER_PORT={port}",
                   "-v", str(bff_jar) + ":/app.jar:ro", JAVA, "java", "-jar", "/app.jar")
            wait_http(port)
            ports.append(port)
        if content_commit:
            from content_checks import verify_content
            verify_content(ports, request, login, check, error, passed)
        verify(ports, redis_port, counts, valkey)
        report = {"auth_commit": auth_commit, "gateway_base_commit": command("git", "-C", str(ROOT), "rev-parse", "HEAD"),
                  "gateway_jar_sha256": hashlib.sha256(bff_jar.read_bytes()).hexdigest(),
                  "content_commit": content_commit,
                  "scenarios": results, "postgres": "18.4", "session_store_image": args.redis_image, "bff_instances": 2,
                  "external_google": "isolated HTTP/JWK fixture; real Auth OIDC client",
                  "not_verified": ["real Google consent", "browser cookies", "production infrastructure"]
                                  + ([] if content_commit else ["Content"])}
        (scratch / "report.json").write_text(json.dumps(report, indent=2) + "\n")
        print("All Auth/session integration scenarios passed.", flush=True)
    finally:
        if proxy:
            proxy.shutdown()
            proxy.server_close()
        for name in reversed(containers):
            logs = subprocess.run(["docker", "logs", name], text=True, capture_output=True)
            (scratch / (name + ".log")).write_text(logs.stdout + logs.stderr)
            subprocess.run(["docker", "rm", "-f", "-v", name], capture_output=True)
        for name in ("auth.env", "bff.env", "content.env"):
            (scratch / name).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
