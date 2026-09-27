# BFF/Gateway 문서

현재 코드는 JWT 기반 AT·RT와 활성 세션 조회를 사용한다. 단일 세션 ID 쿠키와 보호 요청의
활동 만료 연장은 목표 설계이며 코드·설정·운영에 아직 반영하지 않았다.
현재 동작과 실행 방법은 [프로젝트 README](../README.md)를 따른다.

| 문서 | 내용 |
|---|---|
| [역할과 구조](ARCHITECTURE.md) | BFF 책임·패키지 경계·내부 서비스 호출 |
| [공유 세션 계약](../../loresentry-authentication/docs/session/SESSION_DESIGN.md) | Auth·BFF 공통 형식·저장소·검증과 TTL 연장 |
| [브라우저 보안](BROWSER_SECURITY.md) | 단일 쿠키·CSRF·CORS |
| [로그인](auth/LOGIN_FLOW.md) | Google 이동·콜백·임시 쿠키 처리 |
| [세션 활동](auth/SESSION_FLOW.md) | 보호 요청 인증·만료 연장·오류 |
| [로그아웃](auth/LOGOUT_FLOW.md) | 조건부 폐기·쿠키 삭제 |
| [제공 API](API.md) | 브라우저가 BFF를 호출하는 방법과 인증·계정 계약 |
| [호출 API](API_CALLS.md) | BFF의 Auth·Content 호출 목록·입력 구성·응답 변환 |
| [제공 API의 Content 상세](CONTENT_API.md) | 브라우저용 26개 API의 요청·응답·헤더와 오류 |
| [프론트 연동](FRONTEND_AUTH_CONTRACT.md) | 쿠키 수명·오류·인증 전환 조율 |
| [운영 준비](OPERATIONS.md) | 연결·TTL 변경 ACL·접근 제한 |
| [공동 전환](ROLLOUT.md) | Auth·BFF·프론트 배포·검증·롤백 |

`API.md`는 BFF가 제공하는 API, `API_CALLS.md`는 BFF가 호출하는 API를 관리한다.
Content 상세 명세도 BFF 제공 계약이며 Content 서버의 명세와 구분한다.

작업 이력과 테스트 실행 결과는 해당 이슈에서 관리한다. 저장소에는 설계·사용법·검증 기준을 유지한다.
[통합 도구](../integration/session/README.md)는 현재 JWT 방식을 검증하며 세션 ID 전환 시 수정해야 한다.
Auth의 내부 계약은 [Auth 문서](../../loresentry-authentication/docs/README.md)를 참고한다.
