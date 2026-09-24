# BFF의 Auth 연동

인증 책임을 먼저 읽고, 구현할 기능의 흐름을 확인한다.

**2026-09-24 후속 설계:** [단일 로그인 세션](../../../loresentry-authentication/docs/token/SINGLE_SESSION_DESIGN.md)에 따라
AT 검증 후 Redis의 활성 세션 조회를 구현했다. 변경 범위에서는 새 문서를 우선하며,
기존의 Redis 미조회 계약은 대체된다. 실제 Auth·BFF 연동 결과는
[통합 검증](../verification/LOREKEEPER-573.md)에 있다. 운영 반영은 미완료이며
[운영 준비](../OPERATIONS.md)와 [공동 전환](../ROLLOUT.md)을 따른다.

| 문서 | 내용 |
|---|---|
| [단일 로그인 세션](../../../loresentry-authentication/docs/token/SINGLE_SESSION_DESIGN.md) | 세션 조회·오류 응답·읽기 권한과 Auth 상태 변경 계약 |
| [인증 책임](../../../docs/bff/auth/AUTH_RESPONSIBILITIES.md) | 공통 책임 범위와 AT 검증; 저장소 조회 계약은 위 단일 세션 설계 우선 |
| [로그인 연동](LOGIN_FLOW.md) | 로그인 시작·콜백, OAuth 임시 쿠키와 프론트 복귀 |
| [재발급 연동](REFRESH_FLOW.md) | 명시적 재발급, 동시 요청 조율과 실패 전달 |
| [로그아웃 연동](LOGOUT_FLOW.md) | RT 폐기 요청, 쿠키 삭제와 실패 전달 |

## 구현·검증 경계

경로·응답은 [외부 API](../EXTERNAL_API.md), Auth 요청·응답은
[내부 API](../../../loresentry-authentication/docs/INTERNAL_API.md)를 기준으로 구현했다.
프론트의 결과 처리와 재발급 조율 구현은 남아 있으며, 프론트가 필요한 검증은
[사용자 승인으로 이번 완료 범위에서 제외](../verification/LOREKEEPER-574.md)했다.

배포 전 작업은 [JWT 배포 협의](../../../loresentry-authentication/docs/token/JWT_DESIGN.md#배포-시-협의-사항)와
[내부 서비스 접근 제한 검증](../../../docs/bff/INTERNAL_SERVICE_CALLS.md#내부-서비스-호출)을 따른다.
