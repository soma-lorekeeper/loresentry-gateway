"""Runs the real static frontend against local and prod-profile BFFs, using fixture Google."""
import json
import os
import subprocess


def verify_browser(api, project, scratch, ports, store, auth_env, auth_jar, bff_env, bff_jar, content_port):
    project = project.resolve()
    api.check((project / 'out/index.html').exists(), 'frontend static build required')
    # Separate Auth instance uses the production callback address with the same fixture provider.
    prod_auth_port = api.free_port()
    prod_auth_env = scratch / 'browser-auth.env'
    prod_auth_env.write_text(auth_env.read_text().replace(
        'SERVER_PORT=' + next(line.split('=', 1)[1] for line in auth_env.read_text().splitlines() if line.startswith('SERVER_PORT=')),
        'SERVER_PORT=' + str(prod_auth_port)).replace(
        'AUTH_GOOGLE_REDIRECT_URI=http://localhost:8000/auth/oauth/google/callback',
        'AUTH_GOOGLE_REDIRECT_URI=https://api.loresentry.com/auth/oauth/google/callback'))
    prod_auth_env.chmod(0o600)
    prefix = api.containers[0].removesuffix('-pg')
    try:
        api.docker(prefix + '-browser-auth', '--network', 'host', '--env-file', str(prod_auth_env),
                   '-v', str(auth_jar) + ':/app.jar:ro', api.JAVA, 'java', '-jar', '/app.jar')
        api.wait_http(prod_auth_port)
        prod_bff_port = api.free_port()
        api.docker(prefix + '-browser-bff', '--network', 'host', '--env-file', str(bff_env),
                   '-v', str(bff_jar) + ':/app.jar:ro', api.JAVA, 'java', '-jar', '/app.jar',
                   '--spring.profiles.active=prod', '--server.port=' + str(prod_bff_port),
                   '--loresentry.upstream.authentication.base-url=http://127.0.0.1:' + str(prod_auth_port),
                   '--loresentry.upstream.content.base-url=http://127.0.0.1:' + str(content_port),
                   '--loresentry.upstream.graph-rag.base-url=http://127.0.0.1:' + str(content_port),
                   '--loresentry.upstream.ai-chat.base-url=http://127.0.0.1:' + str(content_port))
        api.wait_http(prod_bff_port, "https://loresentry.com")
        key, cert = scratch / 'browser-tls.key', scratch / 'browser-tls.crt'
        api.command('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '1',
                    '-subj', '/CN=loresentry.com', '-addext', 'subjectAltName=DNS:loresentry.com,DNS:api.loresentry.com,DNS:accounts.google.com',
                    '-keyout', str(key), '-out', str(cert))
        key.chmod(0o600)
        config = {'localBff': ports[0], 'prodBff': prod_bff_port, 'redisPort': store,
                  'tlsKey': str(key), 'tlsCert': str(cert), 'report': str(scratch / 'browser-report.json')}
        env = dict(os.environ, PLAYWRIGHT_BROWSERS_PATH='/tmp/loresentry-playwright')
        with (scratch / 'browser-run.log').open('w') as log:
            completed = subprocess.run(['node', 'integration/browser/real-session.mjs'], cwd=project,
                                       input=json.dumps(config), text=True, env=env, stdout=log, stderr=log)
        api.check(completed.returncode == 0, 'browser verification failed; inspect isolated browser-run.log')
        api.passed('real Chromium/Firefox frontend: local and HTTPS prod-profile session cookie lifecycle (fixture Google)')
    finally:
        prod_auth_env.unlink(missing_ok=True)
        (scratch / 'browser-tls.key').unlink(missing_ok=True)
