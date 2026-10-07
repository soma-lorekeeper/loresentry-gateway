"""Terms HTTP contracts against real Auth, BFF, PostgreSQL and Redis/Valkey."""
import concurrent.futures
import hashlib
import json
import os
import subprocess
import urllib.parse

V1 = '00000000-0000-4000-8000-000000000001'
V2 = '00000000-0000-4000-8000-000000000002'
FUTURE = '00000000-0000-4000-8000-000000000003'


def verify_terms(api, ports, store, pg, frontend, scratch, frontend_port):
    port = ports[0]

    def sql(statement):
        return api.command('docker', 'exec', pg, 'psql', '-U', 'integration', '-d', 'authentication', '-At', '-c', statement)

    def original(id, version, offset):
        sql(f"INSERT INTO terms_versions VALUES ('{id}', 'SERVICE_TERMS', '{version}', 'Integration terms', 'Test original {version}', now(), now() + interval '{offset}')")

    def login(subject):
        prepared = api.request(port, 'GET', '/auth/oauth/google/prepare')
        query = urllib.parse.parse_qs(urllib.parse.urlsplit(prepared[1]['Location']).query)
        code = api.b64(json.dumps({'subject': subject, 'nonce': query['nonce'][0], 'challenge': query['code_challenge'][0]}).encode())
        response = api.request(port, 'GET', '/auth/oauth/google/callback?' + urllib.parse.urlencode({'state': query['state'][0], 'code': code}), api.cookie_values(prepared))
        api.check(response[0] == 303, 'callback redirect')
        return response, api.cookie_values(response)

    def pending(subject):
        response, jar = login(subject)
        api.check(response[1]['Location'].endswith('result=terms_required'), 'pending callback result')
        api.check(set(jar) == {'ls_consent'}, 'only pending credential issued')
        api.check('HttpOnly' in '\n'.join(response[1].get_all('Set-Cookie')), 'pending cookie HttpOnly')
        api.secret_values.add(jar['ls_consent'])
        return jar

    def accept(jar, version=V1):
        return api.request(port, 'POST', '/auth/terms/accept', jar, {'terms_version_id': version})

    # Enabled but unseeded must fail closed even though account registration commits.
    response, jar = login('no-original')
    api.check(response[1]['Location'].endswith('result=unavailable') and 'ls_session' not in jar, 'missing original fails closed')
    api.check(sql('SELECT count(*) FROM users') == '1', 'account survives missing original')
    original(V1, '1', '0 seconds')
    original(FUTURE, '99', '30 days')
    jar = pending('no-original')
    another = pending('new-user')
    for item in (jar, another):
        response = api.request(port, 'GET', '/auth/terms', item)
        api.check(response[0] == 200 and response[2]['terms_version_id'] == V1, 'current original excludes future')
        api.check(set(response[2]) == {'terms_version_id', 'version', 'title', 'content', 'effective_at', 'expires_at', 'locale'}, 'terms response whitelist')
        api.check(response[2]['locale'] in (None, 'ko'), 'untranslated original reports Korean or predates locale')
        for locale in ('en', 'fr'):
            fallback = api.request(port, 'GET', '/auth/terms?locale=' + locale, item)
            api.check(fallback[0] == 200 and fallback[2]['terms_version_id'] == V1, 'locale query keeps the version')
            api.check(fallback[2]['content'] == response[2]['content'] and fallback[2]['locale'] in (None, 'ko'), 'missing translation returns the original')
        api.check(not response[1].get_all('Set-Cookie'), 'query does not renew cookie')
        api.check(api.request(port, 'GET', '/auth/users/me', item)[0] == 401, 'pending is not login')
        api.check(api.request(port, 'POST', '/auth/terms/accept', item, {'terms_version_id': V1}, {'X-LS-CSRF': ''})[0] == 403, 'CSRF required')
    old_pending = another['ls_consent']
    another = pending('new-user')
    api.check(another['ls_consent'] != old_pending, 'fresh login issues fresh pending')
    with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
        attempts = list(pool.map(lambda _: accept(jar), range(8)))
    api.check(sorted(x[0] for x in attempts) == [204] + [401] * 7, 'completion consumed exactly once')
    success = next(x for x in attempts if x[0] == 204)
    session = api.cookie_values(success)
    api.check(set(session) == {'ls_session'} and success[2] is None, 'completion empty 204 with session only')
    api.check(api.request(port, 'GET', '/auth/users/me', session)[0] == 200, 'new session authenticates')
    api.check(sql('SELECT count(*) FROM user_terms_acceptances') == '1', 'one acceptance recorded')
    # Lost completion response is recovered by a new Google login, never by replay.
    response, recovered = login('no-original')
    api.check(response[1]['Location'].endswith('result=success') and 'ls_session' in recovered, 'relogin recognizes committed acceptance')
    original(V2, '2', '0 seconds')
    response = accept(another)
    api.check(response[0] == 409 and response[2]['code'] == 'TERMS_VERSION_MISMATCH', 'stale version rejected')
    api.check(not response[1].get_all('Set-Cookie'), 'mismatch preserves pending cookie')
    api.check(api.request(port, 'GET', '/auth/terms', another)[2]['terms_version_id'] == V2, 'query refreshes pending version')
    api.check(accept(another, V2)[0] == 204, 'revised original can be accepted')
    api.check(api.request(port, 'GET', '/auth/users/me', recovered)[0] == 200, 'revision does not revoke existing session')
    pending('no-original')
    expired = pending('expired')
    digest = hashlib.sha256(expired['ls_consent'].encode()).hexdigest()
    api.redis(store, 'DEL', 'auth:consent:by-id:' + digest)
    response = api.request(port, 'GET', '/auth/terms', expired)
    api.check(response[0] == 401 and 'Max-Age=0' in '\n'.join(response[1].get_all('Set-Cookie')), 'invalid pending clears cookie')
    api.passed('terms HTTP: unseeded fail-closed, new/existing accounts, current/future/revised originals, CSRF, one-time completion, recovery, expiry')

    project = frontend.resolve()
    api.check((project / 'out/login/index.html').exists(), 'frontend static export exists')
    config = {'frontendPort': frontend_port, 'bffPort': port, 'output': str(project / 'out'), 'report': str(scratch / 'terms-browser-report.json')}
    env = dict(os.environ)
    with (scratch / 'terms-browser.log').open('w') as log:
        completed = subprocess.run(['node', str(project / 'integration/browser/terms.mjs')], input=json.dumps(config), text=True, env=env, stdout=log, stderr=log)
    api.check(completed.returncode == 0, 'terms browser failed; inspect isolated terms-browser.log')
    api.passed('real Chromium frontend/Auth/BFF: fixture Google return, consent, CSRF/cookies, close/reentry, fresh session, logout')
