# loresentry-gateway

> 현재 코드는 JWT 기반 AT·RT를 사용한다. 아래 API·쿠키·실행 안내는 현재 코드 기준이며, `docs/`의 인증 설계는 아직 구현하지 않은 단일 세션 ID 방식이다. [문서 안내](docs/README.md)에서 목표 계약을 확인한다.

Lore Sentry의 브라우저 API 경계다. Java 21, Spring Boot 4.1.1의 Servlet MVC,
Virtual Threads와 RestClient를 사용한다. 명시적인 외부 API를 통해 Auth·Content를
호출하며, 도메인 데이터의 접근 권한과 규칙은 해당 서비스가 결정한다.

main push는 자동 배포를 실행하므로 운영 설정·ACL·접근 제한을 확인한 뒤 진행한다.
세션 ID 방식으로 전환할 때는 [운영 준비](docs/OPERATIONS.md)와 [공동 전환 절차](docs/ROLLOUT.md)를 따른다.

제공 계약은 [API.md](docs/API.md), Auth·Content 호출 방법은 [API_CALLS.md](docs/API_CALLS.md)에서 관리한다.

## 요청 처리

`web → application → client`로 HTTP 경계·요청 조율·내부 호출을 분리한다.
`security`가 CSRF·AT·활성 세션을 검증하고 `config`가 키·연결·환경을 구성한다.
[Content API](docs/CONTENT_API.md)는 도메인 경로·응답을 정의한다.
[목표 구조](docs/ARCHITECTURE.md)와 [목표 인증 API](docs/API.md)는 세션 ID 전환 시 적용한다.

- 상태 변경 요청은 정확한 Origin과 `X-LS-CSRF: 1`을 먼저 검사한다.
- 보호 요청은 HttpOnly AT 쿠키의 RS256 서명·kid·클레임·시각·sid를 검증한 뒤,
  Auth와 같은 Redis 쓰기 담당 노드에서 `auth:session:{sub}`를 한 번 GET한다.
- 세션 조회는 연결 초기화를 포함해 500ms다. 허용 캐시·복제본 조회·재시도·TTL 연장이 없다.
  부재·만료·sid 불일치는 401, 조회 장애·손상은 503이며 쿠키를 지우지 않는다.
- 검증된 사용자 UUID만 내부 `X-User-Id`로 설정한다. 브라우저의 사용자 헤더를 신뢰하지
  않고, Cookie·Authorization을 내부 서비스로 전달하지 않는다.
- 내부 HTTP 연결·풀 획득 제한은 2초, 읽기는 10초다. 자동 재시도·리다이렉트·쿠키 저장을
  끄며, 내부 오류는 계약별로 변환하고 원문이나 토큰을 노출하지 않는다.

## API

| API | 동작 |
| --- | --- |
| `GET /health` | 공개 프로세스 헬스 체크; Kubernetes·ALB probe 경로 |
| `GET /auth/oauth/google/prepare` | Auth 준비 호출 후 임시 쿠키 설정, Google로 302 |
| `GET /auth/oauth/google/callback` | 로그인 결과에 따라 인증 쿠키 처리, 고정 프론트 주소로 303 |
| `POST /auth/tokens/refresh` | CSRF·RT 쿠키로 명시적 재발급, 성공 시 쿠키 교체와 204 |
| `POST /auth/tokens/revoke` | CSRF 통과 후 AT·RT 삭제 헤더와 RT 폐기 확인 결과 반환 |
| `GET/PATCH /auth/users/me` | AT·활성 세션을 확인한 본인 계정 조회·표시 이름 수정 |
| 프로젝트·파일·메모·이미지 등 38개 경로 | [명시적 Content API 목록](docs/CONTENT_API.md)과 외부 DTO 사용 |

`/`, `/auth`, `/content`, `/graph`, `/ai-chat`, `/health/db` 등 기존 진단 경로도
AT·세션 검사를 받는다. 연결 확인 응답은 기능 API의 계약으로 사용하지 않는다.
임의 namespace relay, 클라이언트 사용자 헤더 인증, 내부 오류 원문 전달은 제거했다.
AI 스트리밍·여러 서비스 응답 조합은 현재 노출 범위에 포함하지 않는다.

## 브라우저 계약

| 환경 | 프론트 Origin | 공개 BFF 주소 | 쿠키 |
| --- | --- | --- | --- |
| local | `http://localhost:3000` | `http://localhost:8000` | `ls_at`, `ls_rt`, `ls_oauth`; Secure 없음 |
| prod | `https://loresentry.com` | `https://api.loresentry.com` | `__Host-ls_at`, `__Host-ls_rt`, `__Host-ls_oauth`; Secure |

쿠키는 HttpOnly·Path=/·Domain 없음이다. AT·RT는 SameSite=Strict이고 최대 수명은
15분·14일이며 실제 토큰 만료까지 남은 시간을 넘지 않는다. OAuth 임시 쿠키만 Lax·최대
5분이다. 쿠키 삭제도 발급 때와 같은 범위로 한다.

CORS는 위 Origin 하나만 정확하게 허용한다. credentials를 허용하고 Location을 노출한다.
서브도메인·다른 localhost 포트·127.0.0.1을 패턴으로 허용하지 않는다.
프론트는 `credentials: "include"`, 상태 변경의 CSRF 헤더, 로그인 result 확인 후
users/me 조회와 명시적 재발급·탭 조율을 구현해야 한다.
세션 ID 방식으로 전환할 때는 [목표 보안 계약](docs/BROWSER_SECURITY.md)과
[프론트 연동 계약](docs/FRONTEND_AUTH_CONTRACT.md)을 적용한다.

## 로컬 실행

Java 21과 접근 가능한 개발 Auth·Content·Redis가 필요하다. Linux 호스트에서 실행할 때
Windows 로컬 브라우저의 3000/8000 포트 전달을 맞추며, 개발 주소가 없다고 운영으로 대체하지 않는다.

[.env.local.example](.env.local.example)을 `.env.local`로 복사하고 개발 서버 주소,
Auth와 일치하는 공개키·kid, BFF 읽기 전용 Redis 계정을 채운다. `.env.local`은 자동으로
읽히지 않으므로 환경으로 주입한다. 실제 비밀번호·개인키를 Git에 넣지 않는다.

```bash
set -a
source .env.local
set +a
./gradlew bootRun
curl http://localhost:8000/health
```

local/prod 프로필을 정확히 하나 선택해야 한다. 공개키 파일·kid·세션 Redis 계정은 필수이며
누락되거나 잘못되면 기동을 거절한다. prod의 내부 서비스 주소는 Kubernetes Service DNS를
사용한다. 위 JWT 설정은 현재 구현에 필요하며, 세션 ID 전환 후의 주입 요구는
[목표 운영 설정 표](docs/OPERATIONS.md#설정과-secret)를 따른다.

## 검증

```bash
./gradlew --no-daemon build
```

일반 `build`는 `redis` 태그를 제외한다. Redis 통합 테스트는 별도 태스크로 실행한다.

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

### Auth·Content 통합 테스트

실제 Auth·PostgreSQL·Valkey와 BFF 두 인스턴스, 선택한 Content를 자동으로 만들고 정리하는
검증 도구도 제공한다. Docker·Python 3·OpenSSL 및 해당 서비스의 로컬 Git 저장소가 필요하다.

```bash
python3 integration/session/run.py --content-source ../loresentry-content
```

설정·격리 범위는 [통합 실행 안내](integration/session/README.md)를 따른다.
외부 Google은 테스트용 HTTP/JWK 응답이며 운영 Google·실제 브라우저·운영 네트워크 검증을
대체하지 않는다. 현재 도구는 JWT 방식용이며 새 세션 ID 설계의 검증에는 수정이 필요하다.

## 배포

main push는 [CI/CD](.github/workflows/ci-cd.yaml)를 통해 일반 build, ECR 이미지 게시,
GitOps 이미지 태그 변경과 Argo CD 배포를 실행한다. CI가 별도 실제 서비스 통합 태스크까지
실행하는 것은 아니다. Work·Deliverable 브랜치 게시로는 이 파이프라인이 실행되지 않는다.

Auth·BFF의 인증 계약과 프론트·운영 설정의 호환성을 확인한 뒤 배포한다.
세션 ID 전환의 진행 요청 배출·구 인스턴스 제거·재로그인과 롤백은
[전환 절차](docs/ROLLOUT.md)를 따른다.
