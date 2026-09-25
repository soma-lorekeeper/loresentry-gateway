# LOREKEEPER-587 검증 기록

2026-09-26, 로그인 콜백은 Auth의 session_id와 expires_at을 검증하여 단일 세션
쿠키를 발급한다. OAuth 임시 쿠키의 소비 여부와 고정 로그인 복귀 주소를 유지한다.
POST /auth/sessions/revoke는 CSRF 검사 뒤 쿠키의 ID만 Auth에 전달한다.
쿠키 없음, 잘못된 ID, 폐기 확인, 통신 실패를 session_revocation으로 구분하며
CSRF를 통과한 응답에는 동일 범위의 세션 쿠키 삭제 헤더를 반환한다.

Java 21에서 전체 단위·MVC·HTTP 클라이언트 테스트 227개를 통과했다.
성공 및 잘못된 콜백 결과, OAuth 소비 상태, 고정 리다이렉트, 중복·빈·잘못된
세션 쿠키, Auth 오류와 응답 유실을 확인했다. 내부 통신은 자동 재시도하지 않는다.
민감한 응답과 ID를 브라우저 본문·복귀 URL·진단 문자열에 포함하지 않는다.

build 및 sessionIntegrationTest를 Redis 7.4와 Valkey 9.0.6에서 통과했다.
실제 브라우저의 쿠키 적용과 탭 경합은 후속 Work 599에서 검증한다.
