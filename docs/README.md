# BFF/Gateway 문서

**2026-09-26 목표 계약은 단일 세션 ID 쿠키와 공유 저장소 기반 인증이다.** 마지막 인증
활동 후 14일에 만료하고 보호 요청의 인증 성공마다 연장한다. 코드·설정·테스트·운영은
아직 이 계약으로 변경하지 않았다. 이전 구현의 검증 완료를 새 설계의 완료로 해석하지 않는다.

| 순서 | 문서 | 내용 |
|---|---|---|
| 1 | [역할과 구조](ARCHITECTURE.md) | BFF 책임과 패키지 경계 |
| 2 | [공유 세션 계약](../../loresentry-authentication/docs/session/SESSION_DESIGN.md) | 형식·저장소·검증과 TTL 연장 |
| 3 | [브라우저 보안](BROWSER_SECURITY.md) | 단일 쿠키·CSRF·CORS |
| 4 | [인증 연동](auth/README.md) | 로그인·보호 요청·로그아웃 |
| 5 | [외부 API](EXTERNAL_API.md) | 경로·응답·오류 |
| 6 | [프론트 인계](FRONTEND_AUTH_CONTRACT.md) | 쿠키 수명·오류와 인증 전환 조율 |
| 7 | [운영 준비](OPERATIONS.md) | 연결·TTL 변경 ACL·접근 제한 |
| 8 | [공동 전환](ROLLOUT.md) | 새 계약 구현·검증·배포·롤백 |

[내부 서비스 호출](../../docs/bff/INTERNAL_SERVICE_CALLS.md)과 [Content API](CONTENT_API.md)를 함께 따른다.
Content 도메인 API는 유지하며 인증 경계만 새 계약으로 전환한다.

`verification/`의 이슈별 문서는 이전 구현의 실행 기록이다. 수치·결론을 소급해 고치지 않는다.
[기존 통합 도구](../integration/session/README.md)도 새 계약으로 수정하고 다시 실행해야 한다.
Auth의 내부 계약은 [Auth 문서](../../loresentry-authentication/docs/README.md)를 참고한다.
