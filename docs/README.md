# BFF/Gateway 문서

BFF의 서비스별 설계·구현 기준은 이 저장소의 `docs/`에서 관리한다.
서비스 간 책임과 내부 호출·공동 전환 계약은 공통 문서 저장소를 링크로 참조한다.

초기 범위는 Google 로그인, 토큰 재발급·로그아웃, 본인 계정 조회·수정이다.
아래 순서로 읽는다. 문서는 구현 기준이며, 코드·프론트 연동·배포 검증 완료를 의미하지 않는다.

| 순서 | 문서 | 확인할 내용 |
|---|---|---|
| 1 | [역할과 구조](ARCHITECTURE.md) | BFF의 책임과 코드 배치 |
| 2 | [브라우저 보안](BROWSER_SECURITY.md) | 쿠키·CSRF·CORS와 local/prod 설정 |
| 3 | [인증 연동](auth/README.md) | AT 검증과 로그인·재발급·로그아웃 흐름 |
| 4 | [외부 API](EXTERNAL_API.md) | 브라우저가 호출할 경로와 성공·실패 응답 |
| 5 | [내부 호출](../../docs/bff/INTERNAL_SERVICE_CALLS.md) | 내부 접근 제한·사용자 전달·타임아웃 |

각 규칙은 해당 문서를 기준으로 하고, 다른 문서는 링크로 참조한다.
프로젝트·파일 API, 응답 조합과 AI 스트리밍은 해당 기능을 구현할 때 별도로 설계한다.

Auth 서버 내부 설계는 [Auth 서버 문서](../../loresentry-authentication/docs/README.md), 프로젝트 공통 맥락은
[루트 문서 안내](../../docs/README.md)를 참고한다.
