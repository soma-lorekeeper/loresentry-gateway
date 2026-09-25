# BFF의 Auth 연동

2026-09-26 목표 설계다. 브라우저의 단일 세션 ID를 공유 저장소에서 확인하고 활동 만료를 연장한다.

| 문서 | 내용 |
|---|---|
| [세션 계약](../../../loresentry-authentication/docs/session/SESSION_DESIGN.md) | ID·저장소·원자적 검증과 연장·폐기 |
| [인증 책임](../../../docs/bff/auth/AUTH_RESPONSIBILITIES.md) | Auth와 BFF의 경계 |
| [로그인](LOGIN_FLOW.md) | Google 이동·콜백과 쿠키 |
| [세션 활동](SESSION_FLOW.md) | 보호 요청 인증·만료 연장·오류 |
| [로그아웃](LOGOUT_FLOW.md) | 조건부 폐기·쿠키 삭제 |

구현 기준은 [외부 API](../EXTERNAL_API.md)와 [Auth 내부 API](../../../loresentry-authentication/docs/INTERNAL_API.md)다.
새 계약 구현·프론트 연동·검증·운영 준비는 [공동 전환](../ROLLOUT.md)을 따른다.
