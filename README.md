# loresentry-gateway

Lore Sentry의 브라우저 API 경계다. Java 21, Spring Boot 4.1.1 Servlet MVC와
RestClient를 사용한다. Auth·Content의 명시적 API를 호출하며 도메인 권한은
각 서비스가 판단한다. 브라우저 인증은 단일 세션 ID를 사용한다.

제공 계약은 [API.md](docs/API.md), 서버 호출은 [API_CALLS.md](docs/API_CALLS.md),
문서별 역할은 [문서 안내](docs/README.md)에서 확인한다.

## 인증과 API

변경 요청은 Origin과 `X-LS-CSRF: 1`을 먼저 검사한다. 보호 요청은 HttpOnly 세션
쿠키를 받아 공유 저장소의 두 인덱스를 원자적으로 검증하고 마지막 인증 활동부터
14일로 만료를 연장한다. 연결·조회·연장 전체 제한은 500ms다. 인증 허용 캐시,
복제본 조회와 자동 재시도는 사용하지 않는다. 상세 계약은
[세션 설계](../loresentry-authentication/docs/session/SESSION_DESIGN.md)를 따른다.

| API | 동작 |
|---|---|
| `GET /health` | 공개 프로세스 헬스 |
| `GET /auth/oauth/google/prepare` | 임시 OAuth 쿠키 설정 후 Google로 이동 |
| `GET /auth/oauth/google/callback` | 단일 세션 쿠키 설정 후 고정 로그인 화면으로 복귀 |
| `POST /auth/sessions/revoke` | CSRF 이후 폐기 요청과 쿠키 삭제 헤더, 폐기 결과 반환 |
| `GET/PATCH /auth/users/me` | 확인된 사용자 계정 조회·이름 수정 |
| Content 38개 경로 | [Content 계약](docs/CONTENT_API.md) |

외부 X-User-Id·Cookie·Authorization은 도메인 호출에 전달하지 않는다.
검증된 UUID 하나만 내부 사용자 헤더로 전달한다. 인증 실패는 401, 저장소 장애는
503이며 쿠키를 변경하지 않는다. 인증에 성공한 업무 오류 응답도 활동 수명을 갱신한다.
내부 HTTP 연결·풀 획득 제한은 2초, 읽기는 10초이며 자동 재시도를 끈다.

## 브라우저 계약

local은 `http://localhost:3000`과 BFF `http://localhost:8000`, prod는
`https://loresentry.com`과 `https://api.loresentry.com`을 사용한다.
세션 쿠키는 local `ls_session`, prod `__Host-ls_session`이다.
HttpOnly·Path=/·Domain 없음·SameSite=Strict이며 prod에 Secure를 적용한다.
OAuth 임시 쿠키만 Lax·최대 5분이다. 서버 만료까지 남은 시간을 쿠키 수명으로 사용한다.

프론트는 credentials: include와 변경 요청의 CSRF 헤더를 사용한다.
인증 전환 시 탭 간 조율과 진행 요청의 헤더 수신 완료가 필요하다.
[프론트 계약](docs/FRONTEND_AUTH_CONTRACT.md)과 [보안 계약](docs/BROWSER_SECURITY.md)을 따른다.

## 로컬 실행

Java 21과 개발 Auth·Content·Redis가 필요하다. [.env.local.example](.env.local.example)을
`.env.local`로 복사하고 내부 주소와 전용 세션 Redis 계정을 채운다.
계정은 조회와 검증·TTL 연장을 허용해야 한다. 환경 파일은 자동으로 읽지 않는다.

```bash
set -a
source .env.local
set +a
./gradlew bootRun
curl http://localhost:8000/health
```

local/prod 중 하나의 프로필이 필요하다. 서비스 JWT 키는 사용하지 않는다.
Linux 호스트에서 실행할 때 Windows 로컬의 3000/8000 포트 전달을 맞춘다.

## 검증과 배포

```bash
./gradlew --no-daemon build
# loopback의 격리 Redis와 Valkey 관리자 포트를 지정한다.
TEST_REDIS_PORT=<port> TEST_VALKEY_PORT=<port> ./gradlew sessionIntegrationTest
python3 integration/session/run.py --auth-source ../loresentry-authentication
```

실제 서비스 실행은 [통합 러너](integration/session/README.md)를 따른다.
테스트 보고서는 `build/reports/tests/`에 생성되며 실행 결과는 해당 이슈에서 관리한다.

main push의 CI/CD는 build, 이미지 게시, GitOps 태그 변경과 Argo CD 배포를 실행한다.
Work·Deliverable 브랜치 게시로는 이 파이프라인이 실행되지 않는다.
[운영 준비](docs/OPERATIONS.md)와 [공동 전환](docs/ROLLOUT.md)의 완료 확인이 필요하다.

### Redis·Valkey 통합 테스트

격리된 Redis 7.4와 Valkey 9.0.6 컨테이너에
[`src/test/resources/session-redis.conf`](src/test/resources/session-redis.conf)를 설정 파일로
마운트하고 해당 설정으로 서버를 시작한다. 호스트에는 루프백 포트로만 노출한다.
이 설정은 비밀번호 없는 관리자 계정을 포함하므로 테스트 전용으로 사용한다.
Gradle 프로세스가 두 서버의 루프백 포트에 접근할 수 있어야 한다.

각 포트를 `TEST_REDIS_PORT`, `TEST_VALKEY_PORT` 환경변수로 지정한 뒤 실행한다.

```bash
./gradlew sessionIntegrationTest
```
