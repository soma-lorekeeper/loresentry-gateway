# Auth·BFF 세션 통합 실행

`run.py`는 격리 PostgreSQL 18.4, Redis 7.4 또는 Valkey 9.0.6, 실제 Auth와 BFF 두 프로세스를
빌드·기동하고 로그인, 계정 조회, 새 로그인에 의한 교체와 로그아웃을 검사한다.
외부 Google 응답만 테스트 HTTP/JWK 서버로 대체한다. Auth의 OIDC 서명·nonce·PKCE,
계정 DB와 세션 처리는 실제 코드를 사용한다. 세션 ID의 정규 형식과 해시 인덱스,
양쪽 절대 만료 일치, 14일 활동 연장과 쿠키 Max-Age도 비교한다. 실제 브라우저·운영 검증은 별도다.

Linux 호스트의 Docker, Python 3와 Auth Git 저장소가 필요하다. Java 21과 Gradle은
컨테이너에서 실행한다. 기본 Auth 기준은 새 콜백 계약을 제공하는 커밋
`0adc41de89cb95df8408be770cbd59c81ba82f52`이며 원본 작업 트리는 변경하지 않는다.

```bash
python3 integration/session/run.py --auth-source ../loresentry-authentication
# 다른 확정 커밋을 검증할 때 지정한다.
python3 integration/session/run.py --auth-source ../loresentry-authentication --auth-ref <commit>
# 실제 Content의 26개 API 회귀를 추가한다.
# 저장소는 --redis-image redis:7.4-alpine 또는 valkey/valkey:9.0.6-alpine으로 선택한다.
python3 integration/session/run.py --content-source ../loresentry-content
```

Auth는 git archive 복사본에 GoogleFixture를 추가한다. BFF는 현재 작업 트리를
빌드하므로 변경사항을 포함한다. 보고서의 Git 기준 커밋과 JAR 해시를 함께 확인한다.
Content 기본 커밋은 `d26a3d3a244bdebb79375290b9d032f232da5563`이며
`--content-ref`로 바꿀 수 있다. Content는 별도 격리 DB를 사용한다.

서비스 JWT 키는 생성하거나 주입하지 않는다. Google 검증용 키만 fixture가 생성한다.
DB·Valkey·Java 포트는 loopback에 노출한다. BFF 계정은 세션 키의 GET과 Lua 검증·TTL
연장 명령만 허용한다. 기본 Redis 관리자는 테스트 준비와 장애 주입용이다.
운영 ACL은 [운영 문서](../../docs/OPERATIONS.md)에 따라 별도로 구성한다.

실행이 만든 컨테이너와 익명 볼륨, 환경 파일은 finally에서 정리한다.
`/tmp/bff-auth-integration-*`에는 소유자만 접근 가능한 로그와 비밀값 없는
report.json을 남긴다. 실제 Google 동의 화면, 브라우저 쿠키 정책, 운영 접근 제한과
이미지/S3 기능을 검증하는 도구는 아니다.

## 경쟁과 만료 경계

BFF의 저장소 연결은 테스트 전용 loopback RESP 프록시를 통과한다. 사전 GET 응답을
보관한 동안 다른 BFF가 로그인·폐기를 완료하도록 제어해 오래된 조회를 재현한다.
독립 HTTP 연결을 장벽에서 함께 시작해 동시 로그인과 같은 ID의 활동도 검사한다.

만료 직전·정확한 경계·직후는 실제 저장소 Lua의 동일 시각 안에서 만료를 ±1ms로
조정한 뒤 제품 검증 스크립트를 실행한다. 만료 경계에서 서버 시계를 변경하거나
14일을 대기하지 않는다. 공개 경로·preflight 비연장, 업무 오류의 연장과 손상 레코드
거절은 실제 두 BFF의 HTTP 응답으로 검사한다.

## 장애 주입

Auth와 BFF의 실제 Redis 연결에 별도 RESP 프록시를 둔다. 연결 초기화·GET·EVAL 지연,
EVAL 응답 유실과 연결 종료 후 명령 횟수·내부 HTTP 호출·쿠키를 비교한다. BFF의
500ms, Auth 폐기의 2초 제한에는 검사 시 HTTP·스케줄링 여유를 각각 400/300ms 허용한다.
재연결 자체와 로그인 쓰기 재실행을 구분하며 폐기 재시도 뒤 늦은 GET이 삭제를 시작하지
않는지도 검사한다. Lua 일부 쓰기 뒤 오류는 테스트 복사본의 해당 지점에만 주입한다.

ACL은 실제 BFF 드라이버의 인증 성공·연장과 명령 제한을 함께 검사한다.
애플리케이션 stdout/stderr의 원문 세션 ID와 fixture 비밀값도 확인한다.

## 실제 프론트 브라우저 연결

프론트 저장소에서 `pnpm check:cdn`으로 정적 결과를 만든 후 다음을 실행한다.

```bash
python3 integration/session/run.py \
  --content-source <content-checkout> \
  --browser-project <frontend-checkout> \
  --browser-only
```

`--browser-only`는 서버 통합 시나리오를 건너뛰고 실제 서버를 기동해 브라우저 검증만
수행한다. 생략하면 기존 서버 검증 뒤 브라우저를 검증한다. prod 프로필 BFF와 prod
콜백 URI를 사용하는 별도 Auth도 격리 환경에서 기동한다. 프론트의
`integration/browser/README.md`에 프록시·TLS 범위와 실제 Google 수동 절차가 있다.
이 옵션의 자동 검증에서 Google은 테스트 공급자이며 실제 Google 통과를 의미하지 않는다.

## 약관 동의 전체 흐름

프론트에서 `pnpm check:cdn`으로 정적 빌드를 만든 후 `--terms-project`를 사용한다.
Node.js, 설치된 `playwright-core` 모듈과 Chromium 실행 파일이 필요하다. 경로는 실행 환경에 맞춰 주입한다.

```bash
PLAYWRIGHT_MODULE=<playwright-core-module-path> \
CHROMIUM_EXECUTABLE=<chromium-executable-path> \
<workspace-python> integration/session/run.py --terms-project ../loresentry-frontend
```

워크스페이스 Python은 `tools/python/.venv/bin/python`의 절대 경로다.
`--auth-ref`로 다른 확정 커밋을 지정할 수 있으며 해당 커밋은 동의 API를 제공해야 한다.
이 모드는 격리 DB에 테스트 원문을 등록하고 Auth 동의 검사를 활성화한다. 운영 원문이나 DB는 사용하지 않는다.
현재·미래·개정 원문, 미동의·기존 동의 계정, 동시 완료·만료·응답 유실 복구를 HTTP로 검사한다.

프론트의 `integration/browser/terms.mjs`는 브라우저 전용 loopback 프록시로 고정 local Origin을
유지하면서 정적 빌드와 실제 BFF에 연결한다. 기존 개발 서버를 변경하지 않는다.
Google 인가 URL을 관찰한 뒤 테스트 code를 실제 콜백에 전달한다. 외부 Google 네트워크는 사용하지 않는다.
Chromium의 동의 쿠키·CSRF·닫기·재진입·새 본인 조회·로그아웃을 검사하고
`terms-browser-report.json`을 기본 `report.json`과 함께 남긴다.
실제 Google 화면과 Google 도메인의 브라우저 왕복, 운영 HTTPS·인프라 및 Content 업무 API는 별도 검증 대상이다.
