# BFF의 Auth 연동

인증 책임을 먼저 읽고, 구현할 기능의 흐름을 확인한다.

**2026-09-24 후속 설계:** [단일 로그인 세션](../../../loresentry-authentication/docs/token/SINGLE_SESSION_DESIGN.md)에 따라
AT 검증 후 Redis의 활성 세션 조회를 추가한다. 변경 범위에서는 새 문서를 우선하며,
기존의 Redis 미조회 계약은 대체된다. Auth 구현은 완료했으며 BFF 구현·연동 검증은 남아 있다.
필요한 스키마·오류·ACL·배포 조건은 [전환 인계](../../../docs/auth/implementation/SESSION_HANDOFF.md)를 참고한다.

| 문서 | 내용 |
|---|---|
| [단일 로그인 세션](../../../loresentry-authentication/docs/token/SINGLE_SESSION_DESIGN.md) | 세션 조회·오류 응답·읽기 권한과 Auth 상태 변경 계약 |
| [인증 책임](../../../docs/bff/auth/AUTH_RESPONSIBILITIES.md) | 책임 범위, stateless 인증과 AT 검증 |
| [로그인 연동](LOGIN_FLOW.md) | 로그인 시작·콜백, OAuth 임시 쿠키와 프론트 복귀 |
| [재발급 연동](REFRESH_FLOW.md) | 명시적 재발급, 동시 요청 조율과 실패 전달 |
| [로그아웃 연동](LOGOUT_FLOW.md) | RT 폐기 요청, 쿠키 삭제와 실패 전달 |

## 남은 설계

초기 BFF 연동 계약은 정리했다. 경로·응답은 [외부 API 초안](../EXTERNAL_API.md),
Auth 요청·응답은 [내부 API](../../../loresentry-authentication/docs/INTERNAL_API.md)를 기준으로 구현한다.
프론트의 결과 처리와 재발급 조율 구현·검증은 남아 있다.

배포 전 작업은 [JWT 배포 협의](../../../loresentry-authentication/docs/token/JWT_DESIGN.md#배포-시-협의-사항)와
[내부 서비스 접근 제한 검증](../../../docs/bff/INTERNAL_SERVICE_CALLS.md#내부-서비스-호출)을 따른다.
