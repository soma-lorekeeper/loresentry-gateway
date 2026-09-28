# BFF/Gateway 문서 안내

> **책임:** 작업에 필요한 기준 문서와 문서별 담당 범위를 안내한다.
>
> **확인할 때:** 어떤 문서를 읽거나 수정해야 할지 정할 때.

실행·테스트 명령은 [프로젝트 README](../README.md)를 따른다.

| 필요한 작업·질문 | 기준 문서 | 담당 범위 |
|---|---|---|
| 로직을 어느 계층에 둘 것인가? | [서버 구조](ARCHITECTURE.md) | BFF 책임·패키지·의존 방향·데이터와 오류 전달 경계 |
| 브라우저가 BFF를 어떻게 호출하는가? | [제공 API](API.md) | 공통 HTTP 계약·인증·계정·오류 응답 |
| 브라우저의 Content 요청·응답은 무엇인가? | [Content 제공 API](CONTENT_API.md) | 38개 경로·DTO·저장 헤더·도메인 오류 |
| BFF가 다른 서버를 어떻게 호출하는가? | [호출 API](API_CALLS.md) | Auth·Content 호출 매핑·내부 입력·응답 변환·통신 제한 |
| 쿠키·CSRF·CORS를 어떻게 처리하는가? | [브라우저 보안](BROWSER_SECURITY.md) | 쿠키 속성·환경별 주소·출처와 헤더 검사 |
| Google 로그인 요청을 어떻게 연결하는가? | [로그인 흐름](auth/LOGIN_FLOW.md) | 페이지 이동·콜백·동의 완료 순서·OAuth 임시 쿠키 |
| 보호 요청을 어떤 순서로 인증하는가? | [세션 활동 흐름](auth/SESSION_FLOW.md) | CSRF·세션 검증·활동 연장·사용자 전달 순서 |
| 로그아웃에서 무엇을 먼저 처리하는가? | [로그아웃 흐름](auth/LOGOUT_FLOW.md) | CSRF·Auth 폐기·쿠키 삭제의 순서와 실패 처리 |
| 프론트는 인증 상태와 진행 요청을 어떻게 관리하는가? | [프론트 연동](FRONTEND_AUTH_CONTRACT.md) | 로그인 확인·동의 모달·오류별 화면 동작·탭 간 인증 전환 조율 |
| 운영 환경에 어떤 설정과 권한이 필요한가? | [운영 설정](OPERATIONS.md) | 환경변수·Redis ACL·네트워크 접근·관측 기준 |
| 배포·롤백·저장소 복구를 어떻게 수행하는가? | [배포·복구](ROLLOUT.md) | 서비스 호환성·트래픽 제어·재인증·복구 확인 |
| Auth·BFF가 공유하는 저장 규칙은 무엇인가? | [Auth 세션 계약](../../loresentry-authentication/docs/session/SESSION_DESIGN.md) | ID 형식·Redis 레코드·수명·원자성·동시성 |

## 문서 책임과 상태

각 규칙은 담당 문서에서 관리하고 다른 문서는 링크로 참조한다. `API.md`와
`CONTENT_API.md`는 BFF가 제공하는 계약이며, `API_CALLS.md`는 호출 구성과 결과 사용을 설명한다.
Auth의 필드·오류 정의는 [Auth 제공 API](../../loresentry-authentication/docs/API.md)를 따른다.

문서는 현재 구현의 계약과 반복해서 사용할 운영 절차를 유지한다. 일회성 전환 작업,
작업 이력·테스트 실행 결과·실제 배포 상태는 해당 이슈에서 관리한다.
약관 동의는 각 담당 문서의 **MVP 미구현** 절에서 관리한다.
브라우저 요청·응답은 [제공 API](API.md#약관-동의-api-mvp-미구현), 내부 호출 구성은
[호출 API](API_CALLS.md#약관-동의-호출-mvp-미구현), 쿠키 속성은
[브라우저 보안](BROWSER_SECURITY.md#동의-대기-쿠키-mvp-미구현), 처리 순서는
[로그인 흐름](auth/LOGIN_FLOW.md#약관-동의-연동-mvp-미구현), 모달·화면 동작은
[프론트 연동](FRONTEND_AUTH_CONTRACT.md#약관-동의-mvp-미구현)이 담당한다.
계정·원문·동의 기록·대기 저장과 내부 API 정의는 [Auth 설계](../../loresentry-authentication/docs/account/TERMS_CONSENT_DESIGN.md)를 따른다.
[통합 도구 사용법](../integration/session/README.md)은 실행 범위·기준 커밋·옵션을 안내한다.
