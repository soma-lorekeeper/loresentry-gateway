# Content 회귀 결과와 브라우저 검증 대기 조건

2026-09-24, LOREKEEPER-574는 **진행 중**이다. 실제 Content의 기존 26개 API 회귀는
통과했지만, 프론트 인증 구현과 local 포트 전달·HTTPS 브라우저 검증이 준비되지 않아
이슈 전체를 완료하지 않았다. 이 결과를 브라우저 E2E나 배포 준비 완료로 사용하지 않는다.

## 수행한 검증

```bash
python3 integration/session/run.py --content-source ../loresentry-content
```

Auth `e9d5b5b35dace0b9c7066ec93d1ea35e018963e7`, Content
`d26a3d3a244bdebb79375290b9d032f232da5563`, PostgreSQL 18.4, Valkey 9.0.6과 BFF 두
인스턴스를 격리 실행했다. 원본 Auth·Content 작업 트리와 운영 서버·DB·AWS는 변경하지 않았다.
실행 결과는 Linux 호스트 `/tmp/bff-auth-integration-17aprzwe/report.json`에 남았다.

- 실제 Auth 로그인으로 발급한 쿠키를 사용해 26개 Content 경로를 HTTP로 호출했다.
  다른 사용자의 프로젝트·문서는 404이고, 외부 X-User-Id로 소유자를 사칭해도 거절됐다.
- 프로젝트·파일·에피소드 생성·조회·수정·이동·삭제, 최신 `last_file` 응답, 잠금·버전·휴지통
  동작을 확인했다. `If-Match` 저장, 같은 `X-Save-Id`의 중복 저장 방지, 409의 current와
  null 또는 실제 base 스냅샷을 확인했다.
- 한글 검색어를 전달해 해당 문서를 찾았다. 프로젝트 생성의 Location과 CORS 노출 헤더,
  정확한 Origin·credentials 허용 응답, Content의 no-store를 확인했다. 이는 HTTP 헤더
  검증이며 실제 브라우저의 쿠키 저장·전송 검증은 아니다.
- 같은 실행에서 [LOREKEEPER-573](LOREKEEPER-573.md)의 실제 Auth·단일 세션·다중 BFF·장애
  시나리오도 다시 통과했다. 일반 빌드 204개와 별도 Redis·Valkey 10개 테스트 결과는
  해당 기록을 따른다. 이후 변경은 이 통합 검증 도구와 문서에 한정된다.

## 프론트 선행 조건

2026-09-24 원격 main을 다시 조회한 프론트 기준 커밋은
`afa4fde071cd4ad843f67f95fe59531932b133b9`다.

| 확인한 코드 | 현재 상태와 필요한 작업 |
| --- | --- |
| `src/services/api/http.ts` | X-User-Id를 전송한다. credentials: include와 X-LS-CSRF 처리가 필요하다. |
| `src/services/api/identity.ts` | localStorage 개발 UUID를 사용한다. 실제 인증 요청에서 제거해야 한다. |
| `src/services/api/index.ts` | auth·account는 실제 API 어댑터가 아닌 상태다. BFF Auth API 연결이 필요하다. |
| `src/features/auth/login-page.tsx` | auth 쿼리와 mock 흐름을 사용한다. 고정 result 값 처리 후 users/me로 로그인 성공을 확인해야 한다. |
| 인증 요청 조율 | 명시적 재발급·원래 요청 1회 재시도·탭 간 조율·늦은 오류 처리는 아직 검증할 구현이 없다. |

프론트 구현은 이 BFF 이슈의 변경 범위에 포함하지 않았다. 계약은
[프론트 인증 인계](../FRONTEND_AUTH_CONTRACT.md)를 따른다. 준비된 프론트 커밋과
local 포트 전달·HTTPS 검증 환경이 제공되면 다음 항목을 실제 브라우저에서 재개한다.

1. 외부 OAuth 왕복의 Lax 임시 쿠키, 복귀 후 Strict·HttpOnly 인증 쿠키와 credentials 전송.
2. users/me 확인, 명시적 재발급과 원래 요청의 한 번 재시도, 여러 탭의 재발급 경쟁 방지.
3. SESSION_INVALID·SESSION_UNAVAILABLE·CSRF·내부 401·로그아웃 실패에서 무한 재발급 방지와
   늦은 실패의 최신 쿠키·화면 상태 보호.
4. 실제 브라우저에서 프로젝트 조회·문서 저장·충돌과 필요한 응답 헤더 접근.

## 검증 중 확인한 후속 범위

Content 최신 main은 계획 기준 이후 이미지 티켓·완료·조회 API 세 개를 추가했다.
이번 BFF의 명시적 노출 목록 26개와 현재 프론트 API 어댑터에는 없는 기능이다. 해당 경로를
임의 relay로 공개하지 않았으며, 이미지 연동은 외부 DTO·오류·업로드 URL 및 S3 검증 범위를
별도로 확정해야 한다. `last_file` 추가 동작은 기존 응답 DTO와 호환되며 이번에 검증했다.

순차 Work 실행 계약에 따라 LOREKEEPER-572 완료·딜리버러블 통합과 다음 Work
LOREKEEPER-575는 아직 수행하지 않았다. 원격 main push와 자동 배포도 실행하지 않았다.
