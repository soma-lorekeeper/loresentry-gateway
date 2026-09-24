#!/usr/bin/env python3
"""Isolated real Auth/PostgreSQL/Valkey + two BFF processes. No production credentials.

Requires Linux, Docker, Python 3, OpenSSL and a local Auth Git checkout. Only the
external Google provider is simulated; Auth's OIDC/JWT/DB/session code is real.
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
AUTH_REF = "e9d5b5b35dace0b9c7066ec93d1ea35e018963e7"
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


def claims(token):
    return json.loads(base64.urlsafe_b64decode(token.split(".")[1] + "=="))


def sign_claims(payload, private_key, kid):
    unsigned = b64(json.dumps({"alg": "RS256", "kid": kid}).encode()) + "." + b64(json.dumps(payload).encode())
    signature = subprocess.run(["openssl", "dgst", "-sha256", "-sign", str(private_key)],
                               input=unsigned.encode(), capture_output=True, check=True).stdout
    return unsigned + "." + b64(signature)


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
    check(set(jar) == {"ls_at", "ls_rt"}, "callback auth cookie names")
    check(callback[1]["Cache-Control"] == "no-store", "callback no-store")
    return jar


def verify(ports, redis_port, counts, private_key, kid, redis_name):
    first, second = ports
    old = login(first, "session-user")
    for port in ports:
        account = request(port, "GET", "/auth/users/me", old)
        check(account[0] == 200, "real account request")
    user = account[2]["id"]
    changed = request(second, "PATCH", "/auth/users/me", old, {"display_name": "통합 검증"})
    check(changed[0] == 200 and changed[2]["display_name"] == "통합 검증", "real account update")
    refreshed = request(second, "POST", "/auth/tokens/refresh", old)
    check(refreshed[0] == 204, "refresh succeeds")
    rotated = cookie_values(refreshed)
    check(claims(old["ls_at"])["sid"] == claims(rotated["ls_at"])["sid"], "refresh preserves sid")
    for port in ports:
        check(request(port, "GET", "/auth/users/me", {"ls_at": old["ls_at"]})[0] == 200,
              "old unexpired AT remains valid without RT")
    error(request(first, "POST", "/auth/tokens/refresh", old), 401, "REFRESH_REJECTED", "RELOGIN")
    passed("real login/account/update/RT rotation; same-sid AT accepted by both BFF instances")

    fresh = login(second, "session-user")
    check(claims(old["ls_at"])["sid"] != claims(fresh["ls_at"])["sid"], "new login replaces sid")
    for port in ports:
        error(request(port, "GET", "/auth/users/me", rotated), 401, "SESSION_INVALID", "RELOGIN")
        error(request(port, "POST", "/auth/tokens/refresh", rotated), 401, "REFRESH_REJECTED", "RELOGIN")
    revoked = request(first, "POST", "/auth/tokens/revoke", rotated)
    check(revoked[0] == 200 and revoked[2]["refresh_revocation"] == "confirmed", "old session revocation")
    for port in ports:
        check(request(port, "GET", "/auth/users/me", fresh)[0] == 200, "old logout must protect new session")
    request(second, "POST", "/auth/tokens/revoke", fresh)
    for port in ports:
        error(request(port, "GET", "/auth/users/me", fresh), 401, "SESSION_INVALID", "RELOGIN")
    passed("new login rejects old AT/RT; old-session logout preserves new login; current logout blocks both instances")

    active = login(first, "fault-user")
    token_claims = claims(active["ls_at"])
    key = "auth:session:" + token_claims["sub"]
    original = redis(redis_port, "GET", key)
    expiry = json.loads(original)["refresh_expires_at"]
    before = counts.copy()
    sidless = dict(token_claims)
    del sidless["sid"]
    for port in ports:
        protected_error(port, {"ls_at": sign_claims(sidless, private_key, kid)},
                        401, "ACCESS_TOKEN_INVALID", "RELOGIN")
        error(request(port, "GET", "/auth/users/me", headers={"X-User-Id": user}),
              401, "ACCESS_TOKEN_MISSING", "REFRESH")
    check(counts == before, "invalid/sidless tokens must not reach Auth")
    redis(redis_port, "DEL", key)
    legacy_key = "auth:refresh:" + token_claims["sub"]
    redis(redis_port, "SET", legacy_key, original, "EX", 60)
    for port in ports:
        protected_error(port, active, 401, "SESSION_INVALID", "RELOGIN")
    redis(redis_port, "SET", key, original, "EXAT", expiry)
    check(counts == before, "no old-key fallback/internal call")
    sidless_rt = claims(active["ls_rt"])
    del sidless_rt["sid"]
    for port in ports:
        error(request(port, "POST", "/auth/tokens/refresh", {"ls_rt": sign_claims(sidless_rt, private_key, kid)}),
              401, "REFRESH_REJECTED", "RELOGIN")
    passed("signed sidless AT and client identity rejected; no legacy-key fallback")

    # A successful request immediately before mutation would expose any session cache.
    for port in ports:
        check(request(port, "GET", "/auth/users/me", active)[0] == 200, "pre-fault valid session")
    before = counts.copy()
    redis(redis_port, "SET", key, "{broken", "EX", 60)
    for port in ports:
        protected_error(port, active, 503, "SESSION_UNAVAILABLE", "RETRY_LATER")
    redis(redis_port, "SET", key, original, "EXAT", expiry)
    redis(redis_port, "ACL", "SETUSER", "bff", "-get")
    for port in ports:
        protected_error(port, active, 503, "SESSION_UNAVAILABLE", "RETRY_LATER")
    redis(redis_port, "ACL", "SETUSER", "bff", "+get")
    for port in ports:
        redis(redis_port, "CLIENT", "PAUSE", 1000, "ALL")
        started = time.monotonic()
        error(request(port, "GET", "/auth/users/me", active), 503, "SESSION_UNAVAILABLE", "RETRY_LATER")
        elapsed = time.monotonic() - started
        check(0.35 < elapsed < 0.9, "session delay bounded by 500ms budget")
        time.sleep(1.1)
    check(counts == before, "corruption/ACL/timeout must not reach Auth")
    for port in ports:
        check(request(port, "GET", "/auth/users/me", active)[0] == 200, "session recovery")
    before = counts.copy()
    command("docker", "stop", "-t", "1", redis_name)
    for port in ports:
        protected_error(port, active, 503, "SESSION_UNAVAILABLE", "RETRY_LATER")
    check(counts == before, "Redis down must not reach Auth")
    passed("uncached corruption/ACL/delay/down failures return 503, preserve cookies and never reach internal Auth")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--auth-source", type=Path, default=ROOT.parent / "loresentry-authentication")
    parser.add_argument("--auth-ref", default=AUTH_REF)
    parser.add_argument("--content-source", type=Path, help="Also test real Content using an isolated source snapshot")
    parser.add_argument("--content-ref", default=CONTENT_REF)
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
                             JAVA, "./gradlew", "--no-daemon", "bootJar"], text=True, capture_output=True)
            (scratch / (source.name + "-build.log")).write_text(build.stdout + build.stderr)
            check(build.returncode == 0, "build failed; inspect isolated build log")
        private = scratch / "private.pem"
        public = scratch / "public.pem"
        command("openssl", "genpkey", "-algorithm", "RSA", "-pkeyopt", "rsa_keygen_bits:2048", "-out", str(private))
        private.chmod(0o600)
        command("openssl", "pkey", "-in", str(private), "-pubout", "-out", str(public))
        der = subprocess.run(["openssl", "pkcs8", "-topk8", "-nocrypt", "-in", str(private), "-outform", "DER"],
                             check=True, capture_output=True).stdout
        kid = str(uuid.uuid4())
        pg = docker(prefix + "-pg", "-p", "127.0.0.1::5432", "-e", "POSTGRES_DB=authentication",
                    "-e", "POSTGRES_USER=integration", "-e", "POSTGRES_PASSWORD=isolated-test",
                    "postgres:18.4-alpine")
        redis_config = scratch / "redis.conf"
        redis_config.write_text('bind 0.0.0.0\nprotected-mode no\nsave ""\nappendonly no\n'
                               'user default on nopass ~* +@all\n'
                               'user bff on >bff-test-password ~auth:session:* -@all +get +auth +ping +hello +client|setinfo +client|setname\n')
        valkey = docker(prefix + "-valkey", "-p", "127.0.0.1::6379", "-v", str(redis_config) + ":/etc/redis.conf:ro",
                        "valkey/valkey:9.0.6-alpine", "valkey-server", "/etc/redis.conf")
        redis_port = published_port(valkey, 6379)
        auth_port = free_port()
        auth_env = scratch / "auth.env"
        auth_env.write_text("\n".join([
            "SPRING_PROFILES_ACTIVE=local", "SERVER_ADDRESS=127.0.0.1", f"SERVER_PORT={auth_port}",
            "DB_HOST=127.0.0.1", f"DB_PORT={published_port(pg, 5432)}", "DB_NAME=authentication",
            "DB_USERNAME=integration", "DB_PASSWORD=isolated-test", "SPRING_DATA_REDIS_HOST=127.0.0.1",
            f"SPRING_DATA_REDIS_PORT={redis_port}", "AUTH_JWT_PRIVATE_KEY_BASE64=" + base64.b64encode(der).decode(),
            "AUTH_JWT_PUBLIC_KEY_PATH=/fixture/public.pem", "AUTH_JWT_KEY_ID=" + kid,
            "AUTH_GOOGLE_CLIENT_ID=test-client", "AUTH_GOOGLE_CLIENT_SECRET=fixture-only",
            "AUTH_GOOGLE_REDIRECT_URI=http://localhost:8000/auth/oauth/google/callback"]) + "\n")
        auth_env.chmod(0o600)
        auth_jar = next(path for path in (auth / "build/libs").glob("*.jar") if "-plain" not in path.name)
        docker(prefix + "-auth", "--network", "host", "--env-file", str(auth_env),
               "-v", str(public) + ":/fixture/public.pem:ro", "-v", str(auth_jar) + ":/app.jar:ro", JAVA, "java", "-jar", "/app.jar")
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
            "SPRING_PROFILES_ACTIVE=local", "SERVER_ADDRESS=127.0.0.1", "BFF_JWT_PUBLIC_KEY=/fixture/public.pem",
            "BFF_JWT_KEY_ID=" + kid, "BFF_AUTH_BASE_URL=" + internal, "BFF_CONTENT_BASE_URL=" + internal,
            "BFF_GRAPH_BASE_URL=" + internal, "BFF_CHAT_BASE_URL=" + internal,
            "BFF_SESSION_REDIS_HOST=127.0.0.1", f"BFF_SESSION_REDIS_PORT={redis_port}",
            "BFF_SESSION_REDIS_USERNAME=bff", "BFF_SESSION_REDIS_PASSWORD=bff-test-password", "BFF_SESSION_REDIS_TLS=false"]) + "\n")
        bff_env.chmod(0o600)
        bff_jar = next(path for path in (ROOT / "build/libs").glob("*.jar") if "-plain" not in path.name)
        ports = []
        for index in range(2):
            port = free_port()
            docker(prefix + f"-bff-{index}", "--network", "host", "--env-file", str(bff_env), "-e", f"SERVER_PORT={port}",
                   "-v", str(public) + ":/fixture/public.pem:ro", "-v", str(bff_jar) + ":/app.jar:ro", JAVA, "java", "-jar", "/app.jar")
            wait_http(port)
            ports.append(port)
        if content_commit:
            from content_checks import verify_content
            verify_content(ports, request, login, check, error, passed, claims)
        verify(ports, redis_port, counts, private, kid, valkey)
        report = {"auth_commit": auth_commit, "gateway_base_commit": command("git", "-C", str(ROOT), "rev-parse", "HEAD"),
                  "gateway_jar_sha256": hashlib.sha256(bff_jar.read_bytes()).hexdigest(),
                  "content_commit": content_commit,
                  "scenarios": results, "postgres": "18.4", "valkey": "9.0.6", "bff_instances": 2,
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
        for name in ("private.pem", "auth.env", "bff.env", "content.env"):
            (scratch / name).unlink(missing_ok=True)


if __name__ == "__main__":
    main()
