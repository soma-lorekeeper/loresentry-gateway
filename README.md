# loresentry-gateway

> **책임:** 서비스를 소개하고 로컬 실행·테스트·CI/CD 사용법을 안내한다.
>
> **확인할 때:** 개발 환경을 준비하거나 빌드·테스트를 실행할 때.

Lore Sentry의 브라우저 API 경계다. Java 21, Spring Boot 4.1.1 Servlet MVC와
RestClient를 사용한다. Auth·Content의 명시적 API를 호출하며 도메인 권한은
각 서비스가 판단한다. 브라우저 인증은 단일 세션 ID를 사용한다.

제공 계약은 [API.md](docs/API.md), 서버 호출은 [API_CALLS.md](docs/API_CALLS.md),
문서별 역할은 [문서 안내](docs/README.md)에서 확인한다.

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

local/prod 중 하나의 프로필이 필요하다. 환경별 주소와 쿠키 설정은
[브라우저 보안](docs/BROWSER_SECURITY.md#환경별-설정)을 따른다.
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
배포 전 확인과 복구 절차는 [배포·복구](docs/ROLLOUT.md)를 따른다.

### Redis·Valkey 통합 테스트

격리된 Redis 7.4와 Valkey 9.0.6 컨테이너에
[`src/test/resources/session-redis.conf`](src/test/resources/session-redis.conf)를 설정 파일로
마운트하고 해당 설정으로 서버를 시작한다. 호스트에는 루프백 포트로만 노출한다.
이 설정은 비밀번호 없는 관리자 계정을 포함하므로 테스트 전용으로 사용한다.
Gradle 프로세스가 두 서버의 루프백 포트에 접근할 수 있어야 한다.

각 포트를 위 명령의 `TEST_REDIS_PORT`, `TEST_VALKEY_PORT`로 지정한다.
