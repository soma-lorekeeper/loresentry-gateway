# BFF/Gateway 문서

BFF의 서비스별 설계·구현 기준은 이 저장소의 `docs/`에서 관리한다.
서비스 간 책임과 내부 호출·공동 전환 계약은 공통 문서 저장소를 링크로 참조한다.

Google 로그인, 토큰 재발급·로그아웃, 본인 계정과 Content 26개 API를 구현했다.
AT 검증 후 공유 활성 세션을 조회한다. 아래 순서로 읽고, 검증·운영 적용 범위는 별도
기록을 확인한다. 프론트 의존 검증 제외와 운영 준비 미완료를 구분한다.

| 순서 | 문서 | 확인할 내용 |
|---|---|---|
| 1 | [역할과 구조](ARCHITECTURE.md) | BFF의 책임과 코드 배치 |
| 2 | [브라우저 보안](BROWSER_SECURITY.md) | 쿠키·CSRF·CORS와 local/prod 설정 |
| 3 | [인증 연동](auth/README.md) | AT 검증과 로그인·재발급·로그아웃 흐름 |
| 4 | [외부 API](EXTERNAL_API.md) | 브라우저가 호출할 경로와 성공·실패 응답 |
| 5 | [내부 호출](../../docs/bff/INTERNAL_SERVICE_CALLS.md) | 내부 접근 제한·사용자 전달·타임아웃 |
| 6 | [프론트 인계](FRONTEND_AUTH_CONTRACT.md) | 쿠키 요청·CSRF·오류·탭 간 조율 |
| 7 | [운영 준비](OPERATIONS.md) | 필수 설정·ACL·접근 제한과 실제 미준비 항목 |
| 8 | [공동 전환](ROLLOUT.md) | Auth/BFF 혼재 방지·재로그인·롤백 위험 |

각 규칙은 해당 문서를 기준으로 하고, 다른 문서는 링크로 참조한다.
프로젝트·파일·에피소드의 [Content 외부 API 계약](CONTENT_API.md)과
[기존 연동 조사](CONTENT_CONTRACT_AUDIT.md)를 함께 참고한다.
여러 서비스 응답 조합과 AI 스트리밍은 해당 기능을 구현할 때 별도로 설계한다.

[일반·세션 테스트 안내](../README.md#검증), [실제 Auth 통합 결과](verification/LOREKEEPER-573.md),
[Content 결과와 브라우저 제외 범위](verification/LOREKEEPER-574.md),
[통합 검증 재현 도구](../integration/session/README.md)에서 실행 근거를 확인한다.

Auth 서버 내부 설계는 [Auth 서버 문서](../../loresentry-authentication/docs/README.md), 프로젝트 공통 맥락은
[루트 문서 안내](../../docs/README.md)를 참고한다.
